package com.relaypony.desktop

import java.io.File

/** Where the desktop CLI keeps its identity/trust, and where received files land. Uses %APPDATA% on
 *  Windows and XDG (~/.config) on Linux/macOS. Override the device name with RELAYPONY_NAME. */
object Config {

    val configDir: File = run {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val base = if (os.contains("win")) {
            // Windows convention: %APPDATA%\relaypony (roaming profile), e.g. C:\Users\me\AppData\Roaming.
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), "AppData\\Roaming")
        } else {
            // XDG on Linux; macOS has no XDG by default and falls through to ~/.config, which is fine there.
            System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".config")
        }
        File(base, "relaypony").apply { mkdirs() }
    }

    val identityFile: File = File(configDir, "identity")
    val trustFile: File = File(configDir, "trust.json")
    val nameFile: File = File(configDir, "name")

    /**
     * The port we prefer to listen on. It used to be whatever the OS handed out, which meant the
     * address changed every run: you could not write a firewall rule for it, and you could not tell
     * anyone where to reach you if mDNS was not getting through. A stable default fixes both. If it
     * is already taken we still fall back to an OS-assigned port (see [LocalNet.listen]).
     *
     * Override with RELAYPONY_PORT; set RELAYPONY_PORT=0 for the old ephemeral behaviour.
     */
    const val DEFAULT_PORT = com.relaypony.transport.Beacon.DEFAULT_TRANSFER_PORT

    val listenPort: Int =
        System.getenv("RELAYPONY_PORT")?.trim()?.toIntOrNull()?.takeIf { it in 0..65535 } ?: DEFAULT_PORT

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
