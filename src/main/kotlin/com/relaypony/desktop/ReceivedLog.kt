package com.relaypony.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Who sent each received file and when, so the Received list can say so (received.json). */
class ReceivedLog(private val file: File) {

    @Serializable
    private data class Entry(val path: String, val from: String, val atMs: Long)

    data class Record(val from: String, val atMs: Long)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())
    private val byPath = LinkedHashMap<String, Record>()

    init {
        if (file.exists()) runCatching {
            json.decodeFromString(serializer, file.readText()).forEach { byPath[it.path] = Record(it.from, it.atMs) }
        }
    }

    @Synchronized fun all(): Map<String, Record> = LinkedHashMap(byPath)

    @Synchronized fun put(path: String, from: String, atMs: Long) { byPath[path] = Record(from, atMs); persist() }

    @Synchronized fun remove(path: String) { if (byPath.remove(path) != null) persist() }

    /** Forget files that are no longer on disk. */
    @Synchronized fun retain(paths: Set<String>) { if (byPath.keys.retainAll(paths)) persist() }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, byPath.map { (p, r) -> Entry(p, r.from, r.atMs) }))
        }
    }
}
