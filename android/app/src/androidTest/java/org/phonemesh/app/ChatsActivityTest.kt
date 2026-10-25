package org.phonemesh.app

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.text.TextUtils
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
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
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

    private fun longPressChat(chats: ChatsActivity, chatId: Long): android.app.AlertDialog {
        // A row without its own listener hands a long press to the list, which calls its item long-click listener.
        TestCore.onMain { Views.descendants(chats.window.decorView).first { it.tag == "chat:$chatId" }.performLongClick() }
        inst.waitForIdleSync()
        return checkNotNull(chats.chatDialogForTest)
    }

    private fun tapInDialog(d: android.app.AlertDialog, tag: String) {
        TestCore.onMain { checkNotNull(Views.descendants(d.window!!.decorView).firstOrNull { it.tag == tag }) { tag }.performClick() }
        inst.waitForIdleSync()
    }

    private fun dialogTags(d: android.app.AlertDialog): List<String> =
        TestCore.onMain { Views.descendants(d.window!!.decorView).mapNotNull { it.tag as? String }.filter { it.startsWith("action_") } }

    @Test fun aLongPressPinsAPrivateChatAndDeletesAChatAfterAsking() {
        TestCore.install()
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val group = TestCore.onMain { core.groupChatId() }
        val kofi = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val ama = TestCore.onMain { core.startDirectChat(RadioPeer("!0000bbbb", "Ama")) }
        TestCore.onMain {
            core.history.addIncoming(kofi, "!0000aaaa", "Vieux", "fr", 1_000, false)
            core.history.addIncoming(ama, "!0000bbbb", "Neuf", "fr", 2_000, false)
        }
        val chats = start()
        assertEquals("the group has no pin: it is always first", listOf("action_delete_chat"), dialogTags(longPressChat(chats, group)))
        TestCore.onMain { checkNotNull(chats.chatDialogForTest).dismiss() }
        tapInDialog(longPressChat(chats, kofi), "action_pin")
        assertTrue(core.history.chat(kofi)!!.pinned)
        assertEquals(listOf(group, kofi, ama), core.chatRows().map { it.chat.id })
        assertEquals("Épinglée", TestCore.onMain { Views.descendants(chats.window.decorView).first { it.tag == "pinned:$kofi" }.contentDescription.toString() })
        assertEquals(listOf("action_unpin", "action_delete_chat"), dialogTags(longPressChat(chats, kofi)))
        tapInDialog(checkNotNull(chats.chatDialogForTest), "action_delete_chat")
        val asked = checkNotNull(chats.chatDialogForTest)
        val title = TestCore.onMain {
            asked.findViewById<TextView>(asked.context.resources.getIdentifier("alertTitle", "id", "android"))?.text?.toString()
        }
        assertEquals("Supprimer cette discussion ?", title)
        TestCore.onMain { asked.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        inst.waitForIdleSync()
        assertNull(core.history.chat(kofi))
        assertNull(Views.tagged(chats, "chat:$kofi"))
        assertNotNull(Views.tagged(chats, "chat:$ama"))
        assertTrue(TestCore.radio.sent.isEmpty())
    }

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

    /** The chat list, the empty hint and ➕, shown or not. */
    private fun chatParts(screen: Activity) = listOf("chats", "empty", "new_chat").map { Views.tagged(screen, it) != null }

    /** The setup area's height when it is offered exactly the height of what it holds. */
    private fun setupHeightFor(screen: Activity): Pair<Int, Int> = TestCore.onMain {
        val scroll = Views.descendants(screen.window.decorView).first { it.tag == "setup_scroll" } as ScrollView
        val content = scroll.getChildAt(0).height
        scroll.measure(android.view.View.MeasureSpec.makeMeasureSpec(scroll.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(content, android.view.View.MeasureSpec.AT_MOST))
        val measured = scroll.measuredHeight
        scroll.requestLayout()
        content to measured
    }

    @Test fun duringSetupOnlyTheStepsShowAndTheyUseTheWholeHeight() {
        TestCore.install(voices = emptyList(), connected = false)
        val chats = start()
        saveSetupPicture(chats, "voice-before-scroll")
        assertEquals("no chat list, no hint, no plus during setup", listOf(false, false, false), chatParts(chats))
        assertNull(Views.withText(chats, "Pas encore de messages"))
        assertNull(Views.withText(chats, "Tout le monde ici"))
        val (content, height) = setupHeightFor(chats)
        assertTrue("the guide has a height", content > 0)
        assertEquals("the guide may use all the height it is offered", content, height)
        val scroll = Views.all(chats).filterIsInstance<ScrollView>().single()
        val root = TestCore.onMain { scroll.parent as android.view.View }
        // The guide is as tall as what it holds, or it reaches the bottom of the screen's own area.
        assertTrue("the guide stops short: top ${scroll.top}, bottom ${scroll.bottom}, content $content, screen ${root.height} less ${root.paddingBottom}",
            TestCore.onMain { scroll.height == content || scroll.bottom == root.height - root.paddingBottom })
        // The current step's two buttons are whole, not cut by the setup area's edge.
        for (tag in listOf("voice_download", "voice_file")) {
            val button = Views.tagged(chats, tag)!!
            val seen = Rect()
            assertTrue(tag, TestCore.onMain { button.getGlobalVisibleRect(seen) })
            assertEquals("$tag is cut", button.height, seen.height())
        }
        TestCore.onMain { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        Views.waitUntil {
            Views.descendants(chats.window.decorView).first { it.tag == "step_row:3" }.getGlobalVisibleRect(Rect())
        }
        saveSetupPicture(chats, "voice-after-scroll")
    }

    @Test fun theChatsWaitBehindTheStepsAndStepThreeOpensTheGroup() {
        TestCore.install()
        TestCore.onMain { TestCore.radio.receive("!0000aaaa", "[pm1:fr] En privé", true) }
        val group = core.groupChatId()
        val direct = core.history.chats().single { it.kind == ChatKind.DIRECT }.id
        val chats = start()
        assertNotNull("step 3 is the current step", Views.tagged(chats, "card_hello"))
        assertEquals(listOf(false, false, false), chatParts(chats))
        assertNull(Views.tagged(chats, "chat:$group"))
        assertNull("a message that came in during setup waits behind the steps", Views.tagged(chats, "chat:$direct"))
        // The step's own button is the way into the group chat.
        Views.tap(chats, "hello_try")
        Views.waitUntil { resumedNow() is ConversationActivity }
        assertEquals("Tout le monde ici", Views.textOf(Views.tagged(checkNotNull(Views.resumed()), "title")!!))
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }
        assertEquals("still setup: nothing was sent", listOf(false, false, false), chatParts(chats))
        TestCore.onMain { core.setDraft(group, "Bonjour"); assertTrue(core.send(group)) }
        assertNotNull(Views.tagged(chats, "setup_done"))
        assertEquals("the list and the plus are back; the hint is not, there are messages", listOf(true, false, true), chatParts(chats))
        assertNotNull(Views.tagged(chats, "chat:$group"))
        assertEquals("1", Views.textOf(Views.tagged(chats, "unread:$direct")!!))
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
        TestCore.install(voices = emptyList(), connected = false, named = false)
        val chats = start()
        val voiceCard = Views.tagged(chats, "card_voice")!!
        assertNotNull("the current step is numbered", Views.tagged(chats, "step_of"))
        assertEquals("ÉTAPE 1 SUR 5", Views.textOf(Views.tagged(chats, "step_of")!!))
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
        assertNotNull(Views.tagged(chats, "step_row:4"))
        assertNotNull(Views.tagged(chats, "step_row:5"))
        assertEquals("Radio non connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
        assertEquals("step 1 shows only the steps", listOf(false, false, false), chatParts(chats))

        TestCore.onMain {
            Fixtures.installReady(context)
            // This disk-only helper does not send AppCore's normal pack notification.
            chats.onCoreChange(AppCore.Change.PACKS)
        }
        // Step 2: the name, which needs the voice to hear it.
        assertNotNull(Views.tagged(chats, "card_name"))
        assertEquals("ÉTAPE 2 SUR 5", Views.textOf(Views.tagged(chats, "step_of")!!))
        assertNotNull(Views.tagged(chats, "step_done:1"))
        assertNotNull(Views.withText(chats, "Dites votre nom"))
        assertNotNull(Views.tagged(chats, "howto:hello"))
        assertEquals("step 2 shows only the steps", listOf(false, false, false), chatParts(chats))
        saveSetupPicture(chats, "step-name")
        Views.tap(chats, "name_open")
        Views.waitUntil { resumedNow() is NameActivity }
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }
        TestCore.onMain { core.saveAccount("Awa", 3) }
        TestCore.onMain { chats.onCoreChange(AppCore.Change.MESSAGES) }

        assertNotNull(Views.tagged(chats, "card_radio"))
        assertEquals("ÉTAPE 3 SUR 5", Views.textOf(Views.tagged(chats, "step_of")!!))
        assertNotNull(Views.tagged(chats, "step_done:1"))
        assertNotNull(Views.tagged(chats, "step_done:2"))
        assertNotNull("the done row shows the name", Views.withText(chats, "Awa"))
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
        assertEquals("step 2 shows only the steps", listOf(false, false, false), chatParts(chats))

        TestCore.onMain { TestCore.radio.connected(true) }
        assertNotNull(Views.tagged(chats, "card_hello"))
        assertEquals("ÉTAPE 5 SUR 5", Views.textOf(Views.tagged(chats, "step_of")!!))
        assertNotNull(Views.tagged(chats, "step_done:1"))
        assertNotNull(Views.tagged(chats, "step_done:2"))
        assertNotNull(Views.tagged(chats, "step_done:3"))
        assertEquals("Radio connectée", Views.tagged(chats, "radio_status")!!.contentDescription)
        assertNotNull(Views.tagged(chats, "howto:hello"))
        assertEquals("step 3 shows only the steps", listOf(false, false, false), chatParts(chats))
        saveSetupPicture(chats, "step3")
        Views.tap(chats, "hello_try")
        Views.waitUntil { resumedNow() is ConversationActivity }
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }

        val group = TestCore.onMain { core.groupChatId() }
        TestCore.onMain { core.setDraft(group, "Bonjour"); assertTrue(core.send(group)) }
        assertNotNull("synchronous send observers see completion", Views.tagged(chats, "setup_done"))
        assertEquals("the chats are back with the green card", listOf(true, false, true), chatParts(chats))
        assertNotNull(Views.tagged(chats, "chat:$group"))
        inst.waitForIdleSync()
        saveSetupPicture(chats, "complete")
        assertTrue(core.settings.setupFinished)
        TestCore.onMain { chats.onCoreChange(AppCore.Change.PEERS) }
        Views.waitUntil(4_000) { Views.descendants(chats.window.decorView).none { it.tag == "setup_done" && it.isShown } }
        listOf("card_voice", "card_name", "card_radio", "card_add", "card_hello", "step_of").forEach { assertNull(it, Views.tagged(chats, it)) }
        assertEquals(listOf(true, false, true), chatParts(chats))
        assertTrue(core.canTalk())
        Views.finish(chats)
        val reopened = start()
        assertNull("setup never returns on reopening", Views.tagged(reopened, "step_of"))
        assertEquals(listOf(true, false, true), chatParts(reopened))
        assertNull(Views.tagged(reopened, "setup_done"))
    }

    /** A phone that finished setup before names existed: one slim card asks for the name; the chats stay. */
    @Test fun aPhoneSetUpBeforeNamesGetsASlimNameCard() {
        TestCore.install(named = false)
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        assertNotNull(Views.tagged(chats, "card_name"))
        assertNull("no step numbers", Views.tagged(chats, "step_of"))
        assertEquals("the chats, their empty hint and the plus stay", listOf(true, true, true), chatParts(chats))
        Views.tap(chats, "name_open")
        Views.waitUntil { resumedNow() is NameActivity }
        TestCore.onMain { core.saveAccount("Kofi", 1) }
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }
        Views.waitUntil { Views.descendants(chats.window.decorView).none { it.tag == "card_name" } }
    }

    /** Owner report: on opening, the app said "find the radio" while it was still reconnecting by itself. */
    @Test fun whileTheRememberedRadioReconnectsTheCardSaysSoThenTurnsGreen() {
        TestCore.install(connected = false)
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        TestCore.onMain { core.connect("AA:BB:CC:DD:EE:01", "Meshtastic 8d88") }
        val chats = start()
        assertNotNull("connecting, with its turning mark", Views.tagged(chats, "radio_link_card:connecting"))
        assertNotNull(Views.tagged(chats, "radio_badge:connecting"))
        assertNotNull(Views.withText(chats, "Connexion à Meshtastic 8d88…"))
        assertNull("not the find-the-radio card while it reconnects", Views.tagged(chats, "radio_search"))
        assertEquals(emptyList<String>(), A11yCheck.problems(chats))
        TestCore.onMain { TestCore.radio.connected(true) }
        assertNotNull("a green tick when it answers", Views.tagged(chats, "radio_link_card:connected"))
        assertNotNull(Views.tagged(chats, "radio_badge:connected"))
        assertNotNull(Views.withText(chats, "Connectée : Meshtastic 8d88"))
        Views.waitUntil(6_000) { Views.descendants(chats.window.decorView).none { it.tag == "radio_link_card:connected" } }
    }

    @Test fun aReconnectionThatGivesUpShowsTheFindTheRadioCard() {
        TestCore.install(connected = false)
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        assertNotNull("nothing is being tried: find the radio", Views.tagged(chats, "radio_search"))
        assertNull(Views.tagged(chats, "radio_link_card:connecting"))
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
        assertEquals("a problem card leaves the list, the hint and the plus in place", listOf(true, true, true), chatParts(chats))
        assertNotNull(Views.tagged(chats, "chat:${core.groupChatId()}"))
        val (content, height) = setupHeightFor(chats)
        assertEquals("a problem card keeps its height limit, so the chats keep their room", content * 3 / 4, height)
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
        TestCore.onMain {
            // Historical direct chats are read-only: seed the pre-upgrade outgoing bubble, do not send a new one.
            core.history.addOutgoing(direct, "Bien reçu", "fr", System.currentTimeMillis())
            core.settings.firstMessageSent = true
            core.settings.setupFinished = true
        }
        val chats = start()
        val list = Views.all(chats).filter { (it.tag as? String)?.startsWith("chat:") == true }.map { it.tag }
        assertEquals(listOf("chat:$group", "chat:$direct"), list)
        assertEquals("3", Views.textOf(Views.tagged(chats, "people:$group")!!))
        assertEquals("Personnes entendues : 3", Views.tagged(chats, "people:$group")!!.contentDescription)
        assertNotNull(Views.withText(chats, "Tout le monde ici"))
        assertNotNull(Views.withText(chats, "Ama : Salut"))
        assertNotNull(Views.withText(chats, "Kofi"))
        assertEquals("En attente", Views.tagged(chats, "last_status:$direct")!!.contentDescription)
        assertEquals("2", Views.textOf(Views.tagged(chats, "unread:$group")!!))
        assertEquals("Non lus : 2", Views.tagged(chats, "unread:$group")!!.contentDescription)
    }

    @Test fun theGroupRowShowsItsWholeTitleOnANarrowPhoneAndTakesTheChannelName() {
        TestCore.install(connected = false)
        // After setup: during it the chats screen shows only the steps.
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        // A message heard: the row is at its fullest, with a time and an unread count beside the title.
        TestCore.onMain { TestCore.radio.receive("!0000bbbb", "[pm1:fr] Salut", false) }
        val group = TestCore.onMain { core.groupChatId() }
        val chats = start()
        val row = Views.tagged(chats, "chat:$group")!!
        assertEquals("Tout le monde ici", row.contentDescription)
        val title = Views.withText(chats, "Tout le monde ici")!!
        assertTrue(Ui.isContent(title))
        // Laid out again as on a 360 dp wide phone: one line, no letter cut, the people count whole beside it.
        TestCore.onMain {
            val people = Views.descendants(row).first { it.tag == "people:$group" }
            val width = Ui.dp(context, 360)
            val peopleWide = people.width
            row.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, width, row.measuredHeight)
            assertEquals(1, title.lineCount)
            assertEquals("nothing cut", 0, title.layout.getEllipsisCount(0))
            assertTrue(peopleWide > 0)
            assertEquals("the people count keeps its width", peopleWide, people.width)
            val line = people.parent as android.view.View
            val beside = (row as android.view.ViewGroup).let { it.getChildAt(it.childCount - 1) }
            assertTrue("the people count stays inside its line", people.right <= line.width)
            assertTrue("the time and unread count stay inside the row", beside.width > 0 && beside.right <= width - row.paddingRight)
            row.requestLayout()
        }
        TestCore.onMain {
            core.connect("AA:BB:CC:DD:EE:01", "Kofi")
            TestCore.radio.channelName = "Marché"
            TestCore.radio.connected(true)
        }
        inst.waitForIdleSync()
        assertEquals("Marché", Views.tagged(chats, "chat:$group")!!.contentDescription)
        assertNotNull(Views.withText(chats, "Marché"))
        assertNull(Views.withText(chats, "Tout le monde ici"))
    }

    @Test fun unreadClearsWhenTheChatIsOpened() {
        TestCore.install()
        // After setup: during it the chats screen shows only the steps.
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
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

    @Test fun plusOpensAddSomeoneWithoutAHeardRadio() {
        TestCore.install(connected = false)
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        Views.tap(chats, "new_chat")
        Views.waitUntil { resumedNow() is AddSomeoneActivity }
        val add = checkNotNull(Views.resumed())
        assertNotNull(Views.tagged(add, "add_scan"))
        assertNotNull(Views.tagged(add, "add_link"))
        assertTrue(core.history.chats().none { it.kind == ChatKind.DIRECT })
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    @Test fun plusUsesAccountsEvenWhenRadiosHaveBeenHeard() {
        TestCore.install()
        TestCore.onMain {
            core.settings.firstMessageSent = true; core.settings.setupFinished = true
            TestCore.radio.setPeers(listOf(RadioPeer("!0000aaaa", "Kofi"), RadioPeer("!0bad4a2f", "!0bad4a2f")))
        }
        // Keep the old radio-label invariant, but adding a person no longer picks a radio.
        assertEquals("Kofi", core.radioLabel("Kofi", "!0000aaaa"))
        assertEquals("Radio 4a2f", core.radioLabel("!0bad4a2f", "!0bad4a2f"))
        val chats = start()
        Views.tap(chats, "new_chat")
        Views.waitUntil { resumedNow() is AddSomeoneActivity }
        assertTrue(core.history.chats().none { it.kind == ChatKind.DIRECT })
        Views.finish(Views.resumed())
        Views.waitUntil { resumedNow() === chats }
        assertTrue(core.history.chats().none { it.kind == ChatKind.DIRECT })
        assertTrue(TestCore.radio.sent.isEmpty())
    }

    @Test fun veryLongNamesAndMessagesStayOnOneLine() {
        TestCore.install()
        // After setup: during it the chats screen shows only the steps.
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
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
        // Finishing the cover only requests the transition. Idle-sync can return
        // while Chats is still stopped; assert the picture after the real resume,
        // without resetting or extending its original twelve-second deadline.
        Views.waitUntil { resumedNow() === chats && chats.window.decorView.isShown }
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

    /** Step 2 counts only once the radio has a region; connecting alone does not tick it, and nothing is written. */
    /** The radio is step 3 (after the voice and the name). */
    @Test fun theRadioStepWaitsForTheRadioRegion() {
        TestCore.install(connected = false)
        TestCore.access.country = "TG"
        TestCore.onMain {
            core.settings.radioAddress = "AA:BB:CC:DD:EE:01"
            TestCore.radio.region = "UNSET"
            TestCore.radio.connected(true)
        }
        val chats = start()
        assertNotNull(Views.tagged(chats, "card_radio"))
        assertNotNull(Views.tagged(chats, "card_region"))
        assertNull(Views.tagged(chats, "step_done:3"))
        assertNull(Views.tagged(chats, "card_hello"))
        TestCore.onMain { TestCore.radio.connected(false); TestCore.radio.region = "EU_868"; TestCore.radio.connected(true) }
        assertNotNull(Views.tagged(chats, "card_hello"))
        assertNotNull(Views.tagged(chats, "step_done:3"))
        assertNull(Views.tagged(chats, "card_region"))
        assertTrue(TestCore.radio.regionWrites.isEmpty())
    }

    @Test fun theEmptyStateShowsUntilAMessageArrives() {
        TestCore.install()
        // After setup: during it the chats screen shows only the steps.
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        assertNotNull(Views.tagged(chats, "empty"))
        assertNotNull(Views.withText(chats, "Pas encore de messages"))
        assertEquals(listOf(true, true, true), chatParts(chats))
        TestCore.onMain { TestCore.radio.receive("!0000aaaa", "[pm1:fr] Enfin", false) }
        assertNull(Views.tagged(chats, "empty"))
    }

    /** A filled button writes in the background colour, an outlined one in the text colour. */
    private fun filled(screen: Activity, tag: String): Boolean {
        val button = checkNotNull(Views.tagged(screen, tag)) { "No view tagged $tag" } as android.widget.Button
        return TestCore.onMain {
            val palette = Palette(screen)
            assertTrue("$tag is neither filled nor outlined", button.currentTextColor == palette.background || button.currentTextColor == palette.text)
            button.currentTextColor == palette.background
        }
    }

    /** Counts file pickers the screen asks for, and answers "cancelled" so none really opens. */
    private fun pickerMonitor(): android.app.Instrumentation.ActivityMonitor = inst.addMonitor(
        android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("application/zip")
        }, android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)

    @Test fun stepOneButtonsCarryTheNumbersOfThePicturesTwoParts() {
        TestCore.install(voices = emptyList(), connected = false)
        val chats = start()
        assertEquals("1", Views.textOf(Views.tagged(chats, "part:1")!!))
        assertEquals("2", Views.textOf(Views.tagged(chats, "part:2")!!))
        val download = Views.tagged(chats, "voice_download")!!
        val friend = Views.tagged(chats, "voice_file")!!
        assertEquals("1 · Télécharger", Views.textOf(download))
        assertEquals("2 · D'un ami", Views.textOf(friend))
        assertEquals("1 · Télécharger", download.contentDescription.toString())
        assertEquals("2 · D'un ami", friend.contentDescription.toString())
        assertTrue("option 1 stays above option 2", TestCore.onMain { download.bottom <= friend.top })
        assertTrue("with internet, Download is the filled button", filled(chats, "voice_download"))
        assertFalse(filled(chats, "voice_file"))
        assertNull("the guide is closed until asked for", Views.tagged(chats, "voice_guide"))
    }

    @Test fun theFriendButtonOpensAGuideAndOnlyItsOwnButtonOpensThePicker() {
        TestCore.install(voices = emptyList(), connected = false)
        val chats = start()
        val picker = pickerMonitor()
        try {
            Views.tap(chats, "voice_file")
            val guide = checkNotNull(Views.tagged(chats, "voice_guide")) { "the friend guide" }
            assertEquals("the guide never opens the picker by itself", 0, picker.hits)
            assertSame(chats, Views.resumed())
            val card = Views.tagged(chats, "card_voice")!!
            assertTrue("the guide sits in the step's card", TestCore.onMain { Views.descendants(card).any { it === guide } })
            assertEquals("ÉTAPE 1 SUR 5", Views.textOf(Views.tagged(chats, "step_of")!!))
            assertNotNull(Views.withText(chats, "2 · D'un ami"))
            val shown = TestCore.onMain { Views.descendants(guide).filter { it.isShown } }
            assertEquals(listOf("howto:friend", "howto:allow"), shown.filterIsInstance<HowToView>().map { it.tag })
            assertEquals(listOf("Votre ami touche Réglages, puis « Partager l'application ».", "Acceptez le fichier ici. Puis touchez-le."),
                shown.filter { it.tag == "howto_sentence" }.map { Views.textOf(it) })
            assertTrue("no voice yet, so no speaker", shown.none { it.tag == "howto_say" })
            assertNull("the step's own picture and its parts make room", Views.tagged(chats, "howto:download"))
            assertNull(Views.tagged(chats, "part:1"))
            assertNull(Views.tagged(chats, "voice_download"))
            assertNull(Views.tagged(chats, "voice_file"))
            assertEquals("Choisir le fichier", Views.textOf(Views.tagged(chats, "voice_pick")!!))
            assertFalse("choosing a file is the lesser way", filled(chats, "voice_pick"))
            assertEquals("Retour", Views.textOf(Views.tagged(chats, "voice_guide_back")!!))
            // A render (a radio event, a download tick) keeps the guide and still opens nothing.
            TestCore.onMain { chats.onCoreChange(AppCore.Change.PEERS) }
            assertSame(guide, Views.tagged(chats, "voice_guide"))
            assertEquals(0, picker.hits)

            Views.tap(chats, "voice_pick")
            assertEquals("the existing picker, once", 1, picker.hits)
            assertNotNull("the guide stays while the picker is open or cancelled", Views.tagged(chats, "voice_guide"))

            Views.tap(chats, "voice_guide_back")
            assertNull(Views.tagged(chats, "voice_guide"))
            assertNotNull(Views.tagged(chats, "howto:download"))
            assertNotNull(Views.tagged(chats, "voice_download"))
            assertNotNull(Views.tagged(chats, "voice_file"))
            assertEquals(1, picker.hits)
        } finally {
            inst.removeMonitor(picker)
        }
    }

    @Test fun theSystemBackClosesTheGuideAndAnInstalledVoiceEndsIt() {
        TestCore.install(voices = emptyList(), connected = false)
        val chats = start()
        Views.tap(chats, "voice_file")
        assertNotNull(Views.tagged(chats, "voice_guide"))
        @Suppress("DEPRECATION")
        TestCore.onMain { chats.onBackPressed() }
        inst.waitForIdleSync()
        assertNull(Views.tagged(chats, "voice_guide"))
        assertFalse("back only closed the guide", TestCore.onMain { chats.isFinishing })
        assertNotNull(Views.tagged(chats, "voice_download"))

        Views.tap(chats, "voice_file")
        assertNotNull(Views.tagged(chats, "voice_guide"))
        TestCore.onMain {
            Fixtures.installReady(context)
            chats.onCoreChange(AppCore.Change.PACKS)
        }
        assertNull("the voice arrived: the guide is over", Views.tagged(chats, "voice_guide"))
        assertNotNull(Views.tagged(chats, "card_radio"))
        // Losing the voice later starts from the two choices, not from a guide left open.
        TestCore.onMain {
            TestCore.clearPacks()
            chats.onCoreChange(AppCore.Change.PACKS)
        }
        assertNotNull(Views.tagged(chats, "voice_download"))
        assertNull(Views.tagged(chats, "voice_guide"))
    }

    @Test fun theGuideSurvivesRotationAndSoDoesClosingIt() {
        TestCore.install(voices = emptyList(), connected = false)
        val first = start()
        val picker = pickerMonitor()
        try {
            Views.tap(first, "voice_file")
            TestCore.onMain { first.recreate() }
            Views.waitUntil { resumedNow().let { it is ChatsActivity && it !== first } }
            val second = Views.resumed() as ChatsActivity
            activity = second
            inst.waitForIdleSync()
            assertNotNull("the guide comes back", Views.tagged(second, "voice_guide"))
            assertNotNull(Views.tagged(second, "voice_pick"))
            assertEquals("coming back opens no picker", 0, picker.hits)
            Views.tap(second, "voice_guide_back")
            TestCore.onMain { second.recreate() }
            Views.waitUntil { resumedNow().let { it is ChatsActivity && it !== second } }
            val third = Views.resumed() as ChatsActivity
            activity = third
            inst.waitForIdleSync()
            assertNull(Views.tagged(third, "voice_guide"))
            assertNotNull(Views.tagged(third, "voice_download"))
            assertEquals(0, picker.hits)
        } finally {
            inst.removeMonitor(picker)
        }
    }

    @Test fun withoutInternetTheFriendIsTheFilledButton() {
        TestCore.install(voices = emptyList(), connected = false)
        TestCore.access.offline = true
        val chats = start()
        assertTrue("no internet: the friend is the main way", filled(chats, "voice_file"))
        assertFalse(filled(chats, "voice_download"))
        val download = Views.tagged(chats, "voice_download")!!
        val friend = Views.tagged(chats, "voice_file")!!
        assertEquals("1 · Télécharger", Views.textOf(download))
        assertEquals("2 · D'un ami", Views.textOf(friend))
        assertTrue("the numbers keep their order", TestCore.onMain { download.bottom <= friend.top })
        TestCore.onMain {
            TestCore.access.offline = false
            chats.onCoreChange(AppCore.Change.PACKS)
        }
        assertTrue("internet again: Download is the main way", filled(chats, "voice_download"))
        assertFalse(filled(chats, "voice_file"))
    }

    @Test fun aLostVoiceCardOffersTheSameTwoWaysWithoutNumbers() {
        TestCore.install(voices = emptyList())
        TestCore.onMain { core.settings.firstMessageSent = true; core.settings.setupFinished = true }
        val chats = start()
        assertNotNull(Views.tagged(chats, "card_voice"))
        assertNull("a problem card has no step numbers", Views.tagged(chats, "step_of"))
        assertNull("and its single picture has no numbered parts", Views.tagged(chats, "part:1"))
        assertEquals("Télécharger", Views.textOf(Views.tagged(chats, "voice_download")!!))
        assertEquals("D'un ami", Views.textOf(Views.tagged(chats, "voice_file")!!))
        Views.tap(chats, "voice_file")
        assertNotNull(Views.tagged(chats, "voice_guide"))
        assertNotNull(Views.withText(chats, "D'un ami"))
        assertNotNull(Views.tagged(chats, "voice_pick"))
        Views.tap(chats, "voice_guide_back")
        assertNotNull(Views.tagged(chats, "howto:download"))
    }

    /** A download that sits at 0 % and reports no pause for the network: nothing listens on port 9. */
    private fun startStuckVoiceDownload() {
        val downloads = PackDownloads(context, PackStore(Fixtures.packDir(context), GeneratedPackCatalog.entries), "http://10.0.2.2:9/")
        TestCore.onMain { core.missingVoicePacks() }.forEach { assertEquals(DownloadDecision.Start, downloads.start(it)) }
    }

    @Test fun theFriendStaysUnderADownloadThatShowsOnlyItsBar() {
        TestCore.install(voices = emptyList(), connected = false)
        startStuckVoiceDownload()
        val chats = start()
        assertFalse("the phone has given no sign of being offline", TestCore.access.offline)
        val bar = Views.tagged(chats, "voice_bar") as android.widget.ProgressBar
        assertEquals(0, TestCore.onMain { bar.progress })
        assertNull("the running download takes the place of Download", Views.tagged(chats, "voice_download"))
        val friend = checkNotNull(Views.tagged(chats, "voice_file")) { "option 2 under the bar" }
        assertEquals("2 · D'un ami", Views.textOf(friend))
        assertTrue(TestCore.onMain { friend.isShown && friend.isEnabled })
        assertFalse("outlined: the download is the way in progress", filled(chats, "voice_file"))
        val progress = Views.tagged(chats, "voice_progress")!!
        assertEquals("↓ 0 %", Views.textOf(progress))
        assertTrue("under the bar", TestCore.onMain { progress.bottom <= friend.top })
        assertTrue(TestCore.onMain { friend.height } >= Ui.dp(context, 48))
        assertEquals(emptyList<String>(), A11yCheck.problems(chats))
        // A download tick redraws the card and keeps both.
        TestCore.onMain { chats.onCoreChange(AppCore.Change.PACKS) }
        assertNotNull(Views.tagged(chats, "voice_bar"))
        Views.tap(chats, "voice_file")
        assertNotNull("option 2 opens the friend guide", Views.tagged(chats, "voice_guide"))
        assertNotNull(Views.tagged(chats, "voice_pick"))
        Views.tap(chats, "voice_guide_back")
        assertNotNull("back to the bar, the download still running", Views.tagged(chats, "voice_bar"))
        assertNotNull(Views.tagged(chats, "voice_file"))
        assertEquals(setOf("engine-whisper-tiny", "voice-fr-siwis"), core.downloads().pending().keys)
    }

    @Test fun aFriendsFileInstallsWhileADownloadRunsAndEndsThatPacksDownload() {
        TestCore.install(voices = emptyList(), connected = false)
        startStuckVoiceDownload()
        val chats = start()
        Views.tap(chats, "voice_file")
        TestCore.onMain { chats.onNewIntent(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(FixturePackProvider.VOICE), "application/zip")) }
        inst.waitForIdleSync()
        val dialog = checkNotNull(chats.importDialogForTest) { "a file is still confirmed first" }
        assertTrue("nothing installed before confirming", core.store.list().isEmpty())
        TestCore.onMain { dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
        inst.waitForIdleSync()
        core.awaitWorkForTest()
        inst.waitForIdleSync()
        assertEquals(listOf("voice-fr-siwis"), core.store.list().map { it.id })
        assertEquals("Pack importé : Français", Views.textOf(Views.tagged(chats, "note")!!))
        assertEquals("the installed pack's download is over; the other goes on", setOf("engine-whisper-tiny"), core.downloads().pending().keys)
        // The engine is still missing, so step 1 stays, with its bar and the guide the user opened.
        assertNotNull(Views.tagged(chats, "card_voice"))
        assertNotNull(Views.tagged(chats, "voice_guide"))
        Views.tap(chats, "voice_guide_back")
        assertNotNull(Views.tagged(chats, "voice_bar"))
        assertNotNull(Views.tagged(chats, "voice_file"))
    }
}
