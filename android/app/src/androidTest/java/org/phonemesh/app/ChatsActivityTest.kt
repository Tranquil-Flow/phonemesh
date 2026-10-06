package org.phonemesh.app

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.widget.TextView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.RadioPeer

/** Fake radio and speech, fixture packs. */
@RunWith(AndroidJUnit4::class)
class ChatsActivityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: Activity? = null
    private val core get() = TestCore.core

    @After fun tearDown() {
        Views.finish(Views.resumed().takeIf { it !== activity })
        Views.finish(activity)
        TestCore.uninstall()
    }

    private fun start(): ChatsActivity = Views.start(ChatsActivity::class.java).also { activity = it }

    /** Plain state, safe inside a waitUntil condition (which already runs on the main thread). */
    private fun resumedNow(): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()

    @Test fun firstLaunchAsksForTheLanguageThenShowsTheChats() {
        TestCore.install(language = null)
        val first = Views.start(ChatsActivity::class.java)
        Views.waitUntil { resumedNow() is LanguageActivity }
        Views.tap(checkNotNull(Views.resumed()), "language:fr")
        // The instance after the language choice and recreate, never the original.
        Views.waitUntil { resumedNow().let { it is ChatsActivity && it !== first } }
        activity = Views.resumed()
        assertEquals("fr", core.settings.language)
        assertNotNull(Views.withText(activity!!, "Discussions"))
    }

    @Test fun setupCardsShowWhatIsMissingThenFreeTheMicrophone() {
        TestCore.install(voices = emptyList(), connected = false)
        val chats = start()
        val voiceCard = Views.tagged(chats, "card_voice")!!
        assertNotNull(Views.descendants(voiceCard).filterIsInstance<TextView>()
            .firstOrNull { Views.textOf(it) == "Voix française · 66 Mo · une seule fois" })
        assertNotNull(Views.tagged(chats, "voice_download"))
        assertNotNull(Views.tagged(chats, "voice_file"))
        assertNotNull(Views.withText(chats, "Connecter la radio"))
        assertNotNull(Views.tagged(chats, "radio_search"))
        assertEquals("Radio non connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
        TestCore.onMain { Fixtures.installReady(context) }
        TestCore.onMain { TestCore.radio.connected(true) }
        assertNull(Views.tagged(chats, "card_voice"))
        assertNull(Views.tagged(chats, "card_radio"))
        assertNotNull(Views.tagged(chats, "card_voice_done"))
        assertNotNull(Views.tagged(chats, "card_radio_done"))
        assertEquals("Radio connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
        assertTrue(core.canTalk())
    }

    @Test fun rowsShowTheGroupFirstWithPeopleHeardThenPrivateChats() {
        TestCore.install()
        TestCore.onMain {
            TestCore.radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi"), RadioPeer("!0000bbbb", "Ama")))
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] Bonjour", false)
            TestCore.radio.receive("!0000bbbb", "[pm1:fr] Salut", false)
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] En privé", true)
        }
        val group = core.groupChatId()
        val direct = core.history.chats().single { it.kind == ChatKind.DIRECT }.id
        TestCore.onMain { core.setDraft(direct, "Bien reçu"); core.send(direct) }
        val chats = start()
        val list = Views.all(chats).filter { (it.tag as? String)?.startsWith("chat:") == true }.map { it.tag }
        assertEquals(listOf("chat:$group", "chat:$direct"), list)
        assertEquals("3", Views.textOf(Views.tagged(chats, "people:$group")!!))
        assertEquals("Personnes entendues : 3", Views.tagged(chats, "people:$group")!!.contentDescription)
        assertNotNull(Views.withText(chats, "Groupe"))
        assertNotNull(Views.withText(chats, "Ama : Salut"))
        assertNotNull(Views.withText(chats, "Kofi"))
        assertEquals("En attente", Views.tagged(chats, "last_status:$direct")!!.contentDescription)
        assertEquals("2", Views.textOf(Views.tagged(chats, "unread:$group")!!))
        assertEquals("Non lus : 2", Views.tagged(chats, "unread:$group")!!.contentDescription)
    }

    @Test fun unreadClearsWhenTheChatIsOpened() {
        TestCore.install()
        TestCore.onMain {
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] Un", true)
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] Deux", true)
        }
        val direct = core.history.chats().single { it.kind == ChatKind.DIRECT }.id
        val chats = start()
        assertEquals("2", Views.textOf(Views.tagged(chats, "unread:$direct")!!))
        Views.clickRow(chats, "chat:$direct")
        Views.waitUntil { resumedNow() is ConversationActivity }
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }
        assertNull(Views.tagged(chats, "unread:$direct"))
    }

    @Test fun plusOpensAPrivateChatWithAHeardRadio() {
        TestCore.install()
        val chats = start()
        Views.tap(chats, "new_chat")
        assertEquals("Aucune radio entendue.", Views.textOf(Views.tagged(chats, "note")!!))
        TestCore.onMain { TestCore.radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi"), RadioPeer("!0bad4a2f", "!0bad4a2f"))) }
        Views.tap(chats, "new_chat")
        val dialog = checkNotNull(chats.peerDialogForTest)
        val names = TestCore.onMain { (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() } }
        assertEquals(listOf("Kofi", "Radio 4a2f"), names)
        TestCore.onMain { dialog.listView.performItemClick(null, 1, 1) }
        Views.waitUntil { resumedNow() is ConversationActivity }
        assertEquals("Radio 4a2f", Views.textOf(Views.tagged(checkNotNull(Views.resumed()), "title")!!))
    }

    @Test fun veryLongNamesAndMessagesStayOnOneLine() {
        TestCore.install()
        val longName = "Kofi Mensah Agbodjan, radio du grand marché"
        TestCore.onMain {
            TestCore.radio.setPeers(listOf(RadioPeer("!0000aaaa", longName)))
            TestCore.radio.receive("!0000aaaa", "[pm1:fr] " + "Très long message ".repeat(10).trim(), true)
        }
        val direct = core.history.chats().single { it.kind == ChatKind.DIRECT }.id
        val chats = start()
        val title = Views.withText(chats, longName)!!
        assertEquals(1, TestCore.onMain { title.maxLines })
        assertEquals(TextUtils.TruncateAt.END, TestCore.onMain { title.ellipsize })
        val row = Views.tagged(chats, "chat:$direct")!!
        assertTrue(row.height < Ui.dp(context, 140))
    }

    /** Review Focus 5. */
    @Test fun aPackOpenedFromAnotherAppAsksFirstAndKeepsTheDraft() {
        TestCore.install(voices = emptyList())
        val group = core.groupChatId()
        activity = start()
        TestCore.onMain { core.setDraft(group, "Je reviens") }
        Views.launch<ConversationActivity>(ConversationActivity.intent(context, group))
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.VOICE), "application/zip")
            .setClass(context, ChatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Views.waitUntil { resumedNow() is ChatsActivity }
        val chats = Views.resumed() as ChatsActivity
        Views.waitUntil { chats.importDialogForTest != null }
        assertEquals("Je reviens", core.draft(group))
        assertTrue(core.store.list().isEmpty())
        val dialog = checkNotNull(chats.importDialogForTest)
        TestCore.onMain { dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        inst.waitForIdleSync() // the dialog hands the button press to the handler first
        core.awaitWorkForTest()
        inst.waitForIdleSync()
        assertEquals(listOf("voice-fr-siwis"), core.store.list().map { it.id })
        assertEquals("Pack importé : Français", Views.textOf(Views.tagged(chats, "note")!!))
    }

    /** Controller ruling C13: a pack that arrives while the microphone is in use is refused before asking. */
    @Test fun aPackOpenedWhileRecordingIsRefusedWithoutAsking() {
        TestCore.install()
        val chats = start()
        assertTrue(TestCore.onMain { core.startRecording(core.groupChatId()) })
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.VOICE), "application/zip")
        TestCore.onMain { chats.onNewIntent(intent) }
        inst.waitForIdleSync()
        assertNull(chats.importDialogForTest)
        assertEquals("Attendez la fin de l'opération.", Views.textOf(Views.tagged(chats, "note")!!))
        TestCore.onMain { core.cancelRecording() }
    }

    @Test fun theEmptyStateShowsUntilAMessageArrives() {
        TestCore.install()
        val chats = start()
        assertNotNull(Views.tagged(chats, "empty"))
        assertNotNull(Views.withText(chats, "Pas encore de messages"))
        TestCore.onMain { TestCore.radio.receive("!0000aaaa", "[pm1:fr] Enfin", false) }
        assertNull(Views.tagged(chats, "empty"))
    }
}
