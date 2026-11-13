package com.relaypony.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.w3c.dom.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's text, in seven languages. Two layers, looked up in order:
 *
 *  1. `strings/desktop/values-xx/strings.xml`: desktop wording and desktop-only text.
 *  2. `strings/android/values-xx/strings.xml`: the Android app's own translated strings, copied in
 *     at build time, so text the two apps share is translated once and stays in step.
 *
 * Each layer falls back to its English file, then the key itself. Formats are Android's
 * (`%1$s`, `%1$d`, `%%`) and run through String.format in the current language. [lang] is Compose
 * state, so changing it re-renders every screen in the new language at once.
 */
object I18n {

    /** The languages the app ships, with each one's own name for itself. */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Español",
        "de" to "Deutsch",
        "fr" to "Français",
        "pt-BR" to "Português (Brasil)",
        "ja" to "日本語",
        "hi" to "हिन्दी",
    )

    var lang by mutableStateOf("en")
        private set

    fun setLanguage(code: String) {
        if (languages.any { it.first == code }) lang = code
    }

    /** The saved choice, else the system language when it is one of ours, else English. */
    fun initialLanguage(saved: String?): String {
        if (saved != null && languages.any { it.first == saved }) return saved
        val sys = Locale.getDefault()
        val tag = if (sys.language == "pt") "pt-BR" else sys.language
        return if (languages.any { it.first == tag }) tag else "en"
    }

    fun init(saved: String?) { lang = initialLanguage(saved) }

    /** A string for non-UI code (status lines, notices). It doesn't follow later language changes. */
    fun t(key: String, vararg args: Any?): String = format(lookup(key, lang), args)

    /** A plural, chosen by [count] under the current language's rules. */
    fun p(key: String, count: Int, vararg args: Any?): String {
        val forms = plural(key, lang)
        val form = forms[quantity(count, lang)] ?: forms["other"] ?: return key
        return format(form, args)
    }

    private fun format(template: String, args: Array<out Any?>): String =
        if (args.isEmpty()) template.replace("%%", "%")
        else runCatching { String.format(locale(lang), template, *args) }.getOrDefault(template)

    private fun locale(code: String): Locale = Locale.forLanguageTag(code)

    /** CLDR's cardinal rules for our languages, reduced to the forms Android files use. */
    private fun quantity(n: Int, code: String): String = when (code) {
        "ja" -> "other"
        "fr", "pt-BR", "hi" -> if (n == 0 || n == 1) "one" else "other"
        else -> if (n == 1) "one" else "other"
    }

    // ---- loading ----

    private class Table(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val cache = ConcurrentHashMap<String, Table>()

    private fun folder(code: String): String = when (code) {
        "en" -> "values"
        "pt-BR" -> "values-pt-rBR"
        else -> "values-$code"
    }

    private fun table(layer: String, code: String): Table = cache.getOrPut("$layer/$code") {
        load("/strings/$layer/${folder(code)}/strings.xml")
    }

    private fun lookup(key: String, code: String): String =
        table("desktop", code).strings[key]
            ?: table("android", code).strings[key]
            ?: table("desktop", "en").strings[key]
            ?: table("android", "en").strings[key]
            ?: key

    private fun plural(key: String, code: String): Map<String, String> =
        table("desktop", code).plurals[key]
            ?: table("android", code).plurals[key]
            ?: table("desktop", "en").plurals[key]
            ?: table("android", "en").plurals[key]
            ?: emptyMap()

    private fun load(path: String): Table {
        val stream = I18n::class.java.getResourceAsStream(path) ?: return Table(emptyMap(), emptyMap())
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val doc = stream.use { factory.newDocumentBuilder().parse(it) }
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
                when (e.tagName) {
                    "string" -> strings[name] = unescape(e.textContent)
                    "plurals" -> {
                        val forms = HashMap<String, String>()
                        val items = e.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            val item = items.item(j) as Element
                            forms[item.getAttribute("quantity")] = unescape(item.textContent)
                        }
                        plurals[name] = forms
                    }
                }
            }
        }
        return Table(strings, plurals)
    }

    /** Android resource string rules: outer quotes keep whitespace; otherwise runs collapse. */
    internal fun unescape(raw: String): String {
        var s = raw
        val quoted = s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")
        if (quoted) s = s.substring(1, s.length - 1) else s = s.trim().replace(Regex("\\s+"), " ")
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> { out.append('\n'); i += 2; continue }
                    't' -> { out.append('\t'); i += 2; continue }
                    'u' -> if (i + 5 < s.length) {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { out.append(code.toChar()); i += 6; continue }
                    }
                    else -> { out.append(n); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}

/** A string in the current language; reading [I18n.lang] makes callers re-render when it changes. */
@Composable
fun tr(key: String, vararg args: Any?): String {
    I18n.lang
    return I18n.t(key, *args)
}

@Composable
fun trp(key: String, count: Int, vararg args: Any?): String {
    I18n.lang
    return I18n.p(key, count, *args)
}
