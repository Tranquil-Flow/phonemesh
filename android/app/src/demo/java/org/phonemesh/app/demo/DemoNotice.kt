package org.phonemesh.app.demo

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.phonemesh.app.AppCore
import org.phonemesh.app.Locales
import org.phonemesh.app.R

/**
 * The way to the demo controls from any screen: a quiet notification that stays while the demo runs ("pull down for
 * the controls"). The status bar label only marks the demo; Android keeps touches in the status bar for itself.
 */
object DemoNotice {
    private const val CHANNEL = "phonemesh-demo"
    private const val ID = 3101
    private const val ASK = 3102

    fun show(base: Context) {
        val context = Locales.wrap(base, AppCore.get(base).settings.language)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.demo_controls_title),
            NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, DemoControlsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio_on)
            .setContentTitle(context.getString(R.string.demo_app_name))
            .setContentText(context.getString(R.string.demo_notice_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build())
    }

    /** Asked once, from the demo's own controls screen, never in the middle of the app's screens. */
    fun askOnce(activity: Activity, world: DemoWorld) {
        if (Build.VERSION.SDK_INT < 33 || world.askedNotifications) return
        world.askedNotifications = true
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK)
        }
    }
}
