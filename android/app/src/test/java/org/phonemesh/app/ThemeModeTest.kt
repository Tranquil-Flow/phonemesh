package org.phonemesh.app

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test fun keysRoundTripAndUnknownIsAuto() {
        ThemeMode.values().forEach { assertEquals(it, ThemeMode.of(it.key)) }
        assertEquals(ThemeMode.AUTO, ThemeMode.of(null))
        assertEquals(ThemeMode.AUTO, ThemeMode.of("sepia"))
    }

    @Test fun lightAndDarkReplaceOnlyTheNightBits() {
        val darkPhone = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        val lightPhone = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
        assertEquals(darkPhone, Themes.uiMode(darkPhone, ThemeMode.AUTO))
        assertEquals(lightPhone, Themes.uiMode(darkPhone, ThemeMode.LIGHT))
        assertEquals(darkPhone, Themes.uiMode(lightPhone, ThemeMode.DARK))
    }
}