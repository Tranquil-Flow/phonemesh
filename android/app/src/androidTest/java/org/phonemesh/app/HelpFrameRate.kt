package org.phonemesh.app

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in measuring tool, not a frame-rate assertion. Uses fakes and the system animation setting. */
@RunWith(AndroidJUnit4::class)
class HelpFrameRate {
    @Test fun measureTheHelpPage() {
        assumeTrue("runs only with -e help_frames 1",
            InstrumentationRegistry.getArguments().getString("help_frames") == "1")
        val inst = InstrumentationRegistry.getInstrumentation()
        TestCore.install()
        var screen: HelpActivity? = null
        try {
            // startActivitySync waits for idleness, which moving pictures need not provide.
            // Start in the fixture's still mode, then restore the phone's setting before measuring.
            val page = Views.start(HelpActivity::class.java)
            screen = page
            TestCore.onMain {
                StoryView.animationsEnabled = StoryView.systemAnimations
                page.window.decorView.requestLayout()
            }
            Thread.sleep(2_000)
            shell(inst, "dumpsys gfxinfo org.phonemesh.app reset")
            val scroll = TestCore.onMain {
                Views.descendants(page.window.decorView).filterIsInstance<ScrollView>().single()
            }
            for (i in 0 until 4) {
                TestCore.onMain { scroll.smoothScrollTo(0, scroll.getChildAt(0).height * i / 4) }
                Thread.sleep(5_000)
            }
            File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "help-gfxinfo.txt")
                .writeText(shell(inst, "dumpsys gfxinfo org.phonemesh.app"))
        } finally {
            TestCore.onMain { StoryView.animationsEnabled = { false } }
            Views.finish(screen)
            TestCore.uninstall()
        }
    }

    private fun shell(inst: Instrumentation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
