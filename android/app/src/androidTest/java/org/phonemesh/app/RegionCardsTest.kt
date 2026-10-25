package org.phonemesh.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.RegionResult
import org.phonemesh.core.DeliveryStatus

/** The region cards on the chats and chat screens, with the fake radio. A region is written only after a tap. */
@RunWith(AndroidJUnit4::class)
class RegionCardsTest {
    private var activity: Activity? = null
    private val radio get() = TestCore.radio
    private fun text(id: Int, vararg args: Any) = activity!!.getString(id, *args)
    /** The country's name in French, the test language. */
    private fun country(code: String) = java.util.Locale("", code).getDisplayCountry(java.util.Locale.FRENCH)

    @After fun tearDown() {
        Views.finish(Views.resumed().takeIf { it !== activity })
        Views.finish(activity)
        TestCore.uninstall()
    }

    /** A remembered radio connects reporting [region], with the phone in [country]. Setup step 1 (the voice) is done. */
    private fun radio(region: String, country: String?, theme: ThemeMode = ThemeMode.AUTO) {
        TestCore.nextTheme = theme
        TestCore.install(connected = false)
        TestCore.access.country = country
        TestCore.onMain {
            TestCore.core.settings.radioAddress = ADDRESS
            TestCore.core.settings.radioName = "Kofi"
            radio.region = region
            radio.connected(true)
        }
    }

    private fun enabled(screen: Activity, tag: String): Boolean = Views.tagged(screen, tag)!!.let { TestCore.onMain { it.isEnabled } }

