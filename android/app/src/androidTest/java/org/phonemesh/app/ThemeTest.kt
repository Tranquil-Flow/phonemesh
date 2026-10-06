package org.phonemesh.app

import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = ContextThemeWrapper(inst.targetContext, R.style.Theme_Phonemesh)

    @Test fun paletteIsLightAndHighContrast() {
        val p = Palette(context)
        assertEquals(Color.WHITE, p.background)
        assertNotEquals(p.good, p.bad)
        listOf(p.background, p.surface, p.text, p.muted, p.outline, p.good, p.bad, p.accent, p.sent)
            .forEach { assertEquals(255, Color.alpha(it)) }
    }

    @Test fun everyIconLoadsAt24dp() {
        assertEquals(25, Icons.all.size)
        Icons.all.forEach { assertEquals(Ui.dp(context, 24), checkNotNull(context.getDrawable(it)).intrinsicWidth) }
    }

    @Test fun controlsAreAtLeast48dpAndDescribed() {
        inst.runOnMainSync {
            val p = Palette(context)
            val button = Ui.button(context, p, "Envoyer", R.drawable.ic_send)
            val icon = Ui.iconButton(context, p, R.drawable.ic_gear, "Réglages")
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            button.measure(spec, spec)
            icon.measure(spec, spec)
            assertTrue(button.measuredHeight >= Ui.dp(context, 48))
            assertTrue(icon.measuredWidth >= Ui.dp(context, 48) && icon.measuredHeight >= Ui.dp(context, 48))
            assertEquals("Envoyer", button.contentDescription)
            assertEquals("Réglages", icon.contentDescription)
        }
    }

    @Test fun bodyTextIsAtLeast18sp() {
        inst.runOnMainSync {
            val text = Ui.text(context, "Bonjour", Palette(context).text)
            val minimum = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics)
            assertTrue(text.textSize >= minimum - 0.5f)
        }
    }
}
