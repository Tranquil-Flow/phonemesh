package org.phonemesh.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * One new-message alert (v0.1.3 N2): [chatId] is the only chat it is for (a tap opens it), null when it covers several
 * (a tap opens the chat list); [messages] counts the unread alerted messages. [fresh]: a new message arrived, so Android
 * may sound; false when an alert only narrows after something was read. It carries no names, words or pictures.
 */
data class Alert(val chatId: Long?, val messages: Int, val fresh: Boolean)

/** The platform side of new-message alerts, so AppCore can be tested with a fake. Main thread. */
interface AlertHost {
    /** Android lets this app show new-message alerts now: the permission (Android 13+), the app and the channel allow it. */
    fun allowed(): Boolean

    /** Makes the alert channel exist (in the app's language), so the person finds it in Android's settings. */
    fun prepare(words: Context)

    /** Shows [alert], replacing the one shown. */
    fun show(alert: Alert, words: Context)

    /** Removes the alert; harmless when none is shown. */
    fun clear()
}

/**
 * Android's notifications for new-message alerts: one channel ([CHANNEL], Android's default importance, so the person's
 * own sound setting applies) and one alert ([ALERT_ID]). The alert names nobody and holds no words: a generic title and
 * line, hidden on the lock screen with no public copy. A tap opens the chat list, with the chat above it when the alert
 * is for one chat; the chat screen is not exported, so only this app's own immutable intent can open it.
 */
class SystemAlertHost(private val app: Context) : AlertHost {
    private val manager: NotificationManager? get() = app.getSystemService(NotificationManager::class.java)

    override fun allowed(): Boolean {
        val manager = manager ?: return false
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!manager.areNotificationsEnabled()) return false
        // No channel yet is allowed: the first alert makes it.
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun prepare(words: Context) {
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, words.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    override fun show(alert: Alert, words: Context) {
        prepare(words)
        manager?.notify(ALERT_ID, build(alert, words))
    }

    override fun clear() {
        manager?.cancel(ALERT_ID)
    }

    fun build(alert: Alert, words: Context): Notification = Notification.Builder(app, CHANNEL)
        .setSmallIcon(R.drawable.ic_chat)
        .setContentTitle(words.getString(if (alert.messages > 1) R.string.alert_new_messages else R.string.alert_new_message))
        .setContentText(words.getString(R.string.alert_open_to_read))
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setVisibility(Notification.VISIBILITY_SECRET)
        .setNumber(alert.messages)
        .setAutoCancel(true)
        // Narrowing after a read is quiet; only a fresh message may sound.
        .setOnlyAlertOnce(!alert.fresh)
        .setContentIntent(tap(alert.chatId))
        .build()

    private fun tap(chatId: Long?): PendingIntent {
        val list = Intent(app, ChatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intents = if (chatId == null) arrayOf(list) else arrayOf(list, ConversationActivity.intent(app, chatId))
        return PendingIntent.getActivities(app, TAP_REQUEST, intents, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL = "new_messages"
        /** Planned sends use 7 and the hub roaming service 8. */
        const val ALERT_ID = 9
        private const val TAP_REQUEST = 9
    }
}
