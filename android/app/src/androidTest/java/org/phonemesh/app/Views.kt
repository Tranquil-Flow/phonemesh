package org.phonemesh.app

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Finds and drives views of the screen in front. Every view access runs on the main thread. */
object Views {
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    fun descendants(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    fun all(activity: Activity): List<View> = TestCore.onMain { descendants(activity.window.decorView).filter { it.isShown } }

    fun tagged(activity: Activity, tag: String): View? = all(activity).firstOrNull { it.tag == tag }

    fun described(activity: Activity, text: String): View? = all(activity).firstOrNull { it.contentDescription?.toString() == text }

    fun withText(activity: Activity, text: String): TextView? =
        all(activity).filterIsInstance<TextView>().firstOrNull { TestCore.onMain { it.text.toString() } == text }

    fun tap(activity: Activity, tag: String) {
        val view = checkNotNull(tagged(activity, tag)) { "No view tagged $tag" }
        TestCore.onMain { view.performClick() }
        inst.waitForIdleSync()
    }

    /** Clicks a ListView row the way a finger does (the list's item listener). */
    fun clickRow(activity: Activity, rowTag: String) {
        val row = checkNotNull(tagged(activity, rowTag)) { "No row tagged $rowTag" }
        TestCore.onMain {
            // A section heading may wrap the tagged content inside the actual list item.
            var item = row
            while (item.parent is View && item.parent !is ListView) item = item.parent as View
            val list = checkNotNull(item.parent as? ListView) { "Row $rowTag is not in a ListView" }
            val position = list.getPositionForView(item)
            check(position != ListView.INVALID_POSITION) { "Row $rowTag has no adapter position" }
            list.performItemClick(item, position, list.getItemIdAtPosition(position))
        }
        inst.waitForIdleSync()
    }

    fun textOf(view: View): String = TestCore.onMain { (view as TextView).text.toString() }

    fun <T : Activity> start(type: Class<T>): T = launch(Intent(inst.targetContext, type))

    @Suppress("UNCHECKED_CAST")
    fun <T : Activity> launch(intent: Intent): T {
        val activity = inst.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as T
        inst.waitForIdleSync()
        return activity
    }

    fun finish(activity: Activity?) {
        if (activity != null) TestCore.onMain { activity.finish() }
        inst.waitForIdleSync()
    }

    fun resumed(): Activity? = TestCore.onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }

    fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!TestCore.onMain(condition)) {
            check(SystemClock.uptimeMillis() < end) { "Condition not met in $timeoutMs ms" }
            Thread.sleep(50)
        }
        inst.waitForIdleSync()
    }
}
