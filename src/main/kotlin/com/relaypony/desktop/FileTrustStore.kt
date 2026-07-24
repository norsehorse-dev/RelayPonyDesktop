package com.relaypony.desktop

import com.relaypony.session.pairing.PinnedDevice
import com.relaypony.session.pairing.TrustStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop trust store: the pinned devices this computer may send to, keyed by handle, persisted as
 * JSON. Implements the same `TrustStore` the phones use, so `Pairing` / `Sas` and the send gate all
 * work unchanged. Security note (as on every platform): trust is keyed on the age HANDLE, never the
 * display name — a spoofed name over mDNS changes nothing.
 */
class FileTrustStore(private val file: File) : TrustStore {

    @Serializable
    private data class Entry(val handle: String, val name: String, val pinnedAtEpochMs: Long)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val entriesSerializer = ListSerializer(Entry.serializer())
    private val byHandle = LinkedHashMap<String, PinnedDevice>()

    init {
        if (file.exists()) runCatching {
            json.decodeFromString(entriesSerializer, file.readText()).forEach {
                byHandle[it.handle] = PinnedDevice(it.handle, it.name, it.pinnedAtEpochMs)
            }
        }
    }

    override fun pin(handle: String, name: String, nowMs: Long) {
        require(handle.isNotEmpty()) { "cannot pin an empty handle" }
        byHandle[handle] = PinnedDevice(handle, name, nowMs)
        persist()
    }

    override fun isPinned(handle: String): Boolean = byHandle.containsKey(handle)
    override fun get(handle: String): PinnedDevice? = byHandle[handle]
    override fun all(): List<PinnedDevice> = byHandle.values.toList()
    override fun remove(handle: String) { byHandle.remove(handle); persist() }

    private fun persist() {
        file.parentFile?.mkdirs()
        val list = byHandle.values.map { Entry(it.recipientHandle, it.name, it.pinnedAtEpochMs) }
        file.writeText(json.encodeToString(entriesSerializer, list))
    }
}
