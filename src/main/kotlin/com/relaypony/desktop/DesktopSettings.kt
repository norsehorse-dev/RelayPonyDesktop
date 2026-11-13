package com.relaypony.desktop

import com.relaypony.session.pairing.InboxIds
import java.io.File
import java.util.Properties

/**
 * Small persisted settings shared by the GUI and the CLI, in `settings.properties` beside the
 * identity: this device's relay and inbox, whether unpaired devices may send here, and the per-peer
 * flags protocol v3 keeps (who sends sealed signaling, who tags its HELLO, who already knows this
 * device's inbox). The Android app keeps the same values in SharedPreferences.
 */
class DesktopSettings(private val file: File = File(Config.configDir, "settings.properties")) {

    private val props = Properties()

    init {
        if (file.exists()) runCatching { file.inputStream().use { props.load(it) } }
    }

    @Synchronized
    private fun get(key: String): String? = props.getProperty(key)

    @Synchronized
    private fun put(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, "RelayPony Desktop settings") }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    /** The relay URL the user typed, or empty for the default relay. */
    var relayServer: String
        get() = get(KEY_RELAY).orEmpty()
        set(value) = put(KEY_RELAY, value.trim())

    /** This device's private relay inbox, created on first use (PROTOCOL_v3 section 5). */
    val inboxId: String
        get() = get(KEY_INBOX)?.takeIf { InboxIds.isValid(it) } ?: InboxIds.generate().also { put(KEY_INBOX, it) }

    fun setInboxId(id: String) { if (InboxIds.isValid(id)) put(KEY_INBOX, id) }

    fun rotateInbox() { put(KEY_INBOX, InboxIds.generate()); clearSet(KEY_INBOX_USERS) }

    /** The app language the user picked, or null to follow the system. */
    var lang: String?
        get() = get(KEY_LANG)
        set(value) = put(KEY_LANG, value)

    /** Whether the first-run intro has been seen. */
    var onboarded: Boolean
        get() = get(KEY_ONBOARDED) == "true"
        set(value) = put(KEY_ONBOARDED, value.toString())

    var acceptUnpaired: Boolean
        get() = get(KEY_ACCEPT_UNPAIRED) == "true"
        set(value) = put(KEY_ACCEPT_UNPAIRED, value.toString())

    fun isSealedPeer(handle: String) = handle in set(KEY_SEALED)
    fun markSealedPeer(handle: String) = add(KEY_SEALED, handle)

    fun isTaggedPeer(handle: String) = handle in set(KEY_TAGGED)
    fun markTaggedPeer(handle: String) = add(KEY_TAGGED, handle)

    fun peerUsesMyInbox(handle: String) = handle in set(KEY_INBOX_USERS)
    fun markPeerUsesMyInbox(handle: String) = add(KEY_INBOX_USERS, handle)
    fun forgetInboxUser(handle: String) = remove(KEY_INBOX_USERS, handle)
    fun clearInboxUsers() = clearSet(KEY_INBOX_USERS)

    @Synchronized
    private fun set(key: String): Set<String> =
        get(key)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    @Synchronized
    private fun add(key: String, handle: String) {
        val s = set(key)
        if (handle !in s) put(key, (s + handle).joinToString(","))
    }

    @Synchronized
    private fun remove(key: String, handle: String) {
        val s = set(key)
        if (handle in s) put(key, (s - handle).joinToString(","))
    }

    private fun clearSet(key: String) = put(key, null)

    private companion object {
        const val KEY_RELAY = "relay_server"
        const val KEY_LANG = "lang"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_INBOX = "inbox_id"
        const val KEY_ACCEPT_UNPAIRED = "accept_unpaired"
        const val KEY_SEALED = "sealed_peers"
        const val KEY_TAGGED = "tagged_peers"
        const val KEY_INBOX_USERS = "inbox_users"
    }
}