    /** Polls from the test thread (Views looks things up on the main thread itself). */
    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < end) { "Condition not met in 5 s" }
            Thread.sleep(50)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun chats() = Views.start(ChatsActivity::class.java).also { activity = it }

    private fun conversation(draft: String?): Activity {
        val group = TestCore.onMain { TestCore.core.groupChatId() }
        if (draft != null) TestCore.onMain { TestCore.core.setDraft(group, draft) }
        return Views.launch<ConversationActivity>(ConversationActivity.intent(InstrumentationRegistry.getInstrumentation().targetContext, group))
            .also { activity = it }
    }

    @Test fun unsetRadioShowsTheSetUpCardAndOnlyYesWrites() {
        radio("UNSET", "TG")
        val screen = chats()
        assertNotNull(Views.tagged(screen, "card_region"))
        assertNotNull("step 2 stays open until the radio has a region", Views.tagged(screen, "card_radio"))
        assertNotNull(Views.withText(screen, text(R.string.region_set, "Togo")))
        assertEquals(text(R.string.region_set, "Togo"), Views.tagged(screen, "region_picture")!!.contentDescription.toString())
        assertNotNull(Views.tagged(screen, "region_other"))
        // Showing the card, rendering again and reading it aloud write nothing.
        TestCore.onMain { TestCore.core.note(UiText(R.string.wait_for_operation)) }
        Views.tap(screen, "region_listen")
        assertEquals(AppCore.LINE_PLAYBACK, TestCore.onMain { TestCore.core.playingId })
        assertTrue("nothing is written before a tap", radio.regionWrites.isEmpty())
        Views.tap(screen, "region_yes")
        assertEquals(listOf("EU_868"), radio.regionWrites)
        assertNotNull(Views.withText(screen, text(R.string.region_working)))
        // While the radio restarts there is nothing to tap, and nothing more is written.
        listOf("region_yes", "region_other", "region_change", "region_keep", "region_retry", "region_helpers")
            .forEach { assertNull(it, Views.tagged(screen, it)) }
        TestCore.onMain { TestCore.core.region.yes() }
        assertEquals(listOf("EU_868"), radio.regionWrites)
    }

    @Test fun theVerifiedRadioSaysReadyThenTheCardLeaves() {
        radio("UNSET", "TG")
        val screen = chats()
        Views.tap(screen, "region_yes")
        TestCore.onMain { radio.connected(false) }
        assertNotNull("the card stays while the radio restarts", Views.withText(screen, text(R.string.region_working)))
        assertNull("not the find-radio step", Views.tagged(screen, "radio_search"))
        TestCore.onMain { radio.region = "EU_868"; radio.connected(true) }
        assertNotNull(Views.withText(screen, text(R.string.region_ready, "Togo")))
        await { Views.tagged(screen, "card_region") == null }
        assertNotNull("step 3 is next", Views.tagged(screen, "card_hello"))
        assertEquals(listOf("EU_868"), radio.regionWrites)
    }

    @Test fun otherRegionShowsChangeAndKeepHidesIt() {
        radio("US", "TG")
        val screen = chats()
        assertNotNull(Views.tagged(screen, "region_change"))
        assertNotNull(Views.withText(screen, text(R.string.region_is_set, country("US"))))
        assertNotNull(Views.withText(screen, text(R.string.region_change_to, "Togo")))
        Views.tap(screen, "region_keep")
        await { Views.tagged(screen, "card_region") == null }
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun changeWritesTheNewRegionOnlyWhenTapped() {
        radio("US", "TG")
        val screen = chats()
        assertTrue(radio.regionWrites.isEmpty())
        Views.tap(screen, "region_change")
        assertEquals(listOf("EU_868"), radio.regionWrites)
    }

    @Test fun unlistedCountryShowsForHelpersAndWritesNothing() {
        radio("UNSET", "BJ")
        val screen = chats()
        assertNotNull(Views.tagged(screen, "region_helpers"))
        assertNotNull(Views.withText(screen, text(R.string.region_unknown, country("BJ"))))
        assertNull(Views.tagged(screen, "region_yes"))
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun matchingRadioShowsNoCard() {
        radio("EU_868", "DE")
        val screen = chats()
        assertNull(Views.tagged(screen, "card_region"))
        assertNotNull("step 2 is done", Views.tagged(screen, "card_hello"))
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun afterSetupAnUnsetRadioIsAProblemCardWithoutNumbers() {
        radio("UNSET", "TG")
        TestCore.onMain { TestCore.core.settings.firstMessageSent = true; TestCore.core.settings.setupFinished = true }
        val screen = chats()
        assertNotNull(Views.tagged(screen, "card_region"))
        assertNotNull(Views.tagged(screen, "region_yes"))
        assertNull(Views.tagged(screen, "step_of"))
        assertNull("the radio is there: not the lost-radio picture", Views.tagged(screen, "howto:lost"))
    }

    @Test fun conversationExplainsTheBlockedSendAndKeepsTheDraft() {
        radio("UNSET", "TG")
        val screen = conversation("Bonjour")
        assertNotNull(Views.tagged(screen, "card_region"))
        val send = Views.tagged(screen, "draft_send")!!
        assertFalse(TestCore.onMain { send.isEnabled })
        TestCore.onMain { send.performClick(); TestCore.core.send(TestCore.core.groupChatId()) }
        assertEquals("Bonjour", TestCore.onMain { TestCore.core.draft(TestCore.core.groupChatId()) })
        assertTrue(radio.sent.isEmpty())
        assertTrue("nothing is written before a tap", radio.regionWrites.isEmpty())
        // Yes works from here too; sending stays off while the radio restarts.
        Views.tap(screen, "region_yes")
        assertEquals(listOf("EU_868"), radio.regionWrites)
        assertNotNull(Views.withText(screen, text(R.string.region_working)))
        assertFalse(TestCore.onMain { send.isEnabled })
        TestCore.onMain { radio.connected(false); radio.region = "EU_868"; radio.connected(true) }
        assertTrue(TestCore.onMain { send.isEnabled })
        assertEquals("Bonjour", TestCore.onMain { TestCore.core.draft(TestCore.core.groupChatId()) })
    }

    @Test fun blockedRegionDraftKeepsActualActionsFullyOnScreen() {
        val screen = ScreenStates.regionBlockedSend().also { activity = it }
        val controls = listOf("draft_clear", "draft_play", "draft_send").map { tag ->
            tag to checkNotNull(Views.tagged(screen, tag))
        }
        Views.waitUntil { controls.all { (_, view) -> view.width > 0 && view.height > 0 } }
        TestCore.onMain {
            controls.forEach { (tag, view) ->
                val visible = android.graphics.Rect()
                assertTrue("$tag must remain visible beside the region explanation", view.getGlobalVisibleRect(visible))
                assertEquals("$tag must not be vertically clipped", view.height, visible.height())
                assertEquals("$tag must not be horizontally clipped", view.width, visible.width())
            }
        }
        assertFalse(enabled(screen, "draft_send"))
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun conversationWithoutADraftShowsNoCardAndAReadyRadioNone() {
        radio("UNSET", "TG")
        assertNull(Views.tagged(conversation(null), "card_region"))
        Views.finish(activity)
        TestCore.onMain { radio.connected(false); radio.region = "EU_868"; radio.connected(true) }
        val screen = conversation("Bonjour")
        assertNull(Views.tagged(screen, "card_region"))
        assertTrue(enabled(screen, "draft_send"))
    }

    /** A write that never went out: nothing reconnects by itself; Try again reconnects first, and the next tap writes. */
    @Test fun tryAgainReconnectsFirstThenWrites() {
        radio("UNSET", "TG")
        val screen = chats()
        TestCore.onMain { radio.regionResult = RegionResult.DROPPED }
        Views.tap(screen, "region_yes")
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), TestCore.onMain { TestCore.core.region.phase })
        assertNotNull(Views.withText(screen, text(R.string.region_not_changed)))
        assertNull("not the working words", Views.withText(screen, text(R.string.region_working)))
        assertTrue(enabled(screen, "region_retry"))
        assertEquals(1, radio.regionWrites.size)
        // The radio goes away: the card stays, with Try again, and nothing reconnects or writes by itself.
        TestCore.onMain { radio.connected(false) }
        assertNotNull(Views.tagged(screen, "card_region"))
        assertTrue(enabled(screen, "region_retry"))
        assertTrue(radio.connects.isEmpty())
        Views.tap(screen, "region_retry")
        assertEquals(listOf(ADDRESS), radio.connects.toList())
        assertEquals("the first tap only reconnects", 1, radio.regionWrites.size)
        assertNotNull("the card shows that it is trying", Views.withText(screen, text(R.string.radio_connecting)))
        assertFalse(enabled(screen, "region_retry"))
        // Connected again: still nothing written until the next tap.
        TestCore.onMain { radio.regionResult = RegionResult.SENT; radio.connected(true) }
        assertNull(Views.withText(screen, text(R.string.radio_connecting)))
        assertTrue(enabled(screen, "region_retry"))
        assertEquals(1, radio.regionWrites.size)
        Views.tap(screen, "region_retry")
        assertEquals(listOf("EU_868", "EU_868"), radio.regionWrites)
        assertNotNull(Views.withText(screen, text(R.string.region_working)))
    }

    /** S3: a failed Change can be left. Back shows the question again, and Keep is there; nothing more is written. */
    @Test fun aFailureCardLeadsBackToTheQuestion() {
        radio("US", "TG")
        val screen = chats()
        TestCore.onMain { radio.regionResult = RegionResult.DROPPED }
        Views.tap(screen, "region_change")
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), TestCore.onMain { TestCore.core.region.phase })
        assertNotNull(Views.tagged(screen, "region_retry"))
        assertNull(Views.tagged(screen, "region_keep"))
        assertEquals(text(R.string.back), Views.textOf(Views.tagged(screen, "region_back")!!))
        Views.tap(screen, "region_back")
        assertEquals(RegionPhase.Idle, TestCore.onMain { TestCore.core.region.phase })
        assertNotNull(Views.tagged(screen, "region_change"))
        assertNull(Views.tagged(screen, "region_retry"))
        assertEquals("Back writes nothing", 1, radio.regionWrites.size)
        assertTrue(radio.connects.isEmpty())
        Views.tap(screen, "region_keep")
        await { Views.tagged(screen, "card_region") == null }
        assertNotNull("step 2 is done", Views.tagged(screen, "card_hello"))
        assertEquals(1, radio.regionWrites.size)
    }

    /** S3: the card that says "Ask a helper" has the helper's button, and a way back; neither writes. */
    @Test fun theCardThatSaysAskAHelperOpensTheHelperList() {
        radio("UNSET", "TG")
        val screen = chats()
        Views.tap(screen, "region_yes")
        repeat(2) { TestCore.onMain { radio.connected(false); radio.region = "UNSET"; radio.connected(true) } }
        assertEquals(RegionPhase.Failed(RegionFailure.NOT_TAKEN), TestCore.onMain { TestCore.core.region.phase })
        assertNotNull(Views.withText(screen, text(R.string.region_ask_helper)))
        assertNotNull(Views.tagged(screen, "region_retry"))
        assertNotNull(Views.tagged(screen, "region_back"))
        assertEquals(text(R.string.region_for_helpers), Views.textOf(Views.tagged(screen, "region_helpers")!!))
        Views.tap(screen, "region_helpers")
        await { Views.resumed() is RegionActivity }
        val list = Views.resumed()!!
        assertNotNull("the helper list, not the country list", Views.tagged(list, "regions"))
        assertNotNull(Views.tagged(list, "helper_set"))
        assertEquals("opening the list writes nothing", 2, radio.regionWrites.size)
        assertEquals(RegionPhase.Failed(RegionFailure.NOT_TAKEN), TestCore.onMain { TestCore.core.region.phase })
    }

    /** S3: the other failure cards have no helper button, and Back from a radio that is away leaves no card. */
    @Test fun backFromARadioThatDidNotReturnClearsTheCard() {
        radio("UNSET", "TG")
        val screen = chats()
        val yes = Views.tagged(screen, "region_yes")!!
        TestCore.onMain { TestCore.core.region.returnMs = 0; yes.performClick(); radio.connected(false) }
        await { TestCore.onMain { TestCore.core.region.phase } == RegionPhase.Failed(RegionFailure.NO_RETURN) }
        assertNull(Views.tagged(screen, "region_helpers"))
        Views.tap(screen, "region_back")
        assertEquals(RegionPhase.Idle, TestCore.onMain { TestCore.core.region.phase })
        assertNull(Views.tagged(screen, "card_region"))
        assertEquals(1, radio.regionWrites.size)
        // The radio returns unchanged: the question again, nothing written by itself.
        TestCore.onMain { radio.region = "UNSET"; radio.connected(true) }
        assertNotNull(Views.tagged(screen, "region_yes"))
        assertEquals(1, radio.regionWrites.size)
    }

    /** B1: "Send again" is off like Send while sending is blocked, and a tap that gets through sends nothing. */
    @Test fun sendAgainIsOffWhileSendingIsBlocked() {
        radio("UNSET", "TG")
        val group = TestCore.onMain { TestCore.core.groupChatId() }
        // A message the radio reported it could not send (no radio at all makes a message wait instead).
        val failed = TestCore.onMain {
            val m = TestCore.core.history.addOutgoing(group, "Bonjour", "fr", System.currentTimeMillis())
            TestCore.core.history.setStatus(m.id, DeliveryStatus.FAILED)
            TestCore.core.history.message(m.id)!!
        }
        assertEquals(DeliveryStatus.FAILED, failed.status)
        val screen = conversation(null)
        val again = "resend:${failed.id}"
        assertFalse(enabled(screen, again))
        Views.tap(screen, again)
        assertTrue(radio.sent.isEmpty())
        assertEquals(DeliveryStatus.FAILED, TestCore.onMain { TestCore.core.history.message(failed.id)!!.status })
        // Still off while the radio restarts with its new region.
        TestCore.onMain { TestCore.core.region.yes() }
        assertFalse(enabled(screen, again))
        TestCore.onMain { radio.connected(false); radio.region = "EU_868"; radio.connected(true) }
        assertTrue(enabled(screen, again))
        Views.tap(screen, again)
        assertEquals(1, radio.sent.size)
        assertEquals(DeliveryStatus.WAITING, TestCore.onMain { TestCore.core.history.message(failed.id)!!.status })
    }

    @Test fun aFailedReconnectLeavesTryAgainReady() {
        radio("UNSET", "TG")
        val screen = chats()
        TestCore.onMain { radio.regionResult = RegionResult.DROPPED }
        Views.tap(screen, "region_yes")
        TestCore.onMain { radio.connected(false) }
        Views.tap(screen, "region_retry")
        assertFalse(enabled(screen, "region_retry"))
        TestCore.onMain { radio.failConnect() }
        assertTrue(enabled(screen, "region_retry"))
        assertNull(Views.withText(screen, text(R.string.radio_connecting)))
    }

    @Test fun theSetUpCardPassesTheScreenChecksInLightAndDark() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            radio("UNSET", "TG", theme)
            assertEquals(theme.name, emptyList<String>(), A11yCheck.problems(chats()))
            Views.finish(activity)
            assertEquals(theme.name, emptyList<String>(), A11yCheck.problems(conversation("Bonjour")))
            Views.finish(activity)
            TestCore.uninstall()
        }
    }

    @Test fun theChangeHelperAndFailureCardsPassTheScreenChecks() {
        radio("US", "TG")
        assertEquals(emptyList<String>(), A11yCheck.problems(chats()))
        Views.finish(activity)
        TestCore.uninstall()
        radio("UNSET", "BJ", ThemeMode.DARK)
        assertEquals(emptyList<String>(), A11yCheck.problems(chats()))
        Views.finish(activity)
        TestCore.uninstall()
        radio("UNSET", "TG")
        TestCore.onMain { radio.regionResult = RegionResult.DROPPED }
        val screen = chats()
        Views.tap(screen, "region_yes")
        assertNotNull(Views.tagged(screen, "region_back"))
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
        // The card with the most buttons: Try again, For helpers and Back, in the dark.
        Views.finish(activity)
        TestCore.uninstall()
        radio("UNSET", "TG", ThemeMode.DARK)
        val dark = chats()
        Views.tap(dark, "region_yes")
        repeat(2) { TestCore.onMain { radio.connected(false); radio.region = "UNSET"; radio.connected(true) } }
        listOf("region_retry", "region_helpers", "region_back").forEach { assertNotNull(it, Views.tagged(dark, it)) }
        assertEquals(emptyList<String>(), A11yCheck.problems(dark))
    }

    /** The picture: the amber radio with the country's flag on its screen; still while animations are off; kept across renders. */
    @Test fun thePictureShowsTheFlagOnTheRadioAndSurvivesRenders() {
        radio("UNSET", "TG")
        val screen = chats()
        val picture = Views.tagged(screen, "region_picture") as RegionPicture
        assertFalse("animations are off in tests: no frames", TestCore.onMain { picture.isRunning })
        val counts = TestCore.onMain {
            val bitmap = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
            picture.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            listOf(0xFFFFCA28, 0xFF006A4E, 0xFFD21034, 0xFF76FF03).map { colour -> pixels.count { it == colour.toInt() } }
        }
        assertTrue("radio body $counts", counts[0] > 100)
        assertTrue("Togo green $counts", counts[1] > 20)
        assertTrue("Togo red $counts", counts[2] > 5)
        assertTrue("green light $counts", counts[3] > 3)
        TestCore.onMain { TestCore.core.note(UiText(R.string.wait_for_operation)); radio.setPeers(emptyList()) }
        assertSame(picture, Views.tagged(screen, "region_picture"))
    }

    private companion object { const val ADDRESS = "AA:BB:CC:DD:EE:01" }
}
