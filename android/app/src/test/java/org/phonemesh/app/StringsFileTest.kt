package org.phonemesh.app

import org.junit.Assert.*
import org.junit.Test
import org.phonemesh.core.CopyRules
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Both strings files: same keys, eight words at most, no technical words, one file per language. */
class StringsFileTest {
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }
    }

    private val english = strings("values")
    private val french = strings("values-fr")
    private fun shown(text: String) = text.replace(Regex("%\\d\\$[sd]"), "X").replace("%%", "%")

    @Test fun bothLanguagesHaveTheSameStrings() = assertEquals(english.keys, french.keys)

    @Test fun noStringIsLongerThanEightWords() {
        (english.map { "en:${it.key}" to it.value } + french.map { "fr:${it.key}" to it.value }).forEach { (key, value) ->
            assertTrue("$key has ${CopyRules.words(shown(value))} words: \"$value\"", CopyRules.words(shown(value)) <= 8)
        }
    }

    @Test fun noTechnicalWords() {
        (english + french.mapKeys { "fr:${it.key}" }).forEach { (key, value) ->
            assertTrue("$key: \"$value\"", CopyRules.bannedIn(value).isEmpty())
        }
    }

    @Test fun everyLanguageHasItsStringsFile() {
        LanguageRegistry.all.forEach { language ->
            val dir = if (language.locale.language == "en") "values" else "values-${language.locale.language}"
            assertTrue("no strings for ${language.tag}", File("src/main/res/$dir/strings.xml").isFile)
        }
    }

    @Test fun theSpecWordsAreThere() {
        assertEquals("Discussions", french["chats_title"])
        assertEquals("Trop long. Raccourcissez.", french["too_long"])
        assertEquals("Voix française", french["setup_voice_title"])
        assertEquals("Pas confirmé", french["status_unconfirmed"])
        assertEquals("parti", french["status_sent"])
        assertEquals("Pas trouvée ? Allumez la radio. Approchez le téléphone.", french["radio_help"])
    }

    @Test fun stepOneNamesItsTwoNumberedChoicesAndTheFriendGuide() {
        assertEquals("%1\$d · %2\$s", english["setup_option"])
        assertEquals(english["setup_option"], french["setup_option"])
        assertEquals("Download", english["setup_download"])
        assertEquals("Télécharger", french["setup_download"])
        assertEquals("From a friend", english["setup_file"])
        assertEquals("D'un ami", french["setup_file"])
        assertEquals("Choose the file", english["setup_choose_file"])
        assertEquals("Choisir le fichier", french["setup_choose_file"])
        assertEquals("Your friend taps Settings, then “Share the app”.", english["setup_guide_send"])
        assertEquals("Votre ami touche Réglages, puis « Partager l'application ».", french["setup_guide_send"])
        assertEquals("Accept the file here. Then tap it.", english["setup_guide_accept"])
        assertEquals("Acceptez le fichier ici. Puis touchez-le.", french["setup_guide_accept"])
        // The guide's sentence names the sender's real labels.
        assertTrue(english.getValue("setup_guide_send").contains(english.getValue("settings")))
        assertTrue(english.getValue("setup_guide_send").contains(english.getValue("share_app")))
        assertTrue(french.getValue("setup_guide_send").contains(french.getValue("settings")))
        assertTrue(french.getValue("setup_guide_send").contains(french.getValue("share_app")))
    }

    @Test fun theSenderIsPointedAtTheFastChoiceThenBluetooth() {
        assertEquals("Choose Quick Share. If missing, choose Bluetooth.", english["share_choose"])
        assertEquals("Choisissez Quick Share. Sinon, choisissez Bluetooth.", french["share_choose"])
    }
}
