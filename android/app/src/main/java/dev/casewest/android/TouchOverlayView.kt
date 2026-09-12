package dev.casewest.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The on-screen controls: where they are, how big they are, whether they are there at all,
 * and the decoding of a finger into an XInput state.
 *
 * THE SPLIT, and it is written down in runtime/cpu/touch_input.h as well as here because both
 * halves have to agree. GEOMETRY is this file's — hit-testing, dp scaling, dragging, resizing,
 * visibility, persistence: all of it is Android View work, and the app already has a
 * compositor, a density and a canvas. The guest-facing half — whether touch contributes at
 * all, the merge into pad 0, the no-button-may-stay-stuck guarantees and the counters that
 * make a bug report readable — is native, because that is where the other three input sources
 * meet and a fourth that arrived by a different route would be a fourth theory of input.
 *
 * WHAT IS PUBLISHED. One [TouchSnapshot]-shaped JNI call per touch event, in XInput's units:
 * button bits, 0..255 triggers, ±32767 sticks with Y POSITIVE UP. SDL's stick Y points down;
 * the flip is here, at the source, because a value the guest reads as "down" while the thumb
 * is up is a control that inverts itself and looks like a bug in the game.
 *
 * WHAT IS NOT PUBLISHED, and why that matters more than the mapping: touches that do not land
 * on a control are NOT consumed. [onTouchEvent] returns false for them and they fall through
 * to SDL's surface, so the title still gets the taps and drags it would have got without an
 * overlay on top of it — menus included. An overlay that swallowed the whole screen would be
 * an overlay that had to reimplement every menu the game has.
 *
 * EDITING. Long-press enters edit mode: drag a control to move it, pinch it to resize it, and
 * GameActivity's "Done" button leaves and saves. Per-control visibility, exact sizes, the
 * master disable and "reset to defaults" are in [TouchSettingsActivity], which edits the same
 * [Layout] this view draws — one persisted model, two ways to reach it, and no third.
 */
class TouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ---------------------------------------------------------------------------------------
    // The controls
    // ---------------------------------------------------------------------------------------

    /**
     * What a control IS, which decides both how it is drawn and how it decodes.
     *
     * [bit] is the XInput button bit for a button-like control — the same values
     * runtime/host/window.cpp lists as XI_*, restated here rather than shared through a
     * generated header because they are ABI: XInput's, and therefore the guest's, and a
     * mismatch shows up as a control that presses something else.
     */
    enum class Kind { BUTTON, DPAD, STICK, TRIGGER }

    data class Control(
        val id: String,
        val label: String,
        val kind: Kind,
        val bit: Int = 0,
        /** Default centre, as a fraction of the view. Flipped for a left-handed layout. */
        val defX: Float,
        val defY: Float,
        /** Default diameter in dp. */
        val defDp: Float,
        /** Which stick a STICK control drives, and which half a DPAD's axes feed. */
        val right: Boolean = false,
    )

    companion object {
        // XInput's button bits. Restated from window.cpp's XI_* on purpose: they are the
        // guest's ABI, not our naming, and a table that has to be read next to the decoding
        // is a table that can be audited.
        private const val XI_DPAD_UP = 0x0001
        private const val XI_DPAD_DOWN = 0x0002
        private const val XI_DPAD_LEFT = 0x0004
        private const val XI_DPAD_RIGHT = 0x0008
        private const val XI_START = 0x0010
        private const val XI_BACK = 0x0020
        private const val XI_LEFT_THUMB = 0x0040
        private const val XI_RIGHT_THUMB = 0x0080
        private const val XI_LEFT_SHOULDER = 0x0100
        private const val XI_RIGHT_SHOULDER = 0x0200
        private const val XI_A = 0x1000
        private const val XI_B = 0x2000
        private const val XI_X = 0x4000
        private const val XI_Y = 0x8000

        /**
         * The default layout, and the reason it looks like an Xbox pad rather than like a
         * phone: this title was made for a gamepad, its prompt art is gamepad glyphs, and
         * cpu/touch_input.cpp's merge flips those glyphs to the pad's whenever touch
         * contributes. A layout that invented its own arrangement would have the player
         * reading "press A" while looking at a circle labelled something else.
         */
        val DEFAULTS: List<Control> = listOf(
            // Left half: the stick the title moves the character with, and the dpad it uses
            // for menus and for the weapon wheel.
            Control("stickL", "MOVE", Kind.STICK, defX = 0.16f, defY = 0.70f, defDp = 132f),
            Control("dpad", "DPAD", Kind.DPAD, defX = 0.34f, defY = 0.86f, defDp = 104f),
            Control("lb", "LB", Kind.BUTTON, XI_LEFT_SHOULDER, 0.14f, 0.30f, 62f),
            Control("lt", "LT", Kind.TRIGGER, defX = 0.05f, defY = 0.44f, defDp = 62f),
            // Right half: the face buttons, the camera stick, and the two that this title
            // spends most of its time in.
            Control("stickR", "LOOK", Kind.STICK, right = true, defX = 0.84f, defY = 0.70f, defDp = 132f),
            Control("a", "A", Kind.BUTTON, XI_A, 0.90f, 0.80f, 66f),
            Control("b", "B", Kind.BUTTON, XI_B, 0.97f, 0.66f, 66f),
            Control("x", "X", Kind.BUTTON, XI_X, 0.83f, 0.66f, 66f),
            Control("y", "Y", Kind.BUTTON, XI_Y, 0.90f, 0.52f, 66f),
            Control("rb", "RB", Kind.BUTTON, XI_RIGHT_SHOULDER, 0.86f, 0.30f, 62f),
            Control("rt", "RT", Kind.TRIGGER, defX = 0.95f, defY = 0.44f, defDp = 62f),
            // The two the title's menus need and that a phone has no other way to reach.
            Control("start", "START", Kind.BUTTON, XI_START, 0.62f, 0.16f, 54f),
            Control("back", "BACK", Kind.BUTTON, XI_BACK, 0.38f, 0.16f, 54f),
            // Pressing a stick in is a real input here: the title binds sprint and the map to
            // them, and an overlay without them is an overlay missing two buttons.
            Control("l3", "L3", Kind.BUTTON, XI_LEFT_THUMB, 0.16f, 0.44f, 46f),
            Control("r3", "R3", Kind.BUTTON, XI_RIGHT_THUMB, 0.84f, 0.44f, 46f),
        )

        /**
         * The overlay's own stick deadzone, as a fraction of the radius.
         *
         * Not the guest's deadzone, which it applies to whatever we send. This one exists
         * because a thumb resting on glass reports a centre that is a pixel or two off, and a
         * value of 300 out of 32767 is enough for the title's own ramp to start a slow walk:
         * the character drifts while the player is holding still. Everything inside is
         * published as exactly zero.
         */
        private const val STICK_DEADZONE = 0.12f

        private const val LAYOUT_FILE = "touch_layout.json"
        private const val LAYOUT_VERSION = 1
    }

    /** Per-control persisted state. Positions are fractions of the view; size is a multiple. */
    data class ControlState(
        var visible: Boolean = true,
        var x: Float = 0f,
        var y: Float = 0f,
        var scale: Float = 1f,
    )

    /** The whole persisted layout, shared with [TouchSettingsActivity]. */
    class Layout {
        var enabled: Boolean = true
        val controls: MutableMap<String, ControlState> = LinkedHashMap()

        init { reset() }

        fun reset() {
            controls.clear()
            for (c in DEFAULTS) {
                controls[c.id] = ControlState(visible = true, x = c.defX, y = c.defY, scale = 1f)
            }
        }

        fun state(id: String): ControlState =
            controls.getOrPut(id) { ControlState(x = 0.5f, y = 0.5f) }

        fun toJson(): JSONObject {
            val j = JSONObject()
            j.put("version", LAYOUT_VERSION)
            j.put("enabled", enabled)
            val cs = JSONObject()
            for ((id, s) in controls) {
                cs.put(
                    id, JSONObject()
                        .put("visible", s.visible)
                        .put("x", s.x.toDouble())
                        .put("y", s.y.toDouble())
                        .put("scale", s.scale.toDouble())
                )
            }
            j.put("controls", cs)
            return j
        }

        companion object {
            fun fromJson(j: JSONObject): Layout {
                val l = Layout()
                l.enabled = j.optBoolean("enabled", true)
                val cs = j.optJSONObject("controls") ?: return l
                // Only ids this build knows about are taken. A layout written by a version
                // with a control this one does not have would otherwise become an unnamed
                // blob that is drawn nowhere and persisted forever.
                for (c in DEFAULTS) {
                    val o = cs.optJSONObject(c.id) ?: continue
                    val s = l.state(c.id)
                    s.visible = o.optBoolean("visible", true)
                    s.x = clamp01(o.optDouble("x", c.defX.toDouble()).toFloat())
                    s.y = clamp01(o.optDouble("y", c.defY.toDouble()).toFloat())
                    // 0.5..2.5: below half a control is not tappable and above two and a half
                    // the face buttons overlap each other, and a layout that can be saved into
                    // an unusable state is a layout that has to be reset by hand.
                    s.scale = min(2.5f, max(0.5f, o.optDouble("scale", 1.0).toFloat()))
                }
                return l
            }

            private fun clamp01(v: Float) = min(1f, max(0f, v))
        }
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    var layout: Layout = Layout()
        private set

    /** True while the player is dragging controls around instead of playing. */
    var editMode: Boolean = false
        set(value) {
            field = value
            if (!value) releaseAll()
            invalidate()
        }

    /** Set by GameActivity so the overlay can ask for the "Done" button without owning it. */
    var onEditModeChanged: ((Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val scratch = RectF()

    /** pointerId -> the control that pointer is driving. */
    private val active = HashMap<Int, String>()
    /** pointerId -> where it went down, for drag and pinch deltas. */
    private val downX = HashMap<Int, Float>()
    private val downY = HashMap<Int, Float>()
    /** The control selected in edit mode, for the banner and for GameActivity's size row. */
    var selected: String? = null
        private set

    // The decoded state, rebuilt from `active` on every event rather than incremented, because
    // a state assembled from increments has to be right about every event and one built from
    // the current set only has to be right about the set.
    private var buttons = 0
    private var leftTrigger = 0
    private var rightTrigger = 0
    private var thumbLX = 0
    private var thumbLY = 0
    private var thumbRX = 0
    private var thumbRY = 0

    private var lastPublished = ""
    private val density = resources.displayMetrics.density

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    fun load(context: Context) {
        val f = layoutFile(context)
        layout = if (f.isFile) {
            try {
                Layout.fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                // A layout that cannot be parsed is a layout that is replaced, not one that
                // stops the game from starting. The alternative is an app that will not boot
                // because a JSON file was truncated by a full disk.
                Layout()
            }
        } else {
            Layout()
        }
        // The native side owns "does touch contribute at all" and reads it at boot; this is
        // the in-session half of the same switch, and it is pushed every time the layout is
        // loaded so that a change made in the settings screen takes effect without a restart.
        NativeBridge.nativeTouchSetEnabled(layout.enabled)
        visibility = if (layout.enabled) VISIBLE else GONE
        invalidate()
    }

    fun save(context: Context) {
        try {
            layoutFile(context).writeText(layout.toJson().toString(2))
        } catch (e: Exception) {
            // Not fatal and not silent: a layout that did not save is a layout the player
            // rearranges twice.
            android.util.Log.w("CaseWest", "could not save the touch layout: ${e.message}")
        }
    }

    private fun layoutFile(context: Context) = File(context.filesDir, LAYOUT_FILE)

    // ---------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------

    private fun radiusPx(c: Control, s: ControlState): Float = c.defDp * density * s.scale * 0.5f

    private fun centrePx(c: Control, s: ControlState, out: FloatArray) {
        out[0] = s.x * width
        out[1] = s.y * height
    }

    private val centre = FloatArray(2)

    /** Remember where every finger is, by pointer id. */
    private fun recordPositions(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val xy = pos.getOrPut(id) { FloatArray(2) }
            xy[0] = event.getX(i)
            xy[1] = event.getY(i)
        }
    }

    /** Which control a point is on, or null. Larger controls are tested last so a small one
     *  that overlaps a big one wins — the player aimed at the small one. */
    private fun hitTest(x: Float, y: Float): Control? {
        var best: Control? = null
        var bestArea = Float.MAX_VALUE
        for (c in DEFAULTS) {
            val s = layout.controls[c.id] ?: continue
            if (!s.visible) continue
            centrePx(c, s, centre)
            val r = radiusPx(c, s)
            // A slightly generous radius on the hit test and not on the drawing: a control that
            // looks 66 dp wide and answers at 74 dp is easier to use and looks identical.
            val reach = r * 1.12f
            if (hypot(x - centre[0], y - centre[1]) <= reach) {
                val area = r * r
                if (area < bestArea) {
                    bestArea = area
                    best = c
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!layout.enabled) return false

        if (editMode) {
            handleEdit(event)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val c = hitTest(event.getX(idx), event.getY(idx)) ?: return false
                // Returning false above for an unclaimed DOWN is what lets the surface below
                // have the gesture. Once a control IS claimed, every later event for that
                // pointer arrives here whether or not it is still inside the control, which is
                // what makes a slide off a button still release it.
                active[id] = c.id
                downX[id] = event.getX(idx)
                downY[id] = event.getY(idx)
                recordPositions(event)
                if (c.kind == Kind.BUTTON || c.kind == Kind.TRIGGER) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                rebuild()
                publish()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (active.isEmpty()) return false
                recordPositions(event)
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (!active.containsKey(id)) continue
                    val c = controlById(active[id]!!) ?: continue
                    if (c.kind == Kind.STICK) {
                        // Re-read on every move: the stick is the one control whose value
                        // depends on where the finger is now rather than on whether it is down.
                        decodeStick(c, event.getX(i), event.getY(i))
                    }
                }
                rebuild()
                publish()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                active.remove(id)
                downX.remove(id)
                downY.remove(id)
                pos.remove(id)
                if (active.isEmpty()) {
                    releaseAll()
                } else {
                    rebuild()
                }
                publish()
                invalidate()
                return true
            }
        }
        return false
    }

    /** Edit mode: drag moves, pinch resizes, tap selects. */
    private fun handleEdit(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                downX[id] = event.getX(idx)
                downY[id] = event.getY(idx)
                // The first finger down selects what the drag and the pinch will act on. In
                // edit mode the overlay claims every touch, including empty screen, because
                // here — and only here — a tap that hits nothing is still a gesture about the
                // layout rather than a gesture for the game.
                selected = hitTest(event.getX(idx), event.getY(idx))?.id
                onEditModeChanged?.invoke(true)
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                downX[event.getPointerId(idx)] = event.getX(idx)
                downY[event.getPointerId(idx)] = event.getY(idx)
                if (event.pointerCount == 2) {
                    // Anchor the pinch to the distance the two pointers had when the second one
                    // landed, so a resize starts from the control's current size instead of
                    // jumping to whatever ratio two arbitrary finger positions happen to make.
                    pinchStart = hypot(
                        event.getX(0) - event.getX(1),
                        event.getY(0) - event.getY(1)
                    )
                    pinchScaleStart = selected?.let { layout.state(it).scale } ?: 1f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val target = selected?.let { controlById(it) } ?: return
                val s = layout.state(target.id)
                if (event.pointerCount >= 2) {
                    // Pinch: the distance between the first two pointers against the distance
                    // they had when the second one landed. Anchored to the second pointer's
                    // down position so the resize starts from 1.0 rather than jumping.
                    val d = hypot(
                        event.getX(0) - event.getX(1),
                        event.getY(0) - event.getY(1)
                    )
                    val d0 = pinchStart
                    if (d0 > 1f) {
                        s.scale = min(2.5f, max(0.5f, pinchScaleStart * (d / d0)))
                    }
                } else {
                    val id = event.getPointerId(0)
                    val dx = (event.getX(0) - (downX[id] ?: event.getX(0))) / max(1, width)
                    val dy = (event.getY(0) - (downY[id] ?: event.getY(0))) / max(1, height)
                    s.x = min(1f, max(0f, s.x + dx))
                    s.y = min(1f, max(0f, s.y + dy))
                    downX[id] = event.getX(0)
                    downY[id] = event.getY(0)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                active.clear()
                downX.clear()
                downY.clear()
                pos.clear()
                pinchStart = 0f
                invalidate()
            }
        }
    }

    private var pinchStart = 0f
    private var pinchScaleStart = 1f

    private fun controlById(id: String): Control? = DEFAULTS.firstOrNull { it.id == id }

    /** Rebuild the button and trigger words from the set of pointers that are down. */
    private fun rebuild() {
        buttons = 0
        leftTrigger = 0
        rightTrigger = 0
        // Sticks are NOT zeroed here: a stick's value is written by decodeStick on every move,
        // and a pointer that lifted has already been removed from `active`. Zeroing both from
        // one place is what keeps "released" and "centred" the same state.
        var stickL = false
        var stickR = false
        for (id in active.values) {
            val c = controlById(id) ?: continue
            when (c.kind) {
                Kind.BUTTON, Kind.DPAD -> buttons = buttons or c.bit
                Kind.TRIGGER -> if (c.id == "lt") leftTrigger = 255 else rightTrigger = 255
                Kind.STICK -> if (c.right) stickR = true else stickL = true
            }
        }
        if (!stickL) {
            thumbLX = 0
            thumbLY = 0
        }
        if (!stickR) {
            thumbRX = 0
            thumbRY = 0
        }
    }

    /**
     * The dpad is four buttons in one control, so it decodes from WHERE the finger is rather
     * than from whether it is down: the octant the touch falls in decides which of the four
     * bits is set, and a touch near the centre sets none. Diagonals are allowed — the title
     * reads a dpad as four independent bits, exactly as a real pad sends them.
     */
    private fun dpadBits(c: Control, x: Float, y: Float): Int {
        val s = layout.state(c.id)
        centrePx(c, s, centre)
        val dx = x - centre[0]
        val dy = y - centre[1]
        val r = radiusPx(c, s)
        if (hypot(dx, dy) < r * 0.25f) return 0
        var bits = 0
        if (dy < -r * 0.2f) bits = bits or XI_DPAD_UP
        if (dy > r * 0.2f) bits = bits or XI_DPAD_DOWN
        if (dx < -r * 0.2f) bits = bits or XI_DPAD_LEFT
        if (dx > r * 0.2f) bits = bits or XI_DPAD_RIGHT
        return bits
    }

    /**
     * A stick, decoded to XInput's range and sign convention.
     *
     * Proportional, not full-scale: this is the difference between a touch stick and the
     * keyboard, and it is why runtime/cpu/touch_input.cpp's drift rule zeroes the CONTROLLER's
     * sub-deadzone axes before the merge and never ours afterwards — a gentle walk is exactly
     * the sub-deadzone range that rule discards, and applying it to a touch value would delete
     * the ability to walk gently.
     */
    private fun decodeStick(c: Control, x: Float, y: Float) {
        val s = layout.state(c.id)
        centrePx(c, s, centre)
        val r = radiusPx(c, s)
        var dx = (x - centre[0]) / r
        var dy = (y - centre[1]) / r
        val mag = hypot(dx, dy)
        if (mag < STICK_DEADZONE) {
            if (c.right) {
                thumbRX = 0
                thumbRY = 0
            } else {
                thumbLX = 0
                thumbLY = 0
            }
            return
        }
        // Rescale so that the edge of the deadzone is 0 and the edge of the control is 1: a
        // stick that published raw magnitude would spend its first 12% of travel on nothing
        // and then jump, which feels like a stutter rather than like a deadzone.
        val scaled = min(1f, (mag - STICK_DEADZONE) / (1f - STICK_DEADZONE))
        dx = dx / mag * scaled
        dy = dy / mag * scaled
        val vx = (dx * 32767f).roundToInt()
        // Y FLIPPED: screen coordinates grow downward, XInput's thumb Y grows upward.
        val vy = (-dy * 32767f).roundToInt()
        if (c.right) {
            thumbRX = vx
            thumbRY = vy
        } else {
            thumbLX = vx
            thumbLY = vy
        }
    }

    /**
     * Send the decoded state to the runtime, and only when it changed.
     *
     * The native merge already declines to move the pad's packet number unless something
     * actually differs, so this guard is not what keeps the packet honest — it is what keeps a
     * finger sliding across a stick from making a JNI call per pixel of travel, which on a
     * 120 Hz panel is a thousand calls a second for a state that changes meaningfully a few
     * times a frame.
     */
    private fun publish() {
        // The dpad is decoded last and from the live pointer positions, because it is the one
        // button-like control whose value depends on where the finger is inside it.
        buttons = buttons and (XI_DPAD_UP or XI_DPAD_DOWN or XI_DPAD_LEFT or XI_DPAD_RIGHT).inv()
        for ((pointerId, controlId) in active) {
            val c = controlById(controlId) ?: continue
            if (c.kind != Kind.DPAD) continue
            val xy = pos[pointerId] ?: continue
            buttons = buttons or dpadBits(c, xy[0], xy[1])
        }

        val key = "$buttons|$leftTrigger|$rightTrigger|$thumbLX|$thumbLY|$thumbRX|$thumbRY"
        if (key == lastPublished) return
        lastPublished = key
        NativeBridge.nativeTouchPublish(
            buttons, leftTrigger, rightTrigger,
            thumbLX, thumbLY, thumbRX, thumbRY,
            active.size
        )
    }

    /**
     * pointerId -> where that finger last was, kept so [publish] can decode the dpad from the
     * current state of the world rather than from the event that happens to be in hand.
     *
     * Positions rather than the MotionEvent, and the reason is ownership: an event handed to a
     * listener is recycled the moment the listener returns, so retaining one means reading
     * somebody else's gesture later. Two floats per finger cost nothing and cannot be stale in
     * a way that is invisible.
     */
    private val pos = HashMap<Int, FloatArray>()

    /** Drop everything to zero. Called on pause, on hide and when leaving edit mode. */
    fun releaseAll() {
        active.clear()
        downX.clear()
        downY.clear()
        pos.clear()
        buttons = 0
        leftTrigger = 0
        rightTrigger = 0
        thumbLX = 0
        thumbLY = 0
        thumbRX = 0
        thumbRY = 0
        lastPublished = ""
        // Both the zero snapshot and the explicit clear, because they answer different
        // questions: the snapshot says "the overlay is idle now" to the merge, and the clear
        // is the guarantee runtime/cpu/touch_input.h documents — nothing may stay held across
        // a pause, a lost surface or a switch off, because the title POLLS a state and never
        // sees the event that would have told it a finger left.
        NativeBridge.nativeTouchPublish(0, 0, 0, 0, 0, 0, 0, 0)
        NativeBridge.nativeTouchClear()
        invalidate()
    }

    // ---------------------------------------------------------------------------------------
    // Entering edit mode
    // ---------------------------------------------------------------------------------------
    //
    // GameActivity's button, and NOT a long-press on the overlay. A long-press gesture here
    // would have to be recognised from a touch the overlay did not claim — because a claimed
    // one is a held button, and turning a held RT into a drag halfway through is worse than
    // anything edit mode does — and claiming unclaimed touches to watch them for a long press
    // is exactly the screen-swallowing the pass-through rule above exists to avoid. A visible
    // button is also discoverable, which a gesture on a transparent layer is not.
    //
    // [onEditModeChanged] is how the button's own label and the Done/Reset row follow the
    // state, so there is one answer to "are we editing" and two views of it.

    // ---------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (!layout.enabled) return
        val dim = if (editMode) 0.62f else 0.40f
        for (c in DEFAULTS) {
            val s = layout.controls[c.id] ?: continue
            if (!s.visible) continue
            centrePx(c, s, centre)
            val r = radiusPx(c, s)
            val cx = centre[0]
            val cy = centre[1]
            val isSelected = editMode && selected == c.id
            val held = active.containsValue(c.id)

            when (c.kind) {
                Kind.STICK -> drawStick(canvas, c, cx, cy, r, dim, held, isSelected)
                Kind.DPAD -> drawDpad(canvas, cx, cy, r, dim, isSelected)
                else -> drawButton(canvas, c, cx, cy, r, dim, held, isSelected)
            }
        }
        if (editMode) drawEditBanner(canvas)
    }

    private fun fillCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, colour: Int) {
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawCircle(cx, cy, r, paint)
    }

    private fun strokeCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, colour: Int, w: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        paint.color = colour
        canvas.drawCircle(cx, cy, r, paint)
    }

    private fun drawButton(
        canvas: Canvas, c: Control, cx: Float, cy: Float, r: Float,
        dim: Float, held: Boolean, selected: Boolean,
    ) {
        fillCircle(canvas, cx, cy, r, if (held) argb(0.72f * dim * 2f) else argb(dim * 0.55f))
        strokeCircle(canvas, cx, cy, r, argb(dim + 0.25f), 2f * density)
        if (selected) strokeCircle(canvas, cx, cy, r + 4f * density, Color.WHITE, 2f * density)
        textPaint.textSize = r * 0.62f
        textPaint.color = if (held) Color.WHITE else argb(0.95f)
        canvas.drawText(c.label, cx, cy + textPaint.textSize * 0.35f, textPaint)
    }

    private fun drawStick(
        canvas: Canvas, c: Control, cx: Float, cy: Float, r: Float,
        dim: Float, held: Boolean, selected: Boolean,
    ) {
        fillCircle(canvas, cx, cy, r, argb(dim * 0.42f))
        strokeCircle(canvas, cx, cy, r, argb(dim + 0.2f), 2f * density)
        if (selected) strokeCircle(canvas, cx, cy, r + 4f * density, Color.WHITE, 2f * density)
        // The knob sits where the finger is, which is the only feedback a touch stick can give
        // that a physical one cannot: there is no travel to feel, so the drawn travel has to be
        // exact or the player has no idea what they are holding.
        var kx = cx
        var ky = cy
        if (held) {
            val vx = if (c.right) thumbRX else thumbLX
            val vy = if (c.right) thumbRY else thumbLY
            kx = cx + vx / 32767f * r * 0.72f
            // Undo the publish-time flip for drawing: the knob follows the finger on screen,
            // which means DOWN on screen, which is negative Y in XInput's convention.
            ky = cy - vy / 32767f * r * 0.72f
        }
        fillCircle(canvas, kx, ky, r * 0.42f, argb(dim + 0.35f))
        textPaint.textSize = r * 0.26f
        textPaint.color = argb(0.9f)
        canvas.drawText(c.label, cx, cy - r * 0.78f, textPaint)
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, r: Float, dim: Float, selected: Boolean) {
        val arm = r * 0.34f
        paint.style = Paint.Style.FILL
        paint.color = argb(dim * 0.6f)
        // A cross rather than four circles: the four directions are one control and drawing
        // them as one says so, which is what makes the diagonals readable as available.
        scratch.set(cx - arm, cy - r, cx + arm, cy + r)
        canvas.drawRoundRect(scratch, arm * 0.5f, arm * 0.5f, paint)
        scratch.set(cx - r, cy - arm, cx + r, cy + arm)
        canvas.drawRoundRect(scratch, arm * 0.5f, arm * 0.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = argb(dim + 0.2f)
        canvas.drawRoundRect(RectF(cx - r, cy - arm, cx + r, cy + arm), arm * 0.5f, arm * 0.5f, paint)
        if (selected) strokeCircle(canvas, cx, cy, r + 4f * density, Color.WHITE, 2f * density)
        textPaint.textSize = r * 0.30f
        textPaint.color = argb(0.9f)
        canvas.drawText("\u25B2", cx, cy - r * 0.42f, textPaint)
        canvas.drawText("\u25BC", cx, cy + r * 0.66f, textPaint)
        canvas.drawText("\u25C0", cx - r * 0.62f, cy + r * 0.12f, textPaint)
        canvas.drawText("\u25B6", cx + r * 0.62f, cy + r * 0.12f, textPaint)
    }

    private fun drawEditBanner(canvas: Canvas) {
        val msg = selected?.let { id ->
            val c = controlById(id)
            val s = layout.state(id)
            "EDITING  ${c?.label ?: id}   ${(s.scale * 100).roundToInt()}%   " +
                "drag to move, pinch to resize, tap another to select"
        } ?: "EDITING  tap a control to select it, drag to move, pinch to resize"
        textPaint.textSize = 13f * density
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT
        val pad = 10f * density
        paint.style = Paint.Style.FILL
        paint.color = argb(0.7f)
        val w = textPaint.measureText(msg)
        canvas.drawRect(pad, pad, pad + w + pad * 2f, pad + textPaint.textSize + pad * 1.6f, paint)
        canvas.drawText(msg, pad * 2f, pad + textPaint.textSize + pad * 0.4f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** A white with a given alpha, clamped — the dim factor and the held boost can exceed 1. */
    private fun argb(a: Float): Int =
        Color.argb((min(1f, max(0f, a)) * 255).roundToInt(), 255, 255, 255)

    /** Resize the control selected in edit mode, from the settings row rather than a pinch. */
    fun scaleSelected(context: Context, factor: Float) {
        val id = selected ?: return
        layout.state(id).scale = min(2.5f, max(0.5f, factor))
        save(context)
        invalidate()
    }

    /** A one-line summary for the launcher's diagnostics row. */
    fun describe(): String {
        val visible = DEFAULTS.count { layout.controls[it.id]?.visible == true }
        return "touch ${if (layout.enabled) "ON" else "OFF"}, $visible/${DEFAULTS.size} controls " +
            "visible, ${if (editMode) "edit mode" else "play mode"}"
    }

    /** The controls that exist, for the settings screen's list. */
    fun controls(): List<Control> = DEFAULTS

    init {
        isClickable = true
        isFocusable = false
        // Transparent to drawing but still able to receive touches: a View with no background
        // does, as long as it is clickable, which is what the two lines above are for.
        setBackgroundColor(Color.TRANSPARENT)
        // A check on the DEFAULTS table rather than on the player's file, and it runs at
        // construction so the failure is immediate: a BUTTON with no bit is a control that
        // draws, feels pressed and does nothing, which on a phone is a defect with no log line.
        for (c in DEFAULTS) {
            if (c.kind == Kind.BUTTON && c.bit == 0) {
                throw IllegalStateException("control ${c.id} is a BUTTON with no XInput bit")
            }
        }
    }
}
