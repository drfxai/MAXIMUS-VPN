package com.example.ui.language

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** values-fa must cover every translatable string with the same placeholders (build 177, B12: Persian is selectable). */
class PersianTranslationTest {

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
    private val fa = load("values-fa/strings.xml")

    @Test
    fun everyTranslatableStringHasPersian() {
        val missing = en.translatable - fa.strings.keys
        assertTrue("Missing Persian strings: ${missing.take(20)}", missing.isEmpty())
        val extra = fa.strings.keys - en.translatable
        assertTrue("Persian strings with no English source or marked untranslatable: $extra", extra.isEmpty())
        assertTrue("Expected the UI to be extracted (got ${en.translatable.size})", en.translatable.size > 1000)
    }

    @Test
    fun placeholdersMatch() {
        val wrong = en.translatable.filter { it in fa.strings }.filter { placeholders(en.strings.getValue(it)) != placeholders(fa.strings.getValue(it)) }
        assertTrue("Placeholder mismatch: $wrong", wrong.isEmpty())
    }

    @Test
    fun persianTextIsNotEmpty() {
        val empty = fa.strings.filterValues { it.isBlank() }.keys
        assertTrue("Empty: $empty", empty.isEmpty())
    }
}
