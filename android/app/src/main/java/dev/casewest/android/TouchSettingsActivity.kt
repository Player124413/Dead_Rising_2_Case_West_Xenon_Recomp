package dev.casewest.android

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Per-control visibility and size, plus the switch that turns touch off entirely.
 *
 * THE SAME FILE, EDITED FROM TWO PLACES. This screen and [TouchOverlayView]'s in-game edit mode
 * both read and write `<filesDir>/touch_layout.json`, through the one [TouchOverlayView.Layout]
 * model. Dragging a control in-game and setting its size here are not two features with a shared
 * format, they are one persisted layout with two editors — which is the only arrangement in
 * which a change made in one place cannot be silently overwritten by the other.
 *
 * WHY A SEPARATE SCREEN AT ALL, when the overlay can be edited in place. Because the two edits
 * answer different questions. Dragging is positional and immediate, and it is best done while
 * looking at the game. Visibility and size are a list: fifteen controls, each with a checkbox
 * and a slider, which is a form and wants to be read as one. A player hiding the dpad because
 * this title barely uses it should be able to see that the dpad is the thing they hid.
 *
 * The rows are built in code rather than inflated from a layout with fifteen copies of the same
 * four views, because the control list is a table in [TouchOverlayView.Companion.DEFAULTS] and a
 * layout file would be a second copy of it that has to be kept in step by hand.
 */
class TouchSettingsActivity : Activity() {

    private var layout: TouchOverlayView.Layout = TouchOverlayView.Layout()
    private val sizeLabels = HashMap<String, TextView>()

    /**
     * The box every control row lives in, kept so "reset" can rebuild them from the model.
     * A property rather than a local in onCreate because the reset button's listener is written
     * before the box is created, and Kotlin resolves names by declaration order — a local here
     * would be a reference to something that does not exist yet at the point it is named.
     */
    private lateinit var rowsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load the persisted layout the same way the overlay does, so a file this build cannot
        // parse produces the default here exactly as it does there.
        val file = java.io.File(filesDir, "touch_layout.json")
        layout = if (file.isFile) {
            try {
                TouchOverlayView.Layout.fromJson(org.json.JSONObject(file.readText()))
            } catch (e: Exception) {
                TouchOverlayView.Layout()
            }
        } else {
            TouchOverlayView.Layout()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
        }

        root.addView(heading("TOUCH CONTROLS"))
        root.addView(
            help(
                "Everything here is saved as soon as you change it. In game, the Controls button " +
                    "in the top-right enters edit mode: drag a control to move it, pinch it to " +
                    "resize it, then press Done."
            )
        )

        val master = Switch(this).apply {
            text = "On-screen controls"
            isChecked = layout.enabled
            textSize = 16f
            setPadding(0, dp(14), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                layout.enabled = checked
                save()
                // The native side's in-session switch follows immediately, so that turning
                // touch off and returning to a paused game does not leave the overlay
                // publishing into a merge that has been told to ignore it — and, more to the
                // point, so that turning it back ON works without a relaunch.
                try {
                    NativeBridge.nativeTouchSetEnabled(checked)
                } catch (e: UnsatisfiedLinkError) {
                    // The runtime library is not loaded in this process yet, which is the normal
                    // case when the settings screen was opened from the launcher before the
                    // first game. GameActivity.onResume pushes the same value through
                    // TouchOverlayView.load, so nothing is lost by not being able to say it here.
                }
            }
        }
        root.addView(master)
        root.addView(
            help(
                "Off means the overlay publishes nothing and contributes nothing: a Bluetooth " +
                    "pad and the guest's own input still work. Touch is the fourth input source, " +
                    "not a mode the game is in."
            )
        )

        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recreateRows()

        val reset = Button(this).apply {
            text = "Reset to the default layout"
            textSize = 13f
            setOnClickListener {
                layout.reset()
                layout.enabled = master.isChecked
                save()
                recreateRows()
                Toast.makeText(this@TouchSettingsActivity, "Layout reset", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(reset, margins(top = 10))

        root.addView(heading("CONTROLS"))
        root.addView(rowsBox)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Rebuild every control row from the model. Cheap, and it cannot drift from [layout]. */
    private fun recreateRows() {
        rowsBox.removeAllViews()
        sizeLabels.clear()
        // A stand-in for the overlay, used only for its DEFAULTS table: the controls, their
        // labels and their default sizes live in one place and this screen reads them from
        // there rather than restating them.
        for (c in TouchOverlayView.DEFAULTS) {
            rowsBox.addView(rowFor(c))
        }
    }

    private fun rowFor(c: TouchOverlayView.Control): LinearLayout {
        val state = layout.state(c.id)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val visible = CheckBox(this).apply {
            text = c.label
            isChecked = state.visible
            textSize = 14f
            setOnCheckedChangeListener { _, checked ->
                state.visible = checked
                save()
            }
        }
        val percent = TextView(this).apply {
            text = "${(state.scale * 100).toInt()}%"
            textSize = 13f
            setPadding(dp(10), dp(6), 0, 0)
        }
        sizeLabels[c.id] = percent
        top.addView(visible, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(percent)
        row.addView(top)

        val size = SeekBar(this).apply {
            // 50..250 in steps of the model's own 0.5..2.5 range. The limits are the model's,
            // not the widget's: below half a control is not tappable with a thumb and above two
            // and a half the face buttons overlap, so a slider that offered either would be
            // offering a layout that has to be reset by hand.
            max = 200
            progress = ((state.scale - 0.5f) * 100f).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    state.scale = 0.5f + value / 100f
                    sizeLabels[c.id]?.text = "${(state.scale * 100).toInt()}%"
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}

                // Saved on release rather than per tick: a layout written sixty times a second
                // during a drag is sixty chances to be interrupted halfway through a write.
                override fun onStopTrackingTouch(bar: SeekBar?) = save()
            })
        }
        row.addView(size)
        row.addView(help(kindHelp(c)))
        return row
    }

    /** What this control is for, in the terms the title uses rather than in XInput's. */
    private fun kindHelp(c: TouchOverlayView.Control): String = when (c.id) {
        "stickL" -> "Left stick: movement. Proportional — a small push walks, which is why the " +
            "runtime's drift rule zeroes a CONTROLLER's sub-deadzone axes and never ours."
        "stickR" -> "Right stick: camera."
        "dpad" -> "D-pad: menus and the weapon wheel. One control with four bits, so a diagonal " +
            "presses two, exactly as a real pad sends them."
        "lt", "rt" -> "Trigger, digital: a press is 255 and a release is 0. The title applies its " +
            "own ramp to whatever arrives, which is the same thing it does to a pad's trigger."
        "l3", "r3" -> "Stick press. This title binds sprint and the map to them."
        "start", "back" -> "Menus. A phone has no other way to reach these two."
        else -> ""
    }

    private fun save() {
        try {
            java.io.File(filesDir, "touch_layout.json").writeText(layout.toJson().toString(2))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save the layout: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Widgets
    // ---------------------------------------------------------------------------------------

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xff7fb2ff.toInt())
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun help(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xffb9b9c2.toInt())
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(4), 0, 0)
    }

    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
