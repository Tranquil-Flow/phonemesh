package org.phonemesh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.phonemesh.core.T1Probe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hardware test T1 of the accounts spec, in probe builds only (BuildConfig.T1_PROBE): do full-size broadcasts on the
 * accounts port reach the other radio's phone, intact? Started from the Mac:
 * `adb shell am broadcast -p org.phonemesh.app -a org.phonemesh.app.T1_SEND --ei run 1 --ei count 5 --ei gapMs 6000`.
 * Every send and arrival goes to logcat (tag PhonemeshT1) and to files/t1-probe.log, which outlives the log's rollover.
 */
internal class T1ProbeRun(private val app: Context, private val send: (ByteArray) -> Long) {
    private val main = Handler(Looper.getMainLooper())
    private val file = File(app.filesDir, "t1-probe.log")
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun start() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val run = intent.getIntExtra("run", 0)
                val count = intent.getIntExtra("count", 1).coerceIn(1, 60)
                val gap = intent.getIntExtra("gapMs", 6000).coerceIn(1000, 120_000)
                val size = intent.getIntExtra("size", 228).coerceIn(T1Probe.MIN_SIZE, T1Probe.MAX_SIZE)
                note("send run=$run count=$count gap=${gap}ms size=$size")
                for (seq in 0 until count) main.postDelayed({
                    note(runCatching { send(T1Probe.packet(run, seq, System.currentTimeMillis(), size)) }.fold(
                        { "sent run=$run seq=$seq id=$it" }, { "send failed run=$run seq=$seq: ${it.message}" },
                    ))
                }, seq.toLong() * gap)
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        note("probe ready")
    }

    fun got(sender: String, payload: ByteArray, channel: Int, packetId: Long, signal: String) {
        val got = T1Probe.read(payload)
        note(if (got == null) "got damaged-or-foreign from=$sender size=${payload.size} channel=$channel id=$packetId $signal"
        else "got run=${got.run} seq=${got.seq} intact size=${payload.size} from=$sender channel=$channel id=$packetId $signal")
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        runCatching { file.appendText("${clock.format(Date())} $line\n") }
    }

    companion object {
        const val ACTION = "org.phonemesh.app.T1_SEND"
        private const val TAG = "PhonemeshT1"
    }
}
