package com.relaypony.desktop

import com.relaypony.session.pairing.PeerRoute
import com.relaypony.session.pairing.PeerRouteStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Where each paired device can be reached on the relay (PROTOCOL_v3 section 5), as JSON. */
class FilePeerRouteStore(private val file: File = File(Config.configDir, "routes.json")) : PeerRouteStore {

    @Serializable
    private data class Entry(val handle: String, val inboxId: String, val relay: String)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())
    private val byHandle = LinkedHashMap<String, PeerRoute>()

    init {
        if (file.exists()) runCatching {
            json.decodeFromString(serializer, file.readText()).forEach { byHandle[it.handle] = PeerRoute(it.inboxId, it.relay) }
        }
    }

    @Synchronized override fun get(handle: String): PeerRoute? = byHandle[handle]

    @Synchronized override fun put(handle: String, route: PeerRoute) { byHandle[handle] = route; persist() }

    @Synchronized override fun remove(handle: String) { if (byHandle.remove(handle) != null) persist() }

    @Synchronized fun all(): Map<String, PeerRoute> = LinkedHashMap(byHandle)

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, byHandle.map { (h, r) -> Entry(h, r.inboxId, r.relay) }))
        }
    }
}
