package com.example.vpn.automation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The upkeep strings live in their own file; values-ru must cover them with the same placeholders. */
class AutomationStringsTest {
    private fun load(path: String): Map<String, String> {
        val file = listOf(File("src/main/res/$path"), File("app/src/main/res/$path")).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { (nodes.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } }
    }

    private val placeholder = Regex("%(\\d+\\$)?[sd]")

    @Test
    fun russianCoversEveryUpkeepString() {
        val en = load("values/strings_automation.xml")
        val ru = load("values-ru/strings_automation.xml")
        assertEquals(en.keys, ru.keys)
        en.forEach { (k, v) ->
            assertEquals(k, placeholder.findAll(v).map { it.value }.toList().sorted(), placeholder.findAll(ru.getValue(k)).map { it.value }.toList().sorted())
            assertTrue("$k untranslated", ru.getValue(k).any { it in '\u0400'..'\u04FF' } || ru.getValue(k) == v)
        }
        // Every task and skip reason has a label (the card maps them one to one).
        assertTrue(en.keys.containsAll(listOf("automation_task_resolver_scan", "automation_task_dns_ladder", "automation_task_clean_ip",
            "automation_task_revival", "automation_task_geo", "automation_skip_vpn", "automation_skip_fresh", "automation_skip_nothing",
            "automation_skip_metered", "automation_skip_tunnel")))
    }
}
