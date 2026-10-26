package org.phonemesh.app.demo

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import org.phonemesh.app.AppCore
import org.phonemesh.app.R
import org.phonemesh.app.ScanActivity

/**
 * Scan a code, in the demo: there is no camera, so the person picks what is in front of it. The chosen text goes to
 * the app's real scan handling ([ScanActivity.handleRead]), exactly as a decoded QR code would.
 */
object DemoScan {
    fun show(activity: ScanActivity, world: DemoWorld, core: AppCore) {
        val choices = listOf(
            R.string.demo_scan_kofi to { meetKofi(world) },
            R.string.demo_scan_yaw to { world.person(DemoPerson.Who.YAW).link() },
            R.string.demo_scan_hub to { world.hub.card.encode() },
            R.string.demo_scan_bad to { "https://example.org/menu" },
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.demo_scan_title)
            .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, which ->
                val text = choices[which].second()
                if (!ScanActivity.handleRead(activity, core, text)) {
                    // A real scan screen stays open on its camera and shows why the code failed. This one closes, so
                    // the screen beneath repeats that note once it is back in front; otherwise the failure is silent.
                    val why = core.lastNote
                    finish(activity)
                    if (why != null) Handler(Looper.getMainLooper()).postDelayed({ core.note(why) }, NOTE_AFTER_CLOSE_MS)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish(activity) }
            .setOnCancelListener { finish(activity) }
            .show()
    }

    /** You and Kofi show each other your codes: he scans yours, and the text returned is his, for your scan. */
    private fun meetKofi(world: DemoWorld): String {
        val kofi = world.person(DemoPerson.Who.KOFI)
        val his = kofi.showCode()
        kofi.scan(world.userCode())
        return his
    }

    private fun finish(activity: Activity) { if (!activity.isFinishing) activity.finish() }

    private const val NOTE_AFTER_CLOSE_MS = 600L
}
