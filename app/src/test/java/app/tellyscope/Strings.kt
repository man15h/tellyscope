// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.UiText
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The English text of `res/values/strings.xml` for plain JVM tests, so they can assert on what a
 * screen shows without Robolectric. [StringsTest] checks it reads every string the way Android does.
 */
object Strings {
    private val doc by lazy {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml")).documentElement
    }

    val strings: Map<String, String> by lazy {
        doc.elements("string").associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    /** English plurals: `one` and `other`. */
    val plurals: Map<String, Map<String, String>> by lazy {
        doc.elements("plurals").associate { p ->
            p.getAttribute("name") to p.elements("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
        }
    }

    private val stringNames by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }
    private val pluralNames by lazy { R.plurals::class.java.fields.associate { it.getInt(null) to it.name } }

    fun string(id: Int, vararg args: Any): String {
        val raw = strings.getValue(stringNames.getValue(id))
        return if (args.isEmpty()) raw else raw.format(Locale.US, *args)
    }

    fun plural(id: Int, count: Int, vararg args: Any): String {
        val forms = plurals.getValue(pluralNames.getValue(id))
        return forms.getValue(if (count == 1 && "one" in forms) "one" else "other").format(Locale.US, *args)
    }

    /** Android's reading of a resource's text: collapsed whitespace outside "quotes", backslash escapes. */
    fun unescape(s: String): String {
        val out = StringBuilder()
        var quoted = false
        var space = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    if (space) out.append(' ').also { space = false }
                    i++
                    when (val e = s[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> out.append(s.substring(i + 1, i + 5).toInt(16).toChar()).also { i += 4 }
                        else -> out.append(e)
                    }
                }
                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> space = out.isNotEmpty()
                else -> {
                    if (space) out.append(' ').also { space = false }
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}

/** What [this] reads as in English. */
fun UiText.text(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> Strings.string(id, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
    is UiText.Plural -> Strings.plural(id, count, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
    is UiText.Join -> parts.joinToString(separator) { it.text() }
    is UiText.Capitalized -> text.text().replaceFirstChar(Char::uppercase)
}
