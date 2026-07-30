package com.relaypony.desktop

import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity
import com.relaypony.session.FileNames
import com.relaypony.session.Ident
import com.relaypony.session.OutgoingFile
import com.relaypony.session.SocketTransfer
import com.relaypony.session.WifiIdent
import com.relaypony.session.pairing.Pairing
import com.relaypony.session.pairing.TrustStore
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * What happens once a Wi-Fi Direct group exists — and it is the half that needed no invention.
 *
 * The Android build already defines this exchange, so this is a faithful port rather than a new
 * protocol: the group owner listens on 8987 and the client connects to it; both sides write their
 * [Ident] and read the peer's; [WifiIdent.resolveISend] settles who sends; then an ordinary
 * [SocketTransfer] session runs on 8988. Because the session, wire and crypto code here is the
 * exact same source the phone ships, a laptop is just another peer.
 *
 * Two details are deliberately copied rather than improved, because interop beats elegance:
 *
 *  * Wire version 1. Android's Wi-Fi Direct path calls `sendTo` without `peerMaxWire`, which
 *    defaults to the frozen v1. Sending v2 here would break against every shipped phone.
 *  * The pairing gate stays exactly where Android puts it — checked after the ident exchange, on
 *    the sending side, against the handle the peer actually presented. A Wi-Fi Direct link is not
 *    a trust decision; the pinned key still is.
 */
object WifiDirectSession {

    /** Fixed ports, matching the Android implementation. Changing either breaks phone interop. */
    const val PORT_IDENT = 8987
    const val PORT_TRANSFER = 8988

    private const val IDENT_TIMEOUT_MS = 60_000
    private const val IDENT_CONNECT_ATTEMPTS = 20
    private const val IDENT_RETRY_MS = 500L
    private const val TRANSFER_TIMEOUT_MS = 120_000
    private const val TRANSFER_CONNECT_ATTEMPTS = 10
    private const val TRANSFER_RETRY_MS = 500L

    sealed interface Outcome {
        data class Sent(val files: Int, val to: String) : Outcome
        data class Received(val files: List<File>, val from: String) : Outcome
    }

    /**
     * Run one transfer over an established group.
     *
     * [peerAddress] is the group owner's IP when we are the client, and ignored when we are the
     * owner (we learn the peer's address from the socket it connects on).
     */
    fun run(
        isGroupOwner: Boolean,
        peerAddress: String?,
        provider: CryptoProvider,
        identity: Identity,
        deviceName: String,
        myHandle: String,
        trust: TrustStore,
        filesToSend: List<File>,
        inbox: File,
        onStatus: (String) -> Unit = {},
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Outcome {
        val wantsToSend = filesToSend.isNotEmpty()
        val mine = Ident(provider.schemeId.toInt(), myHandle, deviceName, wantsToSend)

        onStatus("Exchanging identities…")
        val (theirs, peerIp) = exchangeIdent(isGroupOwner, peerAddress, mine)
        onStatus("Linked with ${theirs.deviceName}")

        return if (WifiIdent.resolveISend(mine, theirs)) {
            if (!Pairing.canSendOneTap(theirs.handle, trust)) {
                throw IllegalStateException(
                    "Not paired with ${theirs.deviceName}. Pair first — over Wi-Fi Direct there is " +
                        "no QR exchange, so the pairing has to already exist."
                )
            }
            sendOver(peerIp, theirs, provider, deviceName, myHandle, filesToSend, onStatus, onProgress)
        } else {
            receiveOver(provider, identity, deviceName, myHandle, inbox, theirs.deviceName, onStatus)
        }
    }

    /**
     * The group owner listens; the client connects. Both write first and then read, which is safe
     * because an [Ident] is a few hundred bytes and fits inside any socket buffer.
     *
     * The client retries: wpa_supplicant reports the group as started slightly before the far side
     * is actually accepting connections, so the first few attempts are expected to fail.
     */
    private fun exchangeIdent(isGroupOwner: Boolean, goAddress: String?, mine: Ident): Pair<Ident, String> {
        if (isGroupOwner) {
            ServerSocket(PORT_IDENT).use { server ->
                server.soTimeout = IDENT_TIMEOUT_MS
                server.accept().use { sock ->
                    val peerIp = sock.inetAddress?.hostAddress ?: "unknown"
                    WifiIdent.writeTo(sock.getOutputStream(), mine)
                    return WifiIdent.readFrom(sock.getInputStream()) to peerIp
                }
            }
        }
        val addr = goAddress ?: throw IllegalStateException("no group owner address to connect to")
        connectWithRetry(addr, PORT_IDENT, IDENT_CONNECT_ATTEMPTS, IDENT_RETRY_MS).use { sock ->
            WifiIdent.writeTo(sock.getOutputStream(), mine)
            return WifiIdent.readFrom(sock.getInputStream()) to addr
        }
    }

    private fun sendOver(
        peerIp: String,
        theirs: Ident,
        provider: CryptoProvider,
        deviceName: String,
        myHandle: String,
        files: List<File>,
        onStatus: (String) -> Unit,
        onProgress: ((Long, Long) -> Unit)?,
    ): Outcome {
        val recipient = provider.recipientFromQr(theirs.handle.toByteArray(Charsets.UTF_8))
        val outgoing = files.map { f ->
            OutgoingFile(f.name, "application/octet-stream", f.length()) { f.inputStream() }
        }
        onStatus("Sending ${files.size} file(s) to ${theirs.deviceName}…")
        var attempt = 0
        while (true) {
            try {
                SocketTransfer.sendTo(
                    peerIp, PORT_TRANSFER, provider, listOf(recipient), deviceName, myHandle, outgoing,
                    onProgress = onProgress,
                )
                return Outcome.Sent(files.size, theirs.deviceName)
            } catch (e: ConnectException) {
                if (++attempt >= TRANSFER_CONNECT_ATTEMPTS) throw e
                Thread.sleep(TRANSFER_RETRY_MS)
            }
        }
    }

    private fun receiveOver(
        provider: CryptoProvider,
        identity: Identity,
        deviceName: String,
        myHandle: String,
        inbox: File,
        theirName: String,
        onStatus: (String) -> Unit,
    ): Outcome {
        onStatus("Receiving from $theirName…")
        ServerSocket(PORT_TRANSFER).use { server ->
            server.soTimeout = TRANSFER_TIMEOUT_MS
            val written = LinkedHashMap<String, File>()
            val result = SocketTransfer.receiveOnceFrom(
                server, provider, identity, deviceName = deviceName, recipientHandle = myHandle,
            ) { entry ->
                val dest = uniqueFile(inbox, FileNames.sanitize(entry.name))
                written[entry.name] = dest
                dest.outputStream()
            }
            return Outcome.Received(written.values.filter { it.exists() }, result.senderName)
        }
    }

    private fun connectWithRetry(host: String, port: Int, attempts: Int, delayMs: Long): Socket {
        var last: Exception? = null
        repeat(attempts) {
            try {
                return Socket().apply { connect(InetSocketAddress(host, port), 5_000) }
            } catch (e: Exception) {
                last = e
                Thread.sleep(delayMs)
            }
        }
        throw last ?: ConnectException("could not reach $host:$port")
    }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }
}
