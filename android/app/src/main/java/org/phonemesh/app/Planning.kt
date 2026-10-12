package org.phonemesh.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Wakes the phone when the next planned message is due; null clears it. */
fun interface PlanAlarm {
    fun set(at: Long?)
}

/**
 * An exact alarm (USE_EXACT_ALARM on Android 13 and later, granted at install; SCHEDULE_EXACT_ALARM before). If Android
 * still refuses an exact one, an inexact alarm is next best: the message may go out a little late.
 */
class SystemPlanAlarm(private val context: Context) : PlanAlarm {
    override fun set(at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val wake = PendingIntent.getBroadcast(
            context, 0, Intent(context, PlannedReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarms.cancel(wake)
            return
        }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, wake)
        } catch (e: SecurityException) {
            Log.w("Phonemesh", "exact alarm refused; using an inexact one", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, wake)
        }
    }
}

/** The alarm went off: a short foreground service connects the radio and sends what is due. */
class PlannedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            context.startForegroundService(Intent(context, PlannedSendService::class.java))
        } catch (e: RuntimeException) {
            // Android may refuse a background start; whatever can go now goes, the rest waits for the app to open.
            Log.w("Phonemesh", "planned send service not started", e)
            AppCore.get(context).sendDue()
        }
    }
}

/**
 * A short foreground service (Android 14: shortService). Holds the radio like an open screen while planned messages are due: the same reconnect, and the same grace before
 * the radio is let go. It stops once nothing is due, or after [GIVE_UP_MS] when the radio cannot be reached; what is
 * left goes out the next time the app opens.
 */
class PlannedSendService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var core: AppCore? = null
    private val check = object : Runnable {
        override fun run() {
            val held = core ?: return
            held.sendDue()
            if (held.hasDuePlanned()) main.postDelayed(this, CHECK_MS) else finish()
        }
    }
    private val giveUp = Runnable { finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (core != null) return START_NOT_STICKY
        val held = AppCore.get(this)
        val foreground = try {
            val note = notification(held)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(NOTE_ID, note)
            }
            true
        } catch (e: RuntimeException) {
            Log.w("Phonemesh", "planned send cannot run in the foreground", e)
            false
        }
        if (!foreground) {
            held.sendDue()
            stopSelf()
            return START_NOT_STICKY
        }
        core = held
        held.activityStarted()
        main.post(check)
        main.postDelayed(giveUp, GIVE_UP_MS)
        return START_NOT_STICKY
    }

    private fun finish() {
        main.removeCallbacksAndMessages(null)
        core?.activityStopped()
        core = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 14 ends a short service after about three minutes; it stops well before (GIVE_UP_MS), this is the backstop. */
    override fun onTimeout(startId: Int) = finish()

    override fun onDestroy() {
        if (core != null) finish()
        super.onDestroy()
    }

    private fun notification(core: AppCore): Notification {
        val words = core.localized()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, words.getString(R.string.planned_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(words.getString(R.string.planned_sending))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL = "planned"
        const val NOTE_ID = 7
        const val CHECK_MS = 2_000L
        const val GIVE_UP_MS = 90_000L
    }
}
