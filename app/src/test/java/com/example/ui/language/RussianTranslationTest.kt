package com.example.ui.language

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** values-ru must cover every translatable string with the same placeholders, and valid Russian plurals. */
class RussianTranslationTest {

    private fun res(path: String): File =
        listOf(File("src/main/res/$path"), File("app/src/main/res/$path")).first { it.exists() }

    private data class Res(val strings: Map<String, String>, val translatable: Set<String>, val plurals: Map<String, Map<String, String>>)

    private fun load(path: String): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res(path))
        val strings = linkedMapOf<String, String>()
        val translatable = linkedSetOf<String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val name = e.getAttribute("name")
            strings[name] = e.textContent
            if (e.getAttribute("translatable") != "false") translatable += name
        }
        val plurals = linkedMapOf<String, Map<String, String>>()
        val pNodes = doc.getElementsByTagName("plurals")
        for (i in 0 until pNodes.length) {
            val e = pNodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length).associate {
                val item = items.item(it) as Element
                item.getAttribute("quantity") to item.textContent
            }
        }
        return Res(strings, translatable, plurals)
    }

    private val placeholder = Regex("%(\\d+\\$)?[sd]")
    private fun placeholders(s: String): List<String> = placeholder.findAll(s).map { it.value }.sorted().toList()

    private val en = load("values/strings.xml")
    private val ru = load("values-ru/strings.xml")

    @Test
    fun everyTranslatableStringHasRussian() {
        val missing = en.translatable - ru.strings.keys
        assertTrue("Missing Russian strings: ${missing.take(20)}", missing.isEmpty())
        val extra = ru.strings.keys - en.translatable
        assertTrue("Russian strings with no English source or marked untranslatable: $extra", extra.isEmpty())
        assertTrue("Expected the UI to be extracted (got ${en.translatable.size})", en.translatable.size > 1000)
    }

    @Test
    fun placeholdersMatch() {
        val wrong = en.translatable.filter { it in ru.strings }.filter { placeholders(en.strings.getValue(it)) != placeholders(ru.strings.getValue(it)) }
        assertTrue("Placeholder mismatch: $wrong", wrong.isEmpty())
    }

    @Test
    fun russianTextIsNotEmptyOrUntranslatedEnglishSentences() {
        val empty = ru.strings.filterValues { it.isBlank() }.keys
        assertTrue("Empty: $empty", empty.isEmpty())
        // A sentence of three or more English words with no Cyrillic was most likely left untranslated.
        val englishOnly = ru.strings.filter { (_, v) ->
            v.none { it in '\u0400'..'\u04FF' } && Regex("\\b[a-z]{3,}\\b \\b[a-z]{3,}\\b \\b[a-z]{3,}\\b").containsMatchIn(v)
        }.keys
        assertTrue("Looks untranslated: ${englishOnly.take(20)}", englishOnly.isEmpty())
    }

    @Test
    fun russianPluralsUseAllCategories() {
        assertEquals(en.plurals.keys, ru.plurals.keys)
        ru.plurals.forEach { (name, items) ->
            assertEquals("$name quantities", setOf("one", "few", "many", "other"), items.keys)
            val expected = placeholders(en.plurals.getValue(name).getValue("other"))
            items.forEach { (q, v) -> assertEquals("$name/$q placeholders", expected, placeholders(v)) }
        }
    }

    @Test
    fun apostrophesAndQuotesAreEscaped() {
        listOf("values/strings.xml", "values-ru/strings.xml").forEach { path ->
            val raw = res(path).readText()
            Regex("<(string|item)[^>]*>(.*?)</(string|item)>").findAll(raw).forEach { m ->
                val body = m.groupValues[2]
                assertTrue("$path unescaped apostrophe: $body", !Regex("(?<!\\\\)'").containsMatchIn(body))
                assertTrue("$path unescaped quote: $body", !Regex("(?<!\\\\)\"").containsMatchIn(body))
            }
        }
    }
}
