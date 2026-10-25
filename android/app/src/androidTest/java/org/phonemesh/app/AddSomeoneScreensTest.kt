package org.phonemesh.app

import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.MeshRadio
import org.phonemesh.core.account.AddCode
import java.security.KeyStore

/** Accounts plan step 11 screens: Add someone, a request, opening a link, the safety picture, the QR round trip. */
@RunWith(AndroidJUnit4::class)
class AddSomeoneScreensTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val core get() = TestCore.core
    private var other: History? = null

    @After fun tearDown() {
        Views.finish(Views.resumed())
        TestCore.uninstall()
        other?.close()
        context.deleteDatabase(BEA)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith("test-bea") }.forEach { keys.deleteEntry(it) }
    }

    private fun resumedNow(): android.app.Activity? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    private fun waitFor(type: Class<*>): android.app.Activity {
        var found: android.app.Activity? = null
        Views.waitUntil(8_000) { resumedNow()?.takeIf { type.isInstance(it) }?.also { found = it } != null }
        return found!!
    }

    /** Bea's phone, outside the app under test: it opens Awa's link and hands over the hello it would send. */
    private fun beaOpens(link: String): Pair<AccountStore, ByteArray> {
        val history = History(context, BEA).also { other = it }
        val store = AccountStore(history, Vault("test-vault"), true, keyPrefix = "test-bea").also { it.setName("Bea", 5) }
        val sent = ArrayList<ByteArray>()
        Accounts(history, store, { sent += it; true }).scanned(link)
        return store to sent.single()
    }

    @Test fun addSomeoneShowsMyCodeAndBothWaysToAdd() {
        TestCore.install()
        val screen = Views.start(AddSomeoneActivity::class.java)
        val code = Views.tagged(screen, "my_code") as ImageView
        assertNotNull(TestCore.onMain { code.drawable })
        assertTrue(Views.textOf(Views.tagged(screen, "my_name")!!).contains("Awa"))
        assertNotNull(Views.tagged(screen, "add_scan"))
        assertNotNull(Views.tagged(screen, "add_link"))
    }

    @Test fun theQrDrawnIsTheQrRead() {
        TestCore.install()
        val text = TestCore.onMain { core.accountsFlow.showCode() }
        val bitmap = AddSomeoneActivity.qr(text, 600)
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val luminance = ByteArray(pixels.size) { (pixels[it] and 0xff).toByte() }
        assertEquals(text, ScanActivity.decode(luminance, bitmap.width, bitmap.width, bitmap.height))
        assertTrue(AddCode.parse(text) is AddCode.Qr)
    }

    @Test fun scanningAContactCodeNamesThemAndReturnsToChats() {
        TestCore.install()
        TestCore.onMain { core.accountsFlow.showCode() }
        val keys = org.phonemesh.core.session.SoftKeys.generate()
        val pair = org.phonemesh.core.session.KeyPairBytes.generate()
        val qr = org.phonemesh.core.account.QrCode(
            org.phonemesh.core.account.ContactCard.create(keys, "Bea", 5), pair.public)
        val screen = Views.start(AddSomeoneActivity::class.java)
        TestCore.onMain { assertTrue(ScanActivity.handleRead(screen, core, AddCode.of(qr))) }
        waitFor(ChatsActivity::class.java)
        assertEquals(UiText(R.string.scan_added_met, listOf("Bea")), TestCore.onMain { core.lastNote })
        assertTrue(TestCore.onMain { core.history.contactRow(qr.card.accountId.toString())!!.met })
    }

    @Test fun scanningALinkNamesThemAndOpensTheSafetyPicture() {
        TestCore.install()
        val keys = org.phonemesh.core.session.SoftKeys.generate()
        val card = org.phonemesh.core.account.ContactCard.create(keys, "Bea", 5)
        val screen = Views.start(AddSomeoneActivity::class.java)
        TestCore.onMain { assertTrue(ScanActivity.handleRead(screen, core, AddCode.of(card))) }
        waitFor(SafetyActivity::class.java)
        assertEquals(UiText(R.string.scan_added, listOf("Bea")), TestCore.onMain { core.lastNote })
        assertFalse(TestCore.onMain { core.history.contactRow(card.accountId.toString())!!.met })
    }

    @Test fun aHelloShowsARequestAndAcceptOpensTheSafetyPicture() {
        TestCore.install()
        val link = TestCore.onMain { core.accountsFlow.link() }
        val (bea, hello) = beaOpens(link)
        val screen = Views.start(AddSomeoneActivity::class.java)
        TestCore.onMain { core.onBroadcastData("!0000bea0", MeshRadio.ACCOUNT_PORT, hello, 0, 1L, "") }
        Views.waitUntil { screen.window.decorView.findViewWithTag<android.view.View>("request") != null }
        Views.tap(screen, "request_accept")
        val safety = waitFor(SafetyActivity::class.java)
        Views.waitUntil(10_000) { safety.window.decorView.findViewWithTag<android.widget.GridLayout>("safety_icons")?.childCount == 6 }
        assertNotNull(core.history.contactRow(bea.accountId.toString()))
        assertFalse(core.history.contactRow(bea.accountId.toString())!!.met)
    }

    @Test fun theSameAndNotTheSameButtonsMarkTheContact() {
        TestCore.install()
        val (bea, hello) = beaOpens(TestCore.onMain { core.accountsFlow.link() })
        val request = TestCore.onMain { core.accountsFlow.onPacket(hello)!! }
        TestCore.onMain { core.acceptRequest(request) }
        val intent = Intent(context, SafetyActivity::class.java).putExtra(SafetyActivity.EXTRA_ACCOUNT, bea.accountId.toBytes())
        var safety: SafetyActivity = Views.launch(intent)
        val same = Views.tagged(safety, "safety_same")!!
        val grid = Views.tagged(safety, "safety_icons") as android.widget.GridLayout
        Views.waitUntil(15_000) { grid.childCount == 6 && same.isEnabled }
        Views.tap(safety, "safety_same")
        Views.waitUntil { core.history.contactRow(bea.accountId.toString())!!.met }
        safety = Views.launch(intent)
        Views.tap(safety, "safety_differ")
        Views.waitUntil { !core.history.contactRow(bea.accountId.toString())!!.met }
        Views.waitUntil { core.lastNote?.id == R.string.safety_differ_note }
    }

    @Test fun anAddMeLinkAddsThePersonAndShowsTheirPictures() {
        TestCore.install()
        val history = History(context, BEA).also { other = it }
        val bea = AccountStore(history, Vault("test-vault"), true, keyPrefix = "test-bea").also { it.setName("Bea", 5) }
        val link = AddCode.of(bea.card())
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor(SafetyActivity::class.java)
        val row = core.history.contactRow(bea.accountId.toString())!!
        assertEquals("Bea", row.name)
        assertFalse(row.met)
    }

    @Test fun aDamagedLinkAddsNobody() {
        TestCore.install()
        context.startActivity(Intent(context, LinkActivity::class.java).putExtra("card", "AAAA").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Views.waitUntil { core.lastNote?.id == R.string.add_bad_code }
        assertTrue(core.history.contactRows().isEmpty())
    }

    @Test fun settingsOpensAddSomeone() {
        TestCore.install()
        val settings = Views.start(SettingsActivity::class.java)
        Views.tap(settings, "settings_add")
        waitFor(AddSomeoneActivity::class.java)
    }

    /** Holds the actual safety-picture worker, not a timer or a replacement UI callback. */
    private fun heldSafety(
        language: String = "en-US", outcome: String = "real", met: Boolean = false,
        check: (SafetyActivity, org.phonemesh.core.account.AccountId, java.util.concurrent.CountDownLatch) -> Unit,
    ) {
        TestCore.install(language = language, connected = false)
        val (bea, hello) = beaOpens(TestCore.onMain { core.accountsFlow.link() })
        val request = TestCore.onMain { core.accountsFlow.onPacket(hello)!! }
        TestCore.onMain { core.acceptRequest(request); core.setContactMet(bea.accountId, met) }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        val original = TestCore.onMain { SafetyActivity.computePicture }
        TestCore.onMain {
            SafetyActivity.computePicture = { flow, id ->
                worker.set(Thread.currentThread())
                entered.countDown()
                kotlin.check(release.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "safety test did not release worker" }
                when (outcome) {
                    "missing" -> null
                    "throw" -> throw IllegalStateException("injected safety computation failure")
                    "short" -> intArrayOf(0, 1, 2, 3, 4)
                    "invalid" -> intArrayOf(0, 1, 2, 3, 4, 64)
                    else -> original(flow, id)
                }
            }
        }
        try {
            val screen: SafetyActivity = Views.launch(SafetyActivity.intent(context, bea.accountId))
            assertTrue("the real async worker reached the barrier", entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("hash must stay off main", worker.get() !== android.os.Looper.getMainLooper().thread)
            check(screen, bea.accountId, release)
        } finally {
            release.countDown()
            try {
                worker.get()?.let {
                    it.join(15_000)
                    assertFalse("worker must finish before fixture teardown", it.isAlive)
                }
                inst.waitForIdleSync()
            } finally { TestCore.onMain { SafetyActivity.computePicture = original } }
        }
    }

    private fun safetyReadyOnlyAfterSixPictures(language: String, preparing: String) = heldSafety(language) { screen, id, release ->
        val same = Views.tagged(screen, "safety_same")!!
        val grid = Views.tagged(screen, "safety_icons") as android.widget.GridLayout
        assertFalse("positive confirmation is disabled while hash is held", TestCore.onMain { same.isEnabled })
        assertEquals(0, TestCore.onMain { grid.childCount })
        assertNotNull(Views.withText(screen, preparing))
        assertFalse(TestCore.onMain { core.accountsFlow.isMet(id) })
        release.countDown()
        Views.waitUntil(15_000) { grid.childCount == 6 && same.isEnabled }
        TestCore.onMain {
            repeat(6) { index ->
                val cell = grid.getChildAt(index)
                assertTrue(cell.contentDescription.isNotBlank())
                val image = Views.descendants(cell).filterIsInstance<ImageView>().single()
                assertNotNull(image.drawable)
            }
            assertFalse(core.accountsFlow.isMet(id))
            same.performClick()
            assertTrue(core.accountsFlow.isMet(id))
            assertTrue(screen.isFinishing)
        }
    }

    @Test fun safetyConfirmationWaitsForSixPicturesInEnglish() =
        safetyReadyOnlyAfterSixPictures("en-US", "Preparing your pictures.")

    @Test fun safetyConfirmationWaitsForSixPicturesInFrench() =
        safetyReadyOnlyAfterSixPictures("fr", "Préparation des images.")

    @Test fun safetyPositiveHandlerRejectsClicksBeforePicturesEvenWhenInvokedDirectly() = heldSafety { screen, id, _ ->
        val same = Views.tagged(screen, "safety_same")!!
        TestCore.onMain {
            // callOnClick deliberately bypasses enabled-state touch dispatch.
            same.callOnClick()
            assertFalse("handler cannot mark an unseen comparison met", core.accountsFlow.isMet(id))
            assertFalse(screen.isFinishing)
        }
    }

    private fun failedSafety(outcome: String, language: String = "en-US", met: Boolean = false) =
        heldSafety(language, outcome, met) { screen, id, release ->
            val same = Views.tagged(screen, "safety_same")!!
            val grid = Views.tagged(screen, "safety_icons") as android.widget.GridLayout
            val expected = if (language == "fr") "Images indisponibles. Revenez puis réessayez." else "Pictures unavailable. Go back and try again."
            release.countDown()
            Views.waitUntil(15_000) {
                Views.descendants(screen.window.decorView).filterIsInstance<android.widget.TextView>()
                    .any { it.isShown && it.text.toString() == expected }
            }
            TestCore.onMain {
                assertFalse(same.isEnabled)
                assertEquals(0, grid.childCount)
                same.callOnClick()
                assertEquals("failure must not change the existing met flag", met, core.accountsFlow.isMet(id))
                assertFalse(screen.isFinishing)
            }
        }

    @Test fun missingSafetyPicturesStayUnconfirmedAndExplainInEnglish() = failedSafety("missing")
    @Test fun missingSafetyPicturesPreservePriorMetAndExplainInFrench() = failedSafety("missing", "fr", true)
    @Test fun failedSafetyComputationStaysUnconfirmed() = failedSafety("throw")
    @Test fun incompleteSafetyPicturesStayUnconfirmed() = failedSafety("short")
    @Test fun invalidSafetyPictureIndexStaysUnconfirmed() = failedSafety("invalid")

    @Test fun safetyNegativeChoiceRemainsAvailableWhilePicturesArePreparing() = heldSafety(met = true) { screen, id, release ->
        Views.tap(screen, "safety_differ")
        assertFalse(TestCore.onMain { core.accountsFlow.isMet(id) })
        assertFalse(TestCore.onMain { screen.isFinishing })
        assertNotNull(Views.withText(screen, screen.getString(R.string.safety_differ_note)))
        release.countDown()
        val grid = Views.tagged(screen, "safety_icons") as android.widget.GridLayout
        Views.waitUntil(15_000) { grid.childCount == 6 }
        assertFalse("completion itself must never mark met", TestCore.onMain { core.accountsFlow.isMet(id) })
        assertNotNull(Views.withText(screen, screen.getString(R.string.safety_differ_note)))
    }

    private companion object { const val BEA = "test-bea.db" }
    @Test fun aDamagedLinkShowsItsReasonOnTheSurvivingScreen() {
        TestCore.install(language = "en-US")
        context.startActivity(Intent(context, LinkActivity::class.java).putExtra("card", "AAAA").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val chats = waitFor(ChatsActivity::class.java)
        val note = Views.tagged(chats, ScreenActivity.NOTE_TAG)!!
        Views.waitUntil { note.isShown }
        assertEquals(chats.getString(R.string.add_bad_code), Views.textOf(note))
        assertTrue(TestCore.onMain { core.history.contactRows().isEmpty() })
    }

    @Test fun aLinkStorageFailureShowsSaveFailureRatherThanDamagedCode() {
        TestCore.install(language = "en-US", connected = false)
        val keys = org.phonemesh.core.session.SoftKeys.generate()
        val card = org.phonemesh.core.account.ContactCard.create(keys, "Bea", 5)
        TestCore.onMain {
            core.history.writableDatabase.execSQL("CREATE TRIGGER reject_link_save BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected link save failure'); END")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AddCode.of(card)))
            .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val chats = waitFor(ChatsActivity::class.java)
        val note = Views.tagged(chats, ScreenActivity.NOTE_TAG)!!
        Views.waitUntil { note.isShown }
        assertEquals(chats.getString(R.string.mail_save_failed), Views.textOf(note))
        TestCore.onMain {
            assertTrue(core.history.contactRows().isEmpty())
            assertTrue(core.history.outbox(card.accountId.toString()).isEmpty())
            assertTrue(TestCore.radio.broadcasts.isEmpty())
        }
    }

}
