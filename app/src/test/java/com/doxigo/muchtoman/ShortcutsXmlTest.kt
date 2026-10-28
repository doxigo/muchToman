package com.doxigo.muchtoman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The launcher shortcuts are two hand-kept copies of one file, because a static shortcut's
 * targetPackage has to be a literal and the dev build installs under another package. Nothing at
 * build time compares them: a shortcut added to one copy and not the other, or an extra renamed in
 * Kotlin and not in the XML, builds clean and opens the app on whatever tab she left it on.
 */
class ShortcutsXmlTest {

    private val full = File("src/full/res/xml/shortcuts.xml")
    private val dev = File("src/fullDev/res/xml/shortcuts.xml")

    @Test
    fun `the dev copy is the released one under the dev package and nothing else`() {
        val expected = full.readText().replace(
            "android:targetPackage=\"com.doxigo.muchtoman\"",
            "android:targetPackage=\"com.doxigo.muchtoman.dev\"",
        )
        assertEquals(expected, dev.readText())
    }

    @Test
    fun `every shortcut targets the released package and the one activity`() {
        val intents = intents(full)
        assertEquals(3, intents.size)
        for (intent in intents) {
            assertEquals("com.doxigo.muchtoman", intent.getAttribute("android:targetPackage"))
            assertEquals(MainActivity::class.java.name, intent.getAttribute("android:targetClass"))
            assertEquals("android.intent.action.MAIN", intent.getAttribute("android:action"))
        }
    }

    @Test
    fun `each shortcut carries an extra MainActivity reads`() {
        val extras = intents(full).map { intent ->
            val extra = intent.getElementsByTagName("extra").item(0) as org.w3c.dom.Element
            extra.getAttribute("android:name") to extra.getAttribute("android:value")
        }
        assertEquals(
            listOf(EXTRA_ADD_TXN to "true", EXTRA_OPEN_DECK to "true", EXTRA_OPEN_TAB to Tab.ASSETS.name),
            extras,
        )
    }

    @Test
    fun `short labels fit the launcher's ten characters`() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/full/res/values/strings.xml")).getElementsByTagName("string")
        val short = (0 until strings.length).map { strings.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("name").endsWith("_short") }
        assertEquals(3, short.size)
        for (label in short) assertTrue(label.textContent, label.textContent.length <= 10)
    }

    private fun intents(file: File): List<org.w3c.dom.Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("intent")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }
}
