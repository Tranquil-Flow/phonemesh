package org.phonemesh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Why roaming is off or refused, each with the sentence that tells the person what to do. */
enum class RoamingBlock(val words: Int) {
    NO_HUB(R.string.roaming_block_no_hub),
    PERMISSION(R.string.roaming_block_permission),
    START_REFUSED(R.string.roaming_block_refused),
    SERVICE_ENDED(R.string.roaming_block_ended),
}

/** What a starting service learns about its own start: run, a start that was cancelled or replaced, or a start Android would refuse. */
enum class RoamingGate { RUN, STALE, BLOCKED }

/** The platform side of roaming, so AppCore can be tested with a fake. [start] is asked only after the same guards the service checks. */
interface RoamingHost {
    /** Asks Android to start the foreground service for [epoch]. False when Android refused; then nothing was started. */
    fun start(epoch: Int): Boolean

    /** Stops the service if it runs; harmless when it does not. */
    fun stop()
}

class SystemRoamingHost(private val app: Context) : RoamingHost {
    override fun start(epoch: Int): Boolean = try {
        app.startForegroundService(Intent(app, HubRoamingService::class.java).putExtra(HubRoamingService.EXTRA_EPOCH, epoch))
        true
    } catch (e: RuntimeException) {
        // A background start refused by Android (or a missing declaration) is a visible failure, never a crash.
        Log.w("Phonemesh", "hub roaming service not started", e)
        false
    }

    override fun stop() {
        runCatching { app.stopService(Intent(app, HubRoamingService::class.java)) }
    }
}

/**
 * Keeps the hub work alive while no screen is open, only because the person switched roaming on (accounts decision 9).
 * A foreground service of type connectedDevice: unlike the short planned-send service it has no three-minute cap.
 * It does no network work itself; it is the process's reason to stay, and AppCore (which it never replaces) keeps
 * polling and sending through its one postman path. Every callback to AppCore carries the epoch of the start it belongs
 * to, so a late start, destroy or timeout of an older service can never switch a newer or a disabled one back on.
 *
 * Order in [onStartCommand]: the guards (current epoch, Bluetooth permissions, hub saved) first, then startForeground
 * inside a try, then the attach. Android 14 refuses a connectedDevice start without its prerequisites; that is caught
 * and reported, never left to the system's start timer.
 */
class HubRoamingService : Service() {
    private var running: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val held = AppCore.get(this)
        if (intent?.action == ACTION_STOP) {
            held.setHubRoaming(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running != null) return START_NOT_STICKY
        val epoch = intent?.getIntExtra(EXTRA_EPOCH, NO_EPOCH) ?: NO_EPOCH
        when (held.roamingGate(epoch)) {
            RoamingGate.BLOCKED -> {
                // Starting in the foreground would throw here; leave without it. AppCore only starts this service after the same guard.
                held.roamingServiceEnded(epoch, held.roamingPrerequisite() ?: RoamingBlock.START_REFUSED)
                stopSelf()
                return START_NOT_STICKY
            }
            RoamingGate.STALE -> {
                // Started, then switched off or replaced before it ran: Android still expects the foreground call, so make it and leave.
                runCatching { enter(held) }
                leave()
                return START_NOT_STICKY
            }
            RoamingGate.RUN -> Unit
        }
        val foreground = try {
            enter(held)
            true
        } catch (e: RuntimeException) {
            Log.w("Phonemesh", "hub roaming cannot run in the foreground", e)
            false
        }
        if (!foreground) {
            held.roamingServiceEnded(epoch, RoamingBlock.START_REFUSED)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!held.roamingAttach(epoch)) {
            leave()
            return START_NOT_STICKY
        }
        running = epoch
        return START_NOT_STICKY
    }

    private fun enter(held: AppCore) {
        val note = notification(held)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTE_ID, note)
        }
    }

    private fun leave() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15 time limits do not apply to this type; if a later Android adds one, the service ends and says so. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        running?.let { AppCore.get(this).roamingServiceEnded(it, RoamingBlock.SERVICE_ENDED) }
        running = null
        leave()
    }

    override fun onDestroy() {
        val epoch = running
        running = null
        // Our own stop already moved AppCore on to a newer epoch, so only an unexpected end reaches the person.
        if (epoch != null) AppCore.get(this).roamingServiceEnded(epoch, RoamingBlock.SERVICE_ENDED)
        super.onDestroy()
    }

    private fun notification(core: AppCore): Notification {
        val words = core.localized()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, words.getString(R.string.roaming_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 0, Intent(this, HubRoamingService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 1, Intent(this, HubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio_on)
            .setContentTitle(words.getString(R.string.roaming_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), words.getString(R.string.roaming_stop), stop).build())
        // API 26+ channel above disables sound and vibration; framework Builder has no setSilent API.
        return builder.build()
    }

    companion object {
        const val CHANNEL = "hub_roaming"
        const val NOTE_ID = 8
        const val ACTION_STOP = "org.phonemesh.app.STOP_HUB_ROAMING"
        const val EXTRA_EPOCH = "epoch"
        private const val NO_EPOCH = -1
    }
}
