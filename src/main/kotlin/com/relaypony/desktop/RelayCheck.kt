package com.relaypony.desktop

import com.relaypony.crypto.AgeProvider
import com.relaypony.session.pairing.InboxIds
import com.relaypony.session.pairing.PairMessages
import com.relaypony.session.wan.RelayClient
import com.relaypony.session.wan.RelaySignaling
import com.relaypony.session.wan.RelayUrls
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * `relaypony relay-check`: exercises every relay call internet transfers make, against this
 * computer's own relay and handle, with no other device involved. Each step prints OK or what
 * went wrong, so a failure can be pinned on the relay plumbing or ruled out.
 */
internal fun cmdRelayCheck() {
    val provider = AgeProvider()
    val identity = FileIdentityStore(Config.identityFile).loadOrCreate(provider)
    val myHandle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
    val settings = DesktopSettings()
    val relay = RelayUrls.normalize(settings.relayServer)
    val base = RelayUrls.base(relay)
    println("Relay:  $base")
    println("Handle: $myHandle")
    println()

    var failures = 0
    fun step(name: String, block: () -> String) {
        print("$name… ")
        val r = runCatching(block)
        r.onSuccess { println("OK ($it)") }.onFailure { failures++; println("FAILED: ${it.message ?: it.javaClass.simpleName}") }
    }

    val client = RelayClient(base)
    step("1. Relay info") {
        val info = client.info() ?: error("no answer from $base/api/signal")
        "version ${info.version}, features ${info.features.sorted().joinToString(",").ifEmpty { "none" }}"
    }

    // Signaling to a handle: what a WAN offer uses when no inbox is known.
    step("2. Signal to this computer's handle and poll it back") {
        val signaling = RelaySignaling(myHandle, base)
        val sealed = PairMessages.seal(provider, myHandle, "RPCHECK|handle|${System.nanoTime()}")
        signaling.sendRaw(myHandle, sealed)
        var got: ByteArray? = null
        repeat(6) {
            if (got == null) {
                signaling.poll().forEach { line -> signaling.sealedPayload(line)?.let { if (it.contentEquals(sealed)) got = it } }
                if (got == null) Thread.sleep(500)
            }
        }
        got ?: error("sent, but polling didn't return it (${signaling.stats})")
        "round trip matched, ${signaling.stats}"
    }

    // Mailboxes: what pairing and 4.0 signaling use. A throwaway inbox, so nothing real is consumed.
    step("3. Mailbox send and poll") {
        val box = InboxIds.generate()
        val payload = PairMessages.seal(provider, myHandle, "RPCHECK|mbox|${System.nanoTime()}")
        client.mboxSend(box, payload)
        var back: List<ByteArray> = emptyList()
        repeat(6) { if (back.isEmpty()) { back = client.mboxPoll(box); if (back.isEmpty()) Thread.sleep(500) } }
        if (back.none { it.contentEquals(payload) }) error("sent, but the poll returned ${back.size} blob(s) and none matched")
        "round trip matched"
    }

    // The relay-forward stream: what a WAN transfer falls back to when hole punching fails.
    step("4. Relay stream data and fetch") {
        val session = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val chunk = "UmVsYXlQb255IGNoZWNr"
        val endpoint = "${base.trimEnd('/')}/api/signal"
        val sent = post(endpoint, JSONObject().put("op", "data").put("to", myHandle).put("session", session).put("chunks", JSONArray().put(chunk)))
        var got = emptyList<String>()
        repeat(6) {
            if (got.isEmpty()) {
                val resp = post(endpoint, JSONObject().put("op", "fetch").put("for", myHandle).put("session", session))
                val arr = runCatching { JSONObject(resp.second).optJSONArray("chunks") }.getOrNull() ?: JSONArray()
                got = (0 until arr.length()).map { arr.getString(it) }
                if (got.isEmpty()) Thread.sleep(500)
            }
        }
        if (chunk !in got) error("data answered HTTP ${sent.first} ${sent.second.take(120)}; fetch returned ${got.size} chunk(s)")
        "round trip matched"
    }

    println()
    println(if (failures == 0) "All relay calls work from this computer." else "$failures step(s) failed. Paste this output to the developer.")
}

private fun post(url: String, body: JSONObject): Pair<Int, String> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 8000; readTimeout = 12000; doOutput = true
        setRequestProperty("Content-Type", "application/json")
    }
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()
    return code to text
}
