package com.relaypony.desktop

import java.io.File

/** Where the desktop CLI keeps its identity/trust, and where received files land. Follows XDG on
 *  Linux (and works fine on macOS/Windows). Override the device name with RELAYPONY_NAME. */
object Config {

    val configDir: File = run {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".config")
        File(base, "relaypony").apply { mkdirs() }
    }

    val identityFile: File = File(configDir, "identity")
    val trustFile: File = File(configDir, "trust.json")
    val nameFile: File = File(configDir, "name")

    /** The auto-detected default name (env override, else hostname, else a friendly fallback). */
    val deviceName: String =
        System.getenv("RELAYPONY_NAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.name") ?: "Desktop"}'s computer"

    /** The name this device actually goes by: a user-saved override if present, else [deviceName].
     *  RELAYPONY_NAME still wins so a scripted/server deploy can pin a name regardless of the file. */
    fun loadName(): String {
        System.getenv("RELAYPONY_NAME")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { nameFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }
            .getOrNull() ?: deviceName
    }

    /** Persist a user-chosen device name (used by the GUI Settings screen). Best-effort. */
    fun saveName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        runCatching {
            configDir.mkdirs()
            nameFile.writeText(clean)
        }
    }

    /** Default inbox: ~/Downloads/RelayPony if Downloads exists, else ./received. */
    fun defaultInbox(): File {
        val downloads = File(System.getProperty("user.home"), "Downloads")
        val dir = if (downloads.isDirectory) File(downloads, "RelayPony") else File("received")
        return dir.apply { mkdirs() }
    }
}
