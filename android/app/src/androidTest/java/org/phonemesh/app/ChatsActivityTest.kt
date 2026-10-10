package org.phonemesh.app

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.text.TextUtils
import android.widget.ListView
import android.widget.ScrollView
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
import java.io.File

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

    /** Silent fixture-only view export; FLAG_SECURE remains enabled. */
    private fun saveSetupPicture(chats: ChatsActivity, name: String) {
        if (InstrumentationRegistry.getArguments().getString("setupExport") != "true") return
        inst.waitForIdleSync()
        TestCore.onMain {
            val decor = chats.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val file = File(context.getExternalFilesDir(null), "task11/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun setupLeavesRoomForChatsAndCanScrollToLaterSteps() {
        TestCore.install(voices = emptyList(), connected = false)
        val chats = start()
        saveSetupPicture(chats, "voice-before-scroll")
        val list = Views.tagged(chats, "chats") as ListView
        assertTrue("setup cannot squeeze chats out: list height=${list.height}, screen height=${chats.window.decorView.height}",
            TestCore.onMain { list.height >= Ui.dp(chats, 76) && list.childCount > 0 })
        val scroll = Views.all(chats).filterIsInstance<ScrollView>().single()
        TestCore.onMain { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        Views.waitUntil {
            Views.descendants(chats.window.decorView).first { it.tag == "step_row:3" }.getGlobalVisibleRect(Rect())
        }
        saveSetupPicture(chats, "voice-after-scroll")
    }

    @Test fun theQuestionMarkOpensHelp() {
        TestCore.install()
        val chats = start()
        val help = checkNotNull(Views.tagged(chats, "help"))
        assertTrue(TestCore.onMain { help.width >= Ui.dp(chats, 48) && help.height >= Ui.dp(chats, 48) })
        assertEquals("Aide", help.contentDescription)
        Views.tap(chats, "help")
        Views.waitUntil { resumedNow() is HelpActivity }
        val page = checkNotNull(Views.resumed())
        Views.tap(page, "back")
        Views.waitUntil { resumedNow() === chats }
    }

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

    @Test fun setupIsANumberedGuideThatFoldsAwayWhenDone() {
        TestCore.install(voices = emptyList(), connected = false)
        val chats = start()
        val voiceCard = Views.tagged(chats, "card_voice")!!
        assertNotNull("the current step is numbered", Views.tagged(chats, "step_of"))
        assertEquals("ÉTAPE 1 SUR 3", Views.textOf(Views.tagged(chats, "step_of")!!))
        saveSetupPicture(chats, "step1")
        assertNotNull(Views.descendants(voiceCard).filterIsInstance<TextView>()
            .firstOrNull { Views.textOf(it) == "Voix française · 66 Mo · une seule fois" })
        assertNotNull(Views.tagged(chats, "voice_download"))
        assertNotNull(Views.tagged(chats, "voice_file"))
        assertNotNull("step 1 plays the download picture", Views.tagged(chats, "howto:download"))
        assertNotNull("and the friend picture as part 2", Views.tagged(chats, "part:2"))
        Views.tap(chats, "part:2")
        assertNotNull(Views.tagged(chats, "howto:friend"))
        assertNull(Views.tagged(chats, "card_radio"))
        assertNotNull(Views.tagged(chats, "step_row:2"))
        assertNotNull(Views.tagged(chats, "step_row:3"))
        assertEquals("Radio non connectée", Views.tagged(chats, "radio_status")!!.contentDescription)

        TestCore.onMain {
            Fixtures.installReady(context)
            // This disk-only helper does not send AppCore's normal pack notification.
            chats.onCoreChange(AppCore.Change.PACKS)
        }
        assertNotNull(Views.tagged(chats, "card_radio"))
        assertEquals("ÉTAPE 2 SUR 3", Views.textOf(Views.tagged(chats, "step_of")!!))
        assertNotNull(Views.tagged(chats, "step_done:1"))
        // The pinned voice-only archive is 6 MB; the missing engine plus voice was 66 MB.
        assertNotNull(Views.withText(chats, "Voix française · 6 Mo"))
        assertNotNull(Views.withText(chats, "Connecter la radio"))
        assertNotNull(Views.tagged(chats, "radio_search"))
        assertNotNull(Views.tagged(chats, "howto:power"))
        saveSetupPicture(chats, "step2")
        assertNull("pairing remains off", Views.tagged(chats, "part:4"))
        Views.tap(chats, "part:2")
        assertNotNull(Views.tagged(chats, "howto:close"))
        Views.tap(chats, "part:3")
        assertNotNull(Views.tagged(chats, "howto:search"))
        assertEquals("Touchez « Trouver la radio ».", Views.textOf(Views.tagged(chats, "howto_sentence")!!))

        TestCore.onMain { TestCore.radio.connected(true) }
        assertNotNull(Views.tagged(chats, "card_hello"))
        assertEquals("ÉTAPE 3 SUR 3", Views.textOf(Views.tagged(chats, "step_of")!!))
        assertNotNull(Views.tagged(chats, "step_done:1"))
        assertNotNull(Views.tagged(chats, "step_done:2"))
        assertEquals("Radio connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
        assertNotNull(Views.tagged(chats, "howto:hello"))
        saveSetupPicture(chats, "step3")
        Views.tap(chats, "hello_try")
        Views.waitUntil { resumedNow() is ConversationActivity }
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }

        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain { core.setDraft(group, "Bonjour"); assertTrue(core.send(group)) }
        assertNotNull("synchronous send observers see completion", Views.tagged(chats, "setup_done"))
        inst.waitForIdleSync()
        saveSetupPicture(chats, "complete")
        assertTrue(core.settings.setupFinished)
        TestCore.onMain { chats.onCoreChange(AppCore.Change.PEERS) }
        Views.waitUntil(4_000) { Views.descendants(chats.window.decorView).none { it.tag == "setup_done" && it.isShown } }
        listOf("card_voice", "card_radio", "card_hello", "step_of").forEach { assertNull(it, Views.tagged(chats, it)) }
        assertTrue(core.canTalk())
        Views.finish(chats)
        val reopened = start()
        assertNull("setup never returns on reopening", Views.tagged(reopened, "step_of"))
        assertNull(Views.tagged(reopened, "setup_done"))
    }

    @Test fun afterSetupALostRadioShowsOneCardWithoutNumbers() {
        TestCore.install()
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        assertNull(Views.tagged(chats, "card_radio"))
        TestCore.onMain { TestCore.radio.connected(false) }
        assertNotNull(Views.tagged(chats, "card_radio"))
        assertNotNull(Views.tagged(chats, "howto:lost"))
        inst.waitForIdleSync()
        saveSetupPicture(chats, "lost-radio")
        assertNull(Views.tagged(chats, "step_of"))
        assertNull(Views.tagged(chats, "step_row:1"))
        TestCore.onMain { TestCore.radio.connected(true) }
        assertNull(Views.tagged(chats, "card_radio"))
        assertNull(Views.tagged(chats, "setup_done"))
    }

    @Test fun aNewLanguageAfterSetupShowsOnlyTheVoiceCard() {
        TestCore.install(voices = listOf("fr"))
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true; core.settings.language = "en-US" }
        val chats = start()
        assertNotNull(Views.tagged(chats, "card_voice"))
        assertNull(Views.tagged(chats, "step_of"))
        assertNull(Views.tagged(chats, "card_hello"))
        assertNull(Views.tagged(chats, "step_row:3"))
        assertNotNull(Views.tagged(chats, "howto:download"))
        assertNull("problem voice card has one picture", Views.tagged(chats, "part:2"))
    }

    @Test fun aRenderKeepsTheSamePicture() {
        TestCore.install(connected = false)
        val chats = start()
        val before = Views.tagged(chats, "howto:power")!!
        TestCore.onMain { chats.onCoreChange(AppCore.Change.PEERS) }
        assertSame(before, Views.tagged(chats, "howto:power"))
        Views.tap(chats, "part:3")
        TestCore.onMain { chats.onCoreChange(AppCore.Change.PACKS) }
        assertSame("a render preserves the selected part", before, Views.tagged(chats, "howto:search"))
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

    private fun packIntent() = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.VOICE), "application/zip")
        .setClass(context, ChatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test fun aPackOpenedDuringFirstLaunchBringsBackTheLanguageScreenThenAsks() {
        TestCore.install(language = null, voices = emptyList())
        val first = Views.start(ChatsActivity::class.java)
        activity = first
        Views.waitUntil { resumedNow() is LanguageActivity }
        val firstLanguage = Views.resumed()
        context.startActivity(packIntent())
        Views.waitUntil { resumedNow().let { it is LanguageActivity && it !== firstLanguage } }
        // A finger takes longer than the stop of the screen underneath; the recreate for the new language needs that stop.
        Views.waitUntil { ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(first) == Stage.STOPPED }
        Views.tap(checkNotNull(Views.resumed()), "language:fr")
        Views.waitUntil { resumedNow().let { it is ChatsActivity && it !== first } }
        val chats = Views.resumed() as ChatsActivity
        activity = chats
        Views.waitUntil { chats.importDialogForTest != null }
        assertTrue(core.store.list().isEmpty())
    }

    @Test fun theConfirmQuestionSurvivesRotationAndIsNotAskedAgainAfterwards() {
        TestCore.install(voices = emptyList())
        val first = start()
        context.startActivity(packIntent())
        Views.waitUntil { first.importDialogForTest != null }
        TestCore.onMain { first.recreate() }
        Views.waitUntil { resumedNow().let { it is ChatsActivity && it !== first } }
        val second = Views.resumed() as ChatsActivity
        activity = second
        Views.waitUntil { second.importDialogForTest != null }
        assertTrue(core.store.list().isEmpty())
        val dialog = checkNotNull(second.importDialogForTest)
        TestCore.onMain { dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick() }
        inst.waitForIdleSync()
        TestCore.onMain { second.recreate() }
        Views.waitUntil { resumedNow().let { it is ChatsActivity && it !== second } }
        val third = Views.resumed() as ChatsActivity
        activity = third
        inst.waitForIdleSync()
        assertNull(third.importDialogForTest)
    }

    @Test fun aPackReopenedFromHistoryWhileRecordingIsIgnoredQuietly() {
        TestCore.install()
        val chats = start()
        assertTrue(TestCore.onMain { core.startRecording(core.groupChatId()) })
        val intent = packIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        TestCore.onMain { chats.onNewIntent(intent) }
        inst.waitForIdleSync()
        assertNull(chats.importDialogForTest)
        assertNull(Views.tagged(chats, "note"))
        TestCore.onMain { core.cancelRecording() }
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

    @Test fun aRefusedFileShowsItsPictureAndNoSpeakerBeforeAnyVoice() {
        TestCore.install(voices = emptyList())
        val chats = start()
        TestCore.onMain { core.note(UiText(R.string.pack_unsafe)) }
        val box = checkNotNull(Views.tagged(chats, "note_box"))
        assertNotNull(Views.tagged(chats, "howto:refused"))
        assertEquals("Fichier refusé pour votre sécurité.", Views.textOf(Views.tagged(chats, ScreenActivity.NOTE_TAG)!!))
        assertEquals("Ce fichier a été refusé.", TestCore.onMain {
            Views.descendants(box).filterIsInstance<TextView>().first { it.tag == "howto_sentence" }.text.toString()
        })
        assertTrue("nothing to read it with", TestCore.onMain {
            Views.descendants(box).none { it.tag == "howto_say" && it.isShown }
        })
        Views.tap(chats, "note_close")
        assertNull(Views.tagged(chats, "note_box"))
        assertTrue("dismissal releases the picture", TestCore.onMain { Views.descendants(box).none { it is HowToView } })
    }

    @Test fun withAVoiceTheNotePictureCanBeHeard() {
        TestCore.install()
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        TestCore.onMain { core.note(UiText(R.string.no_space)) }
        val box = checkNotNull(Views.tagged(chats, "note_box"))
        val say = TestCore.onMain { Views.descendants(box).first { it.tag == "howto_say" } }
        TestCore.onMain { say.performClick() }
        assertEquals(listOf("Supprimez quelque chose pour faire de la place."), TestCore.speech.spoken)
        assertTrue("fake speech only", TestCore.speech.clips.isEmpty())
    }

    @Test fun aNoteWithoutAPictureStaysPlain() {
        TestCore.install()
        val chats = start()
        TestCore.onMain { core.note(UiText(R.string.share_choose)) }
        assertNotNull(Views.tagged(chats, ScreenActivity.NOTE_TAG))
        assertNull(Views.tagged(chats, "note_close"))
        val box = checkNotNull(Views.tagged(chats, "note_box"))
        assertTrue(TestCore.onMain { Views.descendants(box).none { it is HowToView } })
        Views.waitUntil(6_500) { Views.descendants(chats.window.decorView).none { it.tag == "note_box" && it.isShown } }
    }

    @Test fun aPictureNoteLastsTwelveSecondsWithoutRestartingOnRender() {
        TestCore.install()
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        TestCore.onMain { core.note(UiText(R.string.pack_damaged)) }
        val picture = checkNotNull(Views.tagged(chats, "howto:damaged"))
        Thread.sleep(5_300)
        TestCore.onMain { chats.onCoreChange(AppCore.Change.PEERS) }
        assertSame("the first loop must not dismiss the note", picture, Views.tagged(chats, "howto:damaged"))
        Views.waitUntil(8_000) { Views.descendants(chats.window.decorView).none { it.tag == "note_box" && it.isShown } }
        assertNull(Views.tagged(chats, "howto:damaged"))
    }

    @Test fun replacingAndClosingNotesReleasesPicturesAndCancelsOldTimeouts() {
        TestCore.install(voices = emptyList())
        val chats = start()
        TestCore.onMain { core.note(UiText(R.string.share_choose)) }
        Thread.sleep(3_000)
        TestCore.onMain { core.note(UiText(R.string.voice_missing)) }
        val picture = Views.tagged(chats, "howto:download")!!
        val box = Views.tagged(chats, "note_box")!!
        TestCore.onMain { Views.descendants(box).first { it.tag == "howto_say" }.performClick() }
        assertEquals(listOf(checkNotNull(LanguageRegistry.byTag("fr")).howToClips.getValue("download")), TestCore.speech.clips)
        Thread.sleep(2_500)
        assertSame("the plain note's old deadline cannot hide its replacement", picture, Views.tagged(chats, "howto:download"))
        TestCore.onMain { core.note(UiText(R.string.pack_older)) }
        assertFalse(TestCore.onMain { picture.isAttachedToWindow })
        assertNotNull(Views.tagged(chats, "howto:older"))
        Views.tap(chats, "note_close")
        TestCore.onMain { core.note(UiText(R.string.share_choose)) }
        assertNull(Views.tagged(chats, "note_close"))
        assertTrue(TestCore.onMain { Views.descendants(box).none { it is HowToView } })
    }

    @Test fun aCoveredNoteKeepsItsOriginalDeadline() {
        TestCore.install()
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        TestCore.onMain { core.note(UiText(R.string.pack_damaged)) }
        val picture = checkNotNull(Views.tagged(chats, "howto:damaged"))
        val shownAt = android.os.SystemClock.elapsedRealtime()
        val top = Views.start(RadioActivity::class.java)
        try {
            Thread.sleep(1_000)
        } finally {
            Views.finish(top)
        }
        val returned = TestCore.onMain {
            "elapsed=${android.os.SystemClock.elapsedRealtime() - shownAt}, " +
                "resumed=${resumedNow() === chats}, decorShown=${chats.window.decorView.isShown}, " +
                "pictureAttached=${picture.isAttachedToWindow}, pictureShown=${picture.isShown}"
        }
        assertSame("covering must preserve the picture before its deadline ($returned)",
            picture, Views.tagged(chats, "howto:damaged"))
        val remaining = 12_500L - (android.os.SystemClock.elapsedRealtime() - shownAt)
        if (remaining > 0L) Thread.sleep(remaining)
        assertNull("returning must not cancel or restart the twelve-second deadline", Views.tagged(chats, "note_box"))
        assertFalse(TestCore.onMain { picture.isAttachedToWindow })
    }

    @Test fun problemStillsKeepTheWaxPrintAndWaitingCheck() {
        TestCore.install()
        fun bitmap(name: String) = TestCore.onMain {
            val view = HowToView(inst.targetContext, org.phonemesh.core.HowToScenes.get(name))
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(440, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            view.freezeAt(view.scene.still)
            android.graphics.Bitmap.createBitmap(440, 220, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        // The opt-in negative control uses the real pre-fix export, never a made-up image.
        val notheard = if (InstrumentationRegistry.getArguments().getString("howtoPatternNegative") == "true") {
            val file = java.io.File(inst.targetContext.getExternalFilesDir(null), "howto-pattern-before.png")
            checkNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
        } else bitmap("notheard")
        val wait = bitmap("wait")
        try {
            // The motif is anchored at (0,0); the already-qualified band is the same pattern,
            // 18 drawing units (three full tiles) below this interior patch of the dress.
            for (y in 174 until 178) for (x in 96 until 120) {
                val expected = notheard.getPixel(x, y + 36)
                val actual = notheard.getPixel(x, y)
                assertEquals("wax-print alpha at $x,$y", expected ushr 24, actual ushr 24)
                // Path-clipped dress and rect-clipped band round edge coverage by one channel level.
                // Keep all 96 pixels and every channel; the recorded unpatterned negative arm fails.
                for (shift in intArrayOf(16, 8, 0))
                    assertTrue("the dress keeps the anchored wax-print at $x,$y channel $shift",
                        kotlin.math.abs(((expected shr shift) and 255) - ((actual shr shift) and 255)) <= 1)
            }
            assertEquals("the finished button has a tick beside the tapping finger", HowToKit.C.INK, wait.getPixel(204, 158))
        } finally {
            notheard.recycle()
            wait.recycle()
        }
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
