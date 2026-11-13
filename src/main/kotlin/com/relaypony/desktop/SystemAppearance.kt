package com.relaypony.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/** The user's appearance choice in Settings. Saved by name in [Config.themeFile]. */
enum class ThemeMode(val labelKey: String) {
    SYSTEM("set_theme_system"),
    LIGHT("set_theme_light"),
    DARK("set_theme_dark"),
}

/**
 * Reads the desktop's light/dark preference on Linux, where Compose's isSystemInDarkTheme()
 * always reports light. Tried in order, first answer wins:
 *
 *  1. the XDG desktop portal (org.freedesktop.appearance color-scheme), which GNOME, KDE Plasma
 *     and most current desktops publish;
 *  2. GNOME's own color-scheme key, for setups without a portal;
 *  3. a GTK theme name containing "dark" (GTK_THEME, then the gtk-theme key);
 *  4. KDE's ColorScheme in kdeglobals.
 *
 * Every probe is best-effort with a short timeout; anything missing just falls through to light.
 */
object SystemAppearance {

    val isLinux: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    /** Probed once at launch so the first frame is already in the right theme. */
    val cachedLinuxDark: Boolean by lazy { if (isLinux) linuxPrefersDark() else false }

    fun linuxPrefersDark(): Boolean {
        portalColorScheme()?.let { return it }
        gsettings("color-scheme")?.let { if (it.contains("prefer-dark")) return true; if (it.contains("prefer-light")) return false }
        System.getenv("GTK_THEME")?.takeIf { it.isNotBlank() }?.let { return it.lowercase().contains("dark") }
        gsettings("gtk-theme")?.let { if (it.lowercase().contains("dark")) return true }
        kdeColorScheme()?.let { return it }
        return false
    }

    /** 1 = prefer dark, 2 = prefer light, 0 = no preference (keep looking). */
    private fun portalColorScheme(): Boolean? {
        val base = listOf(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop",
        )
        val out = run(base + listOf("--method", "org.freedesktop.portal.Settings.ReadOne", "org.freedesktop.appearance", "color-scheme"))
            ?: run(base + listOf("--method", "org.freedesktop.portal.Settings.Read", "org.freedesktop.appearance", "color-scheme"))
            ?: return null
        return when (Regex("""uint32\s+(\d)""").find(out)?.groupValues?.get(1)) {
            "1" -> true
            "2" -> false
            else -> null
        }
    }

    private fun gsettings(key: String): String? =
        run(listOf("gsettings", "get", "org.gnome.desktop.interface", key))

    private fun kdeColorScheme(): Boolean? {
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".config").path
        val file = File(configHome, "kdeglobals")
        if (!file.isFile) return null
        val line = runCatching { file.readLines() }.getOrNull()
            ?.firstOrNull { it.trim().startsWith("ColorScheme=") } ?: return null
        return line.lowercase().contains("dark")
    }

    /** Runs a short command and returns stdout, or null if it is missing, fails or hangs. */
    private fun run(cmd: List<String>): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
        if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() != 0) return null
        p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
