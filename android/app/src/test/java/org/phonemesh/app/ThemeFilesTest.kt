package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every theme file defines every colour attribute; the night files are dark with light system-bar icons. */
class ThemeFilesTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$path"))
    private fun elements(path: String, tag: String) = xml(path).getElementsByTagName(tag).let { n -> (0 until n.length).map { n.item(it) as Element } }
    private val colours = elements("values/attrs.xml", "attr").map { it.getAttribute("name") }
    private fun items(dir: String) = elements("$dir/themes.xml", "item").associate { it.getAttribute("name") to it.textContent.trim() }
    private val files = listOf("values", "values-v27", "values-night", "values-night-v27")

    @Test fun everyThemeFileDefinesEveryColour() {
        assertTrue("pmOnAccent" in colours)
        files.forEach { dir -> colours.forEach { assertTrue("$dir lacks $it", it in items(dir).keys) } }
    }

    @Test fun nightFilesAreDark() {
        listOf("values-night", "values-night-v27").forEach { dir ->
            val night = items(dir)
            assertEquals("#FF121212", night["pmBackground"])
            assertEquals("false", night["android:windowLightStatusBar"])
            assertEquals("@android:style/Theme.Material.NoActionBar", elements("$dir/themes.xml", "style").single().getAttribute("parent"))
        }
        assertEquals("false", items("values-night-v27")["android:windowLightNavigationBar"])
    }

    @Test fun textOnTheAccentIsDarkInBothThemes() {
        files.forEach { assertEquals(it, "#FF111111", items(it)["pmOnAccent"]) }
    }
}