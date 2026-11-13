package com.relaypony.desktop

import com.relaypony.crypto.AgeProvider
import com.relaypony.pake.WordCodePairing
import com.relaypony.session.pairing.PakeDetails
import com.relaypony.session.pairing.PeerRoute
import com.relaypony.session.wan.RelayClient
import com.relaypony.session.wan.RelayUrls

/**
 * `relaypony pair --words` shows a word code for the other device to type, and
 * `relaypony pair --word-code <code> [--relay <url>]` enters one (PROTOCOL_v3 section 7).
 */
internal fun cmdPairWords(showCode: Boolean, code: String?, relayArg: String?) {
    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val myHandle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val trust = FileTrustStore(Config.trustFile)
    val routes = FilePeerRouteStore()
    val settings = DesktopSettings()
    val myRelay = RelayUrls.normalize(settings.relayServer)
    val clients = HashMap<String, RelayClient>()
    val wc = WordCodePairing(PakeDetails(myHandle, Config.loadName(), settings.inboxId, myRelay), { r ->
        clients.getOrPut(r) { RelayClient(RelayUrls.base(r)) }
    })

    val result = try {
        if (showCode) {
            if (RelayClient(RelayUrls.base(myRelay)).info()?.hasNameplates != true) {
                System.err.println("This computer's relay doesn't support word codes. Use `relaypony pair` for a QR code.")
                kotlin.system.exitProcess(1)
            }
            val shown = wc.claim()
            println("Type this code on the other device:")
            println()
            println("    ${shown.code}")
            if (shown.relay.isNotEmpty()) println("    relay: ${RelayUrls.base(shown.relay)}")
            println()
            println("It works once and expires in two minutes. Waiting…")
            wc.runAsA(shown)
        } else {
            wc.runAsB(code.orEmpty().trim(), RelayUrls.normalize(relayArg.orEmpty()))
        }
    } catch (e: LinkageError) {
        System.err.println("Word codes aren't available in this build. Use `relaypony pair` for a QR code.")
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        WordCodePairing.Result.Failed(e.message ?: "relay unreachable")
    }

    when (result) {
        is WordCodePairing.Result.Paired -> {
            trust.pin(result.peer.handle, result.peer.name)
            routes.put(result.peer.handle, PeerRoute(result.peer.inboxId, result.peer.relay))
            println("Paired with ${result.peer.name}.")
            return
        }
        WordCodePairing.Result.Mismatch -> System.err.println("The codes didn't match. Start again with a new code.")
        WordCodePairing.Result.Timeout -> System.err.println("Nobody entered the code in time, or it has expired.")
        WordCodePairing.Result.Cancelled -> System.err.println("Cancelled.")
        is WordCodePairing.Result.Failed -> System.err.println(
            when (result.kind) {
                WordCodePairing.FailKind.NOT_A_CODE -> "That isn't a word code. It looks like 47-lantern-orbit-maple."
                WordCodePairing.FailKind.OWN_CODE -> "That's this computer's own code. Enter it on the other device instead."
                WordCodePairing.FailKind.UNEXPECTED -> "The other device sent something unexpected. Start again."
                WordCodePairing.FailKind.RELAY -> "Couldn't reach the relay (${result.reason})."
            },
        )
    }
    kotlin.system.exitProcess(1)
}
