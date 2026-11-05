package org.phonemesh.app

import android.Manifest
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.1.3 N2: the real Android side of new-message alerts, with no permission granted to the app (as on every test run):
 * what Android reports, the channel, the alert's privacy and where a tap leads. Nothing is posted; nothing sounds.
 * Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class SystemAlertsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val host = SystemAlertHost(context)
    private val monitors = ArrayList<Instrumentation.ActivityMonitor>()
    private val opened = ArrayList<android.app.Activity>()

    @After fun clean() {
        opened.forEach { Views.finish(it) }
        Views.finish(Views.resumed())
        monitors.forEach { inst.removeMonitor(it) }
        TestCore.uninstall()
    }

    private fun words() = Locales.wrap(context, "fr")

    @Test fun withoutTheAlertPermissionAndroidAllowsNoAlert() {
        assumeTrue("the permission exists from Android 13", Build.VERSION.SDK_INT >= 33)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(host.allowed())
        assertTrue(PhoneAccess(context).alertPermissionMissing())
    }

    @Test fun theAlertNamesNobodyAndStaysOffTheLockScreen() {
        val one = host.build(Alert(41L, 1, fresh = true), words())
        assertEquals(SystemAlertHost.CHANNEL, one.channelId)
        assertEquals(Notification.VISIBILITY_SECRET, one.visibility)
        assertNull("no redacted copy either", one.publicVersion)
        assertEquals(Notification.CATEGORY_MESSAGE, one.category)
        assertTrue("tapping removes it", one.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("Nouveau message", one.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Ouvrez l'application pour lire.", one.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull("no long text, no picture", one.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull(one.extras.get(Notification.EXTRA_PICTURE))
        assertFalse("a fresh text may sound", one.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(checkNotNull(one.contentIntent).isImmutable)
        val many = host.build(Alert(null, 3, fresh = false), words())
        assertEquals("Nouveaux messages", many.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue("narrowing an alert is quiet", many.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test fun alertsHaveTheirOwnChannel() {
        host.prepare(words())
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        val channel = checkNotNull(manager.getNotificationChannel(SystemAlertHost.CHANNEL))
        assertEquals("Nouveaux messages", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertTrue(SystemAlertHost.CHANNEL != HubRoamingService.CHANNEL)
    }

    private fun watch(type: Class<*>) = inst.addMonitor(type.name, null, false).also { monitors += it }

    /** As the shade would: the system lets a notification's own tap open the app; this test process asks the same. */
    private fun tap(alert: Alert) {
        val intent = checkNotNull(host.build(alert, words()).contentIntent)
        val options = if (Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle() else null
        intent.send(context, 0, null, null, null, null, options)
    }

    @Test fun tappingAnAlertForOneChatOpensThatChatAboveTheChatList() {
        val core = TestCore.install()
        val (_, chatId) = PersonFixture.meet("Bea")
        val chats = watch(ChatsActivity::class.java)
        val conversation = watch(ConversationActivity::class.java)
        tap(Alert(chatId, 1, fresh = true))
        val shown = checkNotNull(inst.waitForMonitorWithTimeout(conversation, 10_000)) { "the chat did not open" }
        opened += shown
        assertEquals(chatId, shown.intent.getLongExtra(ConversationActivity.EXTRA_CHAT_ID, -1))
        Views.waitUntil(10_000) { core.openChatId == chatId }
        // Back from the chat lands on the chat list (Android builds the lower screen only when it is needed).
        Views.finish(shown)
        opened += checkNotNull(inst.waitForMonitorWithTimeout(chats, 10_000)) { "no chat list beneath the chat" }
    }

    @Test fun tappingAnAlertForSeveralChatsOpensTheChatList() {
        TestCore.install()
        val chats = watch(ChatsActivity::class.java)
        val conversation = watch(ConversationActivity::class.java)
        tap(Alert(null, 2, fresh = true))
        opened += checkNotNull(inst.waitForMonitorWithTimeout(chats, 10_000)) { "the chat list did not open" }
        assertEquals(0, conversation.hits)
    }

    @Test fun tappingAnAlertForADeletedChatLandsOnTheChatList() {
        val core = TestCore.install()
        val (_, chatId) = PersonFixture.meet("Bea")
        assertTrue(TestCore.onMain { core.deleteChat(chatId) })
        val chats = watch(ChatsActivity::class.java)
        val conversation = watch(ConversationActivity::class.java)
        tap(Alert(chatId, 1, fresh = true))
        val gone = checkNotNull(inst.waitForMonitorWithTimeout(conversation, 10_000))
        Views.waitUntil(10_000) { gone.isFinishing || gone.isDestroyed }
        opened += checkNotNull(inst.waitForMonitorWithTimeout(chats, 10_000)) { "no chat list after a chat that is gone" }
        assertNull("nothing opened for a chat that is gone", TestCore.onMain { core.openChatId })
    }
}
