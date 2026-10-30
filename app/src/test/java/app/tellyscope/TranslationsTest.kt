// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * Each `values-xx` translates every string and plural, and keeps each one's format arguments.
 * A new translation adds a subclass below with its qualifier.
 */
@RunWith(RobolectricTestRunner::class)
abstract class TranslationsTest(private val qualifier: String) {
    private val res = RuntimeEnvironment.getApplication().resources
    private val arg = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")

    private fun args(s: String) = arg.findAll(s).map { it.value }.sorted().toList()

    private fun names(path: String, tag: String, skipUntranslatable: Boolean = false): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filterNot { skipUntranslatable && it.getAttribute("translatable") == "false" }
            .map { it.getAttribute("name") }.toSet()
    }

    @Test fun everyStringIsTranslated() {
        for (tag in listOf("string", "plurals")) {
            assertEquals(
                tag,
                names("src/main/res/values/strings.xml", tag, skipUntranslatable = true),
                names("src/main/res/values-$qualifier/strings.xml", tag),
            )
        }
    }

    @Test fun everyStringKeepsItsArguments() {
        for (f in R.string::class.java.fields) {
            val english = Strings.strings[f.name] ?: continue // from a library
            assertEquals(f.name, args(english), args(res.getText(f.getInt(null)).toString()))
        }
    }

    @Test fun everyPluralKeepsItsArguments() {
        for (f in R.plurals::class.java.fields) {
            val english = Strings.plurals[f.name] ?: continue
            // Languages split counts differently (Hindi and French "one" also cover 0, Russian "one"
            // covers 21), so any form may carry the count where English "one" spells it out.
            val allowed = setOf(args(english["one"] ?: english.getValue("other")), args(english.getValue("other")))
            for (n in listOf(0, 1, 2, 3, 5, 11, 21, 1_000_000)) {
                val translated = args(res.getQuantityText(f.getInt(null), n).toString())
                assertTrue("${f.name} $n: $translated", translated in allowed)
            }
        }
    }
}

@Config(sdk = [35], qualifiers = "es") class SpanishStringsTest : TranslationsTest("es")
@Config(sdk = [35], qualifiers = "zh-rCN") class ChineseStringsTest : TranslationsTest("zh-rCN")
@Config(sdk = [35], qualifiers = "ja") class JapaneseStringsTest : TranslationsTest("ja")
@Config(sdk = [35], qualifiers = "ru") class RussianStringsTest : TranslationsTest("ru")
@Config(sdk = [35], qualifiers = "hi") class HindiStringsTest : TranslationsTest("hi")
