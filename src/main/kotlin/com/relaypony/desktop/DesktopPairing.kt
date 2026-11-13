package com.relaypony.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.relaypony.pake.WordCodePairing
import com.relaypony.session.MainThread
import com.relaypony.session.pairing.PairingFlow
import com.relaypony.session.pairing.PairingService
import com.relaypony.session.pairing.PakeDetails
import com.relaypony.session.pairing.QrPayloadV2
import com.relaypony.session.wan.RelayUrls

/**
 * Everything behind pairing on the desktop (PROTOCOL_v3 sections 3, 4 and 7), the twin of the
 * Android app's PairingController: the live QR, the code comparison, and word codes. The network
 * side is the shared [PairingService] and [WordCodePairing]. Event-thread only, like that service.
 *
 * The desktop usually shows its QR and a phone scans it. To scan the other way, a desktop can read
 * a pairing code from the clipboard (copied text or a screenshot of the QR) or from an image file.
 */
class DesktopPairing(
    private val service: PairingService,
    private val myHandle: String,
    private val main: MainThread,
    /** Keep the LAN listener and inbox polling up (true) or put them back (false). */
    private val setListening: (Boolean) -> Unit,
    /** A device was just pinned. */
    private val onPinned: (handle: String, name: String) -> Unit,
    /** A paired device unpaired this one; its pin and route are already gone. */
    private val onUnpinned: (handle: String) -> Unit,
    /** A fresh word-code attempt carrying this device's current details. */
    private val newWordCode: () -> WordCodePairing,
    /** Whether this device's relay has mailboxes and nameplates (relay 2.0), null if unknown. */
    private val relayHasMailboxes: () -> Boolean?,
    /** Pin a device that a word code paired, with its relay route. */
    private val pinWordCodePeer: (PakeDetails) -> Unit,
) {
    sealed class Prompt {
        /** Someone scanned this device's QR: compare and Confirm or Cancel. */
        data class Confirm(val pending: PairingFlow.Shower.Pending) : Prompt()
        /** This device read the other's QR and waits for it to confirm. */
        class Waiting(val name: String, val code: String, val nonceB: ByteArray) : Prompt()
        data class Paired(val name: String, val oneWay: Boolean) : Prompt()
        data class Declined(val name: String) : Prompt()
        data class NoAnswer(val name: String) : Prompt()
        data class Problem(val text: String) : Prompt()
        /** The other device unpaired this one (section 4.7). */
        data class UnpairedBy(val name: String) : Prompt()
    }

    var prompt by mutableStateOf<Prompt?>(null); private set

    /** The QR currently on screen. Its nonce is good for 5 minutes and one use. */
    var qr by mutableStateOf<QrPayloadV2?>(null); private set

    fun qrRemainingMs(): Long = service.qrRemainingMs()

    /** A fresh QR. Retires the one on screen. */
    fun refreshQr() { qr = service.showQr() }

    /** A fresh QR if there is none or it has expired or been used, unless a comparison is up. */
    fun ensureQr() {
        if (prompt is Prompt.Confirm) return
        if (qr == null || service.qrRemainingMs() == 0L) refreshQr()
    }

    /** The device name changed: the QR on screen carries the old one. */
    fun nameChanged() { if (qr != null && prompt !is Prompt.Confirm) refreshQr() }

    // ---- Listening while pairing ----

    private var surfaces = 0
    private var listening = false
    private val stopListening = Runnable {
        if (!wantsListening()) {
            listening = false
            setListening(false)
        }
    }

    /** A screen showing the QR appeared (true) or went away (false). */
    fun surfaceVisible(visible: Boolean) {
        surfaces = (surfaces + if (visible) 1 else -1).coerceAtLeast(0)
        updateListening()
    }

    private fun wantsListening(): Boolean = surfaces > 0 || prompt is Prompt.Confirm || prompt is Prompt.Waiting

    /** Listening stays up while a QR is shown or a comparison is pending, and for a short while after. */
    private fun updateListening() {
        if (wantsListening()) {
            main.removeCallbacks(stopListening)
            if (!listening) {
                listening = true
                setListening(true)
            }
        } else if (listening) {
            main.removeCallbacks(stopListening)
            main.postDelayed(stopListening, LINGER_MS)
        }
    }

    private fun showPrompt(p: Prompt?) {
        prompt = p
        updateListening()
    }

    /** A pairing code read from the clipboard or an image: the other device's QR text. */
    fun onScanned(text: String) {
        if (prompt != null) return
        try {
            service.onScanned(text.trim())
        } catch (e: IllegalArgumentException) {
            showPrompt(
                Prompt.Problem(
                    if (text.contains(myHandle)) I18n.t("pair_own_code") else I18n.t("pair_not_a_code")
                )
            )
        }
    }

    fun onEvent(ev: PairingService.Event) {
        when (ev) {
            is PairingService.Event.Request -> showPrompt(Prompt.Confirm(ev.pending))
            is PairingService.Event.Waiting -> {
                showPrompt(Prompt.Waiting(ev.name, ev.code, ev.nonceB))
                val nonceB = ev.nonceB
                main.postDelayed(Runnable {
                    val p = prompt
                    if (p is Prompt.Waiting && p.nonceB.contentEquals(nonceB)) {
                        service.cancelWaiting(nonceB)
                        showPrompt(Prompt.NoAnswer(p.name))
                    }
                }, PairingFlow.VALIDITY_MS)
            }
            is PairingService.Event.Paired -> pinned(ev.handle, ev.name, oneWay = false)
            is PairingService.Event.PairedOneWay -> pinned(ev.handle, ev.name, oneWay = true)
            is PairingService.Event.Declined -> showPrompt(Prompt.Declined((prompt as? Prompt.Waiting)?.name ?: ""))
            is PairingService.Event.RouteUpdated -> Unit
            is PairingService.Event.Unpaired -> {
                onUnpinned(ev.handle)
                // Don't cover a code comparison that is in progress with something else.
                if (prompt == null) showPrompt(Prompt.UnpairedBy(ev.name))
            }
        }
    }

    /** The user answered a [Prompt.Confirm]. */
    fun answer(accept: Boolean) {
        val p = prompt as? Prompt.Confirm ?: return
        showPrompt(null)
        service.confirm(p.pending, accept)
        if (!accept) refreshQr()
    }

    /** The user gave up waiting for the other device. */
    fun cancelWaiting() {
        val p = prompt as? Prompt.Waiting ?: return
        service.cancelWaiting(p.nonceB)
        showPrompt(null)
    }

    fun dismissPrompt() {
        when (prompt) {
            is Prompt.Confirm -> answer(false)
            is Prompt.Waiting -> cancelWaiting()
            else -> showPrompt(null)
        }
    }

    private fun pinned(handle: String, name: String, oneWay: Boolean) {
        onPinned(handle, name)
        showPrompt(Prompt.Paired(name, oneWay))
        // The QR that was just used is spent; the next one shown is fresh.
        if (qr != null) refreshQr()
    }

    // ---- Word codes (section 7) ----

    sealed class WordCode {
        data object Working : WordCode()
        /** This device's code, and the relay the other device must use when it isn't the default. */
        data class Showing(val code: String, val relay: String) : WordCode()
        data class Failed(val text: String) : WordCode()
    }

    var word by mutableStateOf<WordCode?>(null); private set

    @Volatile private var wordCancelled = false
    /** Bumped per attempt, so a late result from an abandoned attempt is ignored. */
    @Volatile private var wordGen = 0

    /** Side A: get a code from this device's relay, show it, and wait for the other device. */
    fun showWordCode() {
        if (relayHasMailboxes() == false) {
            word = WordCode.Failed(I18n.t("word_relay_old"))
            return
        }
        val gen = ++wordGen
        wordCancelled = false
        word = WordCode.Working
        background {
            val wc = try { newWordCode() } catch (e: LinkageError) { null }
            val shown = try {
                wc?.claim()
            } catch (e: LinkageError) {
                null
            } catch (e: Exception) {
                main.post { if (gen == wordGen) word = WordCode.Failed(I18n.t("word_relay_unreachable")) }
                return@background
            }
            if (wc == null || shown == null) {
                main.post { if (gen == wordGen) word = WordCode.Failed(I18n.t("d_word_not_in_build")) }
                return@background
            }
            main.post { if (gen == wordGen) word = WordCode.Showing(shown.code, shown.relay) }
            val result = runAttempt { wc.runAsA(shown, cancelled = { wordCancelled || gen != wordGen }) }
            main.post { finishWordCode(gen, result) }
        }
    }

    /** Side B: pair with a code read from the other device. [relay] is blank for the default relay. */
    fun enterWordCode(code: String, relay: String) {
        val gen = ++wordGen
        wordCancelled = false
        word = WordCode.Working
        val normalizedRelay = RelayUrls.normalize(relay)
        background {
            val result = runAttempt {
                newWordCode().runAsB(code.trim(), normalizedRelay, cancelled = { wordCancelled || gen != wordGen })
            }
            main.post { finishWordCode(gen, result) }
        }
    }

    /** Stop the current attempt. Side A releases its nameplate on the way out. */
    fun cancelWordCode() {
        wordCancelled = true
        wordGen++
        word = null
    }

    /** Back to the start after a failure. */
    fun resetWordCode() { word = null }

    /** The pair dialog closed: abandon an attempt in flight and clear a failure. */
    fun dialogClosed() {
        if (word is WordCode.Working || word is WordCode.Showing) cancelWordCode()
        if (word is WordCode.Failed) word = null
    }

    private fun runAttempt(block: () -> WordCodePairing.Result): WordCodePairing.Result = try {
        block()
    } catch (e: LinkageError) {
        WordCodePairing.Result.Failed(NOT_IN_BUILD, WordCodePairing.FailKind.UNEXPECTED)
    } catch (e: Exception) {
        WordCodePairing.Result.Failed(e.message ?: "relay unreachable")
    }

    private fun finishWordCode(gen: Int, result: WordCodePairing.Result) {
        if (gen != wordGen) return
        when (result) {
            is WordCodePairing.Result.Paired -> {
                word = null
                pinWordCodePeer(result.peer)
                pinned(result.peer.handle, result.peer.name, oneWay = false)
            }
            WordCodePairing.Result.Mismatch -> word = WordCode.Failed(I18n.t("word_mismatch"))
            WordCodePairing.Result.Timeout -> word = WordCode.Failed(I18n.t("word_timeout"))
            WordCodePairing.Result.Cancelled -> word = null
            is WordCodePairing.Result.Failed -> word = WordCode.Failed(
                when {
                    result.reason == NOT_IN_BUILD -> I18n.t("d_word_not_in_build")
                    result.kind == WordCodePairing.FailKind.NOT_A_CODE -> I18n.t("word_not_a_code")
                    result.kind == WordCodePairing.FailKind.OWN_CODE -> I18n.t("word_own_code")
                    result.kind == WordCodePairing.FailKind.UNEXPECTED -> I18n.t("word_unexpected")
                    else -> I18n.t("word_relay_unreachable")
                },
            )
        }
    }

    private fun background(block: () -> Unit) {
        Thread(block, "relaypony-wordcode").apply { isDaemon = true; start() }
    }

    private companion object {
        /** A marker, never shown: the native library is missing from this build. */
        const val NOT_IN_BUILD = "relaypony:word-codes-unavailable"

        /** How long listening stays up after the QR and every prompt are gone. */
        const val LINGER_MS = 30_000L
    }
}
