package org.phonemesh.app

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A11yCheck must report what it claims to: each rule is shown firing on a small hand-built screen. */
class A11yCheckTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private var activity: Activity? = null

    @After fun tearDown() {
        Views.finish(activity)
        TestCore.uninstall()
    }

    private fun dp(value: Int) = Ui.dp(inst.targetContext, value)

    private fun problemsWith(build: (Activity, LinearLayout) -> Unit): List<String> {
        TestCore.install(language = null)
        val screen = Views.start(LanguageActivity::class.java)
        activity = screen
        TestCore.onMain {
            val column = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL }
            build(screen, column)
            screen.setContentView(FrameLayout(screen).apply { addView(column) })
        }
        inst.waitForIdleSync()
        return A11yCheck.problems(screen)
    }

    private fun text(screen: Activity, value: String, sp: Float = 20f) = TextView(screen).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun button(screen: Activity, sizeDp: Int, description: String?) = View(screen).apply {
        isClickable = true
        contentDescription = description
        layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun list(screen: Activity, rowDp: Int) = ListView(screen).apply {
        adapter = object : ArrayAdapter<String>(screen, 0, listOf("one", "two")) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                text(screen, getItem(position)!!).apply { layoutParams = AbsListViewParams(rowDp) }
        }
        layoutParams = LinearLayout.LayoutParams(dp(200), dp(300))
    }

    @Suppress("FunctionName")
    private fun AbsListViewParams(rowDp: Int) = android.widget.AbsListView.LayoutParams(dp(200), dp(rowDp))

    private fun clean(screen: Activity, column: LinearLayout) {
        column.addView(button(screen, 48, "ok"))
        column.addView(text(screen, "Short words only"))
        column.addView(list(screen, 48))
    }

    @Test fun aCleanScreenReportsNothing() {
        assertEquals(emptyList<String>(), problemsWith { screen, column -> clean(screen, column) })
    }

    @Test fun aSmallClickableIsReported() {
        val found = problemsWith { screen, column -> clean(screen, column); column.addView(button(screen, 30, "small")) }
        assertTrue(found.toString(), found.any { it.startsWith("too small") })
    }

    @Test fun anUndescribedClickableIsReported() {
        val found = problemsWith { screen, column -> clean(screen, column); column.addView(button(screen, 48, null)) }
        assertTrue(found.toString(), found.any { it.startsWith("no description") })
    }

    @Test fun nineWordsAreReported() {
        val found = problemsWith { screen, column -> clean(screen, column); column.addView(text(screen, "one two three four five six seven eight nine")) }
        assertTrue(found.toString(), found.any { it.startsWith("more than eight words") })
    }

    @Test fun aTechnicalWordIsReported() {
        val found = problemsWith { screen, column -> clean(screen, column); column.addView(text(screen, "Un paquet")) }
        assertTrue(found.toString(), found.any { it.startsWith("technical word") })
    }

    @Test fun smallTextIsReported() {
        val found = problemsWith { screen, column -> clean(screen, column); column.addView(text(screen, "Tiny", sp = 14f)) }
        assertTrue(found.toString(), found.any { it.startsWith("text under 18 sp") })
    }

    @Test fun smallContentTextIsStillReported() {
        val found = problemsWith { screen, column -> clean(screen, column); column.addView(Ui.content(text(screen, "Tiny", sp = 14f))) }
        assertTrue(found.toString(), found.any { it.startsWith("text under 18 sp") })
    }

    @Test fun longContentTextIsNotReported() {
        val found = problemsWith { screen, column ->
            clean(screen, column)
            column.addView(Ui.content(text(screen, "one two three four five six seven eight nine paquet")))
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test fun aShortListRowIsReportedEvenThoughNotClickable() {
        val found = problemsWith { screen, column ->
            column.addView(button(screen, 48, "ok"))
            column.addView(list(screen, 30))
        }
        assertTrue(found.toString(), found.any { it.startsWith("too small") && it.contains("TextView") })
    }

    @Test fun aScreenWithNothingToTouchIsReported() {
        val found = problemsWith { screen, column -> column.addView(text(screen, "Only words")) }
        assertTrue(found.toString(), found.any { it.startsWith("no touch targets") })
    }
}
