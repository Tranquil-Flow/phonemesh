package org.phonemesh.app

import android.app.Activity
import android.graphics.Bitmap
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Release qualification of the group screens through real group/Postman fixtures; never hardware or speech. */
@RunWith(AndroidJUnit4::class)
class V012ReleaseScreensTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var fixture: GroupScreensTest? = null

    @After fun tearDown() {
        fixture?.tearDown()
        repeat(8) { ScreenStates.finish(Views.resumed()) }
        TestCore.uninstall()
    }

    private fun until(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 20_000
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < end) { "release screen not reached" }
            Thread.sleep(50)
        }
        inst.waitForIdleSync()
    }

    private fun clean(a: Activity) {
        inst.waitForIdleSync()
        assertEquals(a.javaClass.simpleName, emptyList<String>(), A11yCheck.problems(a))
        assertTrue("screen has touch targets", A11yCheck.scan(a).targets > 0)
    }

    private fun chooser(language: String, theme: ThemeMode): Activity {
        TestCore.nextTheme = theme
        TestCore.install(language = language, voices = listOf(language))
        PersonFixture.meet("Bea")
        return Views.start(GroupPeopleActivity::class.java)
    }

    private fun details(language: String, theme: ThemeMode): Activity {
        TestCore.nextTheme = theme
        fixture = GroupScreensTest().also { it.beaJoinsThenMessagesShowDeliveredToNofM() }
        TestCore.onMain { TestCore.core.settings.language = language }
        val conversation = checkNotNull(Views.resumed())
        val header = checkNotNull(Views.described(conversation, conversation.getString(R.string.group_details)))
        TestCore.onMain { header.performClick() }
        until { Views.resumed() is GroupDetailsActivity }
        return checkNotNull(Views.resumed())
    }

    @Test fun groupChooserHasDescribedTargetsInBothLanguagesAndThemes() {
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (language in listOf("en-US", "fr")) {
            val a = chooser(language, theme)
            clean(a)
            assertEquals(theme, TestCore.core.settings.theme)
            ScreenStates.finish(a)
            TestCore.uninstall()
        }
    }

    @Test fun groupDetailsHasDescribedTargetsInBothLanguagesAndThemes() {
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (language in listOf("en-US", "fr")) {
            val a = details(language, theme)
            clean(a)
            assertEquals(theme, TestCore.core.settings.theme)
            ScreenStates.finish(a)
            fixture!!.tearDown(); fixture = null
        }
    }

    @Test fun captureGroupScreensInLightAndDark() {
        assumeFalse("only in the screenshot build", BuildConfig.SECURE_SCREENS)
        val dir = File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "v012-group-shots")
        check(dir.deleteRecursively() || !dir.exists()); check(dir.mkdirs())
        val expected = mutableSetOf<String>()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            fun shot(name: String) {
                val a = checkNotNull(Views.resumed())
                Views.waitUntil { a.window.decorView.isLaidOut && !a.window.decorView.isLayoutRequested }
                Thread.sleep(1_200)
                val root = checkNotNull(inst.uiAutomation.rootInActiveWindow)
                check(root.findAccessibilityNodeInfosByText("isn't responding").isEmpty()) { "ANR obscures $name" }
                assertEquals(theme, TestCore.core.settings.theme)
                val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
                val file = "$name-${theme.name.lowercase()}.png"
                try { File(dir, file).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                expected += file
            }
            fun reset() {
                repeat(8) { ScreenStates.finish(Views.resumed()) }
                fixture?.tearDown(); fixture = null
                TestCore.uninstall()
                TestCore.nextTheme = theme
            }
            reset()
            fixture = GroupScreensTest().also {
                it.captureForRelease = { label -> shot(label) }
                it.newPrivateGroupStartsFromAddSomeoneAndWaitsForPeopleToJoin()
            }
            reset()
            fixture = GroupScreensTest().also { it.beaJoinsThenMessagesShowDeliveredToNofM() }
            shot("05-messages-and-delivery")
            val conversation = checkNotNull(Views.resumed())
            val header = checkNotNull(Views.described(conversation, conversation.getString(R.string.group_details)))
            TestCore.onMain { header.performClick() }
            until { Views.resumed() is GroupDetailsActivity }
            val detail = checkNotNull(Views.resumed())
            clean(detail); shot("06-group-details")
            val scroll = Views.all(detail).filterIsInstance<ScrollView>().single()
            TestCore.onMain { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            until { TestCore.onMain { scroll.scrollY > 0 || !scroll.canScrollVertically(1) } }
            shot("07-group-details-bottom")
            Views.tap(detail, "group_leave")
            shot("08-last-admin-leave")
            reset()
            fixture = GroupScreensTest().also { it.aNewMemberSeesRecentMessagesSharedByTheAdmin() }
            shot("09-recent-and-unknown-member")
            reset()
            fixture = GroupScreensTest().also {
                it.captureForRelease = { label -> shot(label) }
                it.anInvitationWaitsWithJoinAndIgnoreUntilChosen()
            }
            shot("11-after-join")
            reset()
        }
        assertEquals(22, expected.size)
        assertEquals(expected, dir.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun captureUpdateScreensInLightAndDark() {
        assumeFalse("only in the screenshot build", BuildConfig.SECURE_SCREENS)
        val dir = File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "v012-updates-shots")
        check(dir.deleteRecursively() || !dir.exists()); check(dir.mkdirs())
        val expected = mutableSetOf<String>()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            fun capture(label: String, body: (UpdatesScreenTest) -> Unit) {
                TestCore.nextTheme = theme
                val f = UpdatesScreenTest()
                try {
                    body(f)
                    val a = checkNotNull(Views.resumed())
                    Views.waitUntil { a.window.decorView.isLaidOut && !a.window.decorView.isLayoutRequested }
                    clean(a)
                    Thread.sleep(1_200)
                    assertEquals(theme, TestCore.core.settings.theme)
                    until { inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == inst.targetContext.packageName }
                    val active = checkNotNull(inst.uiAutomation.rootInActiveWindow)
                    assertEquals(inst.targetContext.packageName, active.packageName.toString())
                    check(active.findAccessibilityNodeInfosByText("isn't responding").isEmpty())
                    val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
                    val name = "$label-${theme.name.lowercase()}.png"
                    try { File(dir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { bitmap.recycle() }
                    expected += name
                } finally {
                    f.tearDown()
                    repeat(8) { ScreenStates.finish(Views.resumed()) }
                }
            }
            capture("01-offer") { it.theLocalHubScreenOpensUpdatesAndAnnouncesAnOfferAlreadyHeld() }
            capture("02-installed") { it.anOfferIsAnnouncedAndNothingIsCopiedUntilTheTap() }
            capture("03-no-wifi") { it.withoutTheHubWifiThePersonIsToldToJoinItAndNothingIsAsked() }
            capture("04-not-set-up") { it.aBuildThatIsNotSetUpForUpdatesSaysSoPlainlyAndOffersNothing() }
        }
        assertEquals(8, expected.size)
        assertEquals(expected, dir.listFiles().orEmpty().map { it.name }.toSet())
    }
}
