package org.phonemesh.app

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import org.phonemesh.core.HowToScenes

/** All how-to pictures, with their sentence and speaker, grouped for the Help page. */
class HelpActivity : ScreenActivity() {
    private val panels = ArrayList<HowToPanel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad() * 2)
        }
        column.addView(backBar(getString(R.string.help)))
        column.addView(noteLine())
        GROUPS.forEachIndexed { index, (title, scenes) ->
            column.addView(Ui.text(this, getString(title), palette.text, 22f, bold = true).apply {
                tag = "help_group:$index"
                setPadding(0, pad() * 3 / 2, 0, pad() / 2)
            })
            scenes.filter { it != "pair" || HowTo.showPair }.forEach { scene ->
                val panel = howTo(scene, compact = true).apply {
                    tag = "help:$scene"
                    setPadding(0, pad() / 2, 0, pad() / 2)
                }
                panels += panel
                column.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        show(ScrollView(this).apply { addView(column) })
    }

    /** A newly arrived voice changes the speakers, never the pictures or their loop position. */
    override fun render() = panels.forEach { it.update() }

    companion object {
        val GROUPS: List<Pair<Int, List<String>>> = listOf(
            R.string.help_setup to listOf("download", "friend", "power", "close", "search", "pair", "hello"),
            R.string.help_talking to listOf("cancel", "lock", "listen", "delivery", "failed", "arrive"),
            R.string.help_more to HowToScenes.MORE,
            R.string.help_problems to listOf("lost", "bt", "short", "notheard", "mic"),
            R.string.help_rare to listOf("allow", "dlfail", "damaged", "refused", "older", "space", "wait", "toolong", "playfail"),
        )
    }
}
