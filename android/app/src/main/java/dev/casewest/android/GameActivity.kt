package dev.casewest.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import org.libsdl.app.SDLActivity

/**
 * The game: an [SDLActivity] with this port's four additions to it.
 *
 * SDL's Activity owns the surface, the input plumbing and the thread that runs SDL_main.
 * Everything below is what this port needs on top of that, and each of the four exists because
 * SDL cannot know about it:
 *
 *   1. THE ENVIRONMENT, exported before the library is loaded. Every setting this app has
 *      reaches the runtime as an environment variable (see [RuntimeConfig]), and the only
 *      point in SDL's lifecycle guaranteed to precede the load is [loadLibraries] — which is
 *      why that override exists and why it is not done in onCreate after super.
 *   2. THE TOUCH OVERLAY, added to SDL's own layout above the surface. It claims touches that
 *      land on a control and passes every other touch through to the game, which is the
 *      contract in [TouchOverlayView].
 *   3. THE FIRST-RUN PROGRESS, because SDL's Android video driver owns exactly one surface:
 *      window.cpp's Host_ProgressBegin forwards here instead of creating a second window, and
 *      these are the [onNativeProgressBegin] / [onNativeProgress] / [onNativeProgressEnd]
 *      callbacks the native side looks up by name on this Activity's class.
 *   4. RUMBLE, the one native→Java call that exists because the device is on the other side of
 *      the language boundary: window.cpp's IssueRumble has a pad's motors everywhere else and
 *      a phone's vibrator here.
 *
 * The four callbacks at the bottom are looked up by JNI name and signature —
 * `onNativeRumble(II)V`, `onNativeProgressBegin(Ljava/lang/String;)Z`,
 * `onNativeProgress(Ljava/lang/String;F)V`, `onNativeProgressEnd()V` — on whatever object was
 * handed to [NativeBridge.nativeSetActivity]. Renaming one is not a compile error anywhere; it
 * is a line in the boot log saying the callback could not be found, which is why they are
 * listed here rather than inherited from something.
 */
class GameActivity : SDLActivity() {

    private var overlay: TouchOverlayView? = null
    private var progressBox: LinearLayout? = null
    private var progressTitle: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressLabel: TextView? = null
    private var toolbar: LinearLayout? = null
    private var editButton: Button? = null

    private var vibrator: Vibrator? = null
    private var powerManager: PowerManager? = null
    private var thermalCallback: PowerManager.OnThermalStatusChangedListener? = null

    // ---------------------------------------------------------------------------------------
    // 1. The library, and the environment it has to be loaded into
    // ---------------------------------------------------------------------------------------

    /**
     * The one shared library this app contains.
     *
     * SDL's default list is {"SDL2", "main"} and both halves of it are wrong here: libSDL2 is
     * linked STATICALLY into libcw_runtime.so rather than shipped beside it, and there is no
     * libmain.so because the runtime is not built as an executable on Android. getLibraries()
     * also decides which library SDLMain dlopens and calls SDL_main in — it takes the LAST
     * entry and wraps it as "lib" + name + ".so" — so this list is not cosmetic. It has to say
     * "cw_runtime", which is the same string runtime/CMakeLists.txt names its target and that
     * window.cpp's CW_ANDROID_SELF_LIB spells with the platform's prefix and suffix attached.
     */
    override fun getLibraries(): Array<String> = arrayOf("cw_runtime")

    /**
     * Export the configuration, then load. In that order, and the order is the whole reason
     * this override exists: SDLActivity.onCreate calls loadLibraries() before it does anything
     * else, and the runtime reads its configuration with getenv from SDL_main — so this is the
     * last moment at which a variable can be written and still be seen. Writing one later is
     * not late, it is invisible: several of them are read once behind a std::once_flag.
     *
     * Loading "cw_runtime" and not SDL's list is the other half: there is one library, and it
     * carries SDL, ffmpeg's decoder, the recompiled image and this port's runtime.
     */
    override fun loadLibraries() {
        // SEED BEFORE EXPORT, and the order is load-bearing rather than tidy. host_paths.cpp
        // resolves CW_ROOT by checking that the directory EXISTS — `std::filesystem::is_directory`
        // — and a CW_ROOT naming a directory that has not been created yet is ignored with a
        // line in the log, after which every path in the runtime resolves against a read-only
        // fallback. So the root is created and seeded first, then named.
        try {
            val summary = GameFiles.seed(this)
            Log.i(TAG, summary)
        } catch (e: Exception) {
            Log.e(TAG, "seeding the data root failed: ${e.message}")
        }

        RuntimeConfig.load(this).export(this)

        System.loadLibrary("cw_runtime")
    }

    /**
     * Arguments for SDL_main, which arrive after argv[0].
     *
     * Empty in normal play. The extra exists so that a device or an emulator can be asked to
     * run the runtime's own gates without rebuilding the app:
     *
     *     adb shell am start -n dev.casewest.android/.GameActivity --esa cwArguments --smoke
     *
     * which is how .github/workflows/android.yml proves the artifact BOOTS and not merely that
     * it compiled — the same gate the desktop workflow runs as `cw_runtime --smoke`, on a
     * platform where there is no command line to run it from.
     */
    override fun getArguments(): Array<String> =
        intent?.getStringArrayExtra("cwArguments") ?: emptyArray()

    // ---------------------------------------------------------------------------------------
    // The Activity
    // ---------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SDL's layout: a RelativeLayout holding the surface. Protected and static in
        // SDLActivity, which is how the overlay gets to sit ABOVE the surface in the same view
        // hierarchy — later children draw on top, and a touch the overlay declines falls
        // through to the surface below it.
        val layout = mLayout
        if (layout == null) {
            Log.e(TAG, "SDLActivity created no layout — the surface is missing and nothing " +
                "below can be added. This is an SDL failure, not a configuration one.")
            return
        }

        vibrator = obtainVibrator()
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

        // Hand the native side this Activity so its four callbacks resolve. After
        // super.onCreate, because that is where the library was loaded.
        NativeBridge.nativeSetActivity(this)

        addProgressUi(layout)
        addOverlay(layout)
        addToolbar(layout)

        registerThermalListener()

        Log.i(
            TAG,
            "GameActivity ready: root=${GameFiles.root(this)} state=${GameFiles.state(this)} " +
                "args=${getArguments().joinToString(",")}"
        )
    }

    private fun addOverlay(layout: android.view.ViewGroup) {
        val view = TouchOverlayView(this)
        view.load(this)
        overlay = view
        layout.addView(
            view,
            RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        )
        view.onEditModeChanged = { editing -> updateToolbar(editing) }
        updateToolbar(false)
    }

    private fun addProgressUi(layout: android.view.ViewGroup) {
        // The first-run unpack of a 1.2 GB container is the longest wait in this app and it
        // happens before a single frame exists, so it gets a real UI rather than a spinner in
        // a corner: a dimmed box, a title, a bar and the label the runtime sends.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = ColorDrawable(Color.argb(225, 12, 12, 16))
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }
        val label = TextView(this).apply {
            setTextColor(Color.argb(255, 205, 205, 210))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(bar, LinearLayout.LayoutParams(dp(360), dp(10)).apply {
            topMargin = dp(14)
        })
        box.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        progressBox = box
        progressTitle = title
        progressBar = bar
        progressLabel = label

        val params = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.CENTER_IN_PARENT)
        }
        layout.addView(box, params)
    }

    private fun addToolbar(layout: android.view.ViewGroup) {
        // Two small buttons in the top-right, and they are small on purpose: anything larger is
        // screen the game does not have. "Controls" toggles edit mode; "Touch" opens the
        // settings screen where visibility, size, the master disable and the reset live.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            alpha = 0.55f
        }
        val edit = Button(this).apply {
            text = "Controls"
            textSize = 11f
            setPadding(dp(10), 0, dp(10), 0)
            minimumWidth = 0
            minWidth = 0
            setOnClickListener {
                val view = overlay ?: return@setOnClickListener
                view.editMode = !view.editMode
                updateToolbar(view.editMode)
            }
        }
        val done = Button(this).apply {
            text = "Done"
            textSize = 11f
            setPadding(dp(10), 0, dp(10), 0)
            minimumWidth = 0
            minWidth = 0
            visibility = View.GONE
            setOnClickListener {
                val view = overlay ?: return@setOnClickListener
                view.editMode = false
                view.save(this@GameActivity)
                updateToolbar(false)
                Toast.makeText(this@GameActivity, "Touch layout saved", Toast.LENGTH_SHORT).show()
            }
        }
        val settings = Button(this).apply {
            text = "Touch"
            textSize = 11f
            setPadding(dp(10), 0, dp(10), 0)
            minimumWidth = 0
            minWidth = 0
            setOnClickListener {
                startActivity(android.content.Intent(this@GameActivity, TouchSettingsActivity::class.java))
            }
        }
        bar.addView(edit)
        bar.addView(done)
        bar.addView(settings)
        toolbar = bar
        editButton = edit

        val params = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_END)
            topMargin = dp(4)
            marginEnd = dp(4)
        }
        layout.addView(bar, params)
    }

    /** Keep the two buttons of the edit affordance in step with the overlay's mode. */
    private fun updateToolbar(editing: Boolean) {
        runOnUiThread {
            editButton?.visibility = if (editing) View.GONE else View.VISIBLE
            toolbar?.getChildAt(1)?.visibility = if (editing) View.VISIBLE else View.GONE
            toolbar?.alpha = if (editing) 0.95f else 0.55f
            val view = overlay ?: return@runOnUiThread
            toolbar?.visibility = if (view.layout.enabled) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // The layout may have changed in the settings screen while this Activity was stopped,
        // and the native side's in-session switch has to follow it.
        overlay?.load(this)
        updateToolbar(false)
        registerThermalListener()
        pushThermalStatus()
    }

    override fun onPause() {
        super.onPause()
        // Nothing may stay held across a pause. The title polls a state and never sees the
        // event that would tell it a finger left, so a call taken mid-fight is a fight the
        // player returns to with the trigger down.
        overlay?.releaseAll()
        unregisterThermalListener()
    }

    override fun onDestroy() {
        overlay?.let {
            it.releaseAll()
            if (it.editMode) it.save(this)
        }
        // Drop the global reference the native side took in onCreate. Not optional: an
        // Activity that is finished but still referenced keeps its window alive.
        NativeBridge.nativeClearActivity()
        unregisterThermalListener()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()

    // ---------------------------------------------------------------------------------------
    // Thermal
    // ---------------------------------------------------------------------------------------

    /**
     * Feed the OS's thermal status to the governor.
     *
     * API 29 and up, because PowerManager's thermal API is API 29; below that the governor's
     * thermal arm simply never fires and its frame-rate arm still does, which is the correct
     * degradation — a phone that cannot report heat is a phone that has to be governed by what
     * it achieves rather than by how it feels.
     */
    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < 29 || thermalCallback != null) return
        val pm = powerManager ?: return
        val cb = PowerManager.OnThermalStatusChangedListener { status ->
            Log.i(TAG, "thermal status -> $status")
            NativeBridge.nativeSetThermalStatus(status)
        }
        try {
            pm.addThermalStatusListener(mainExecutor, cb)
            thermalCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "no thermal listener: ${e.message}")
        }
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = powerManager ?: return
        thermalCallback?.let {
            try {
                pm.removeThermalStatusListener(it)
            } catch (e: Exception) {
                Log.w(TAG, "removing the thermal listener: ${e.message}")
            }
        }
        thermalCallback = null
    }

    /** Ask once at resume, so a device that got hot while backgrounded does not report cold. */
    private fun pushThermalStatus() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = powerManager ?: return
        try {
            NativeBridge.nativeSetThermalStatus(pm.currentThermalStatus)
        } catch (e: Exception) {
            Log.w(TAG, "reading the thermal status: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // 4. Rumble: native -> Java
    // ---------------------------------------------------------------------------------------

    /**
     * The title's two motor speeds, as the phone's one vibrator.
     *
     * A phone has no second motor and no amplitude ramp worth modelling, so the pair is reduced
     * to the louder of the two and played as a short one-shot. The duration is 260 ms against
     * window.cpp's 250 ms refresh: a rumble the title holds arrives again before the previous
     * one has finished, so a held rumble reads as continuous and a tapped one reads as a tap.
     * (0,0) cancels rather than playing nothing, because the guest's own "stop" is a pair of
     * zeroes and a vibration left running past it is a phone that buzzes after the explosion.
     *
     * Called on a native thread; the vibrator is thread-safe and this does no view work.
     */
    fun onNativeRumble(leftMotor: Int, rightMotor: Int) {
        val v = vibrator ?: return
        if (leftMotor == 0 && rightMotor == 0) {
            try {
                v.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "cancelling vibration: ${e.message}")
            }
            return
        }
        // XInput's motor speeds are 0..65535; VibrationEffect's amplitude is 1..255 with
        // DEFAULT_AMPLITUDE (-1) meaning "let the device decide". Mapping to 1..255 rather
        // than to DEFAULT keeps a light rumble light, which is the only part of the effect a
        // phone can actually vary.
        val level = maxOf(leftMotor, rightMotor).coerceIn(0, 65535)
        val amplitude = (1 + level * 254 / 65535).coerceIn(1, 255)
        try {
            v.vibrate(VibrationEffect.createOneShot(260, amplitude))
        } catch (e: Exception) {
            // A device with no vibrator, or one whose HAL refuses the effect. Neither is worth
            // a crash in a game that is otherwise running.
            Log.w(TAG, "vibration refused: ${e.message}")
            vibrator = null
        }
    }

    private fun obtainVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() }
    } catch (e: Exception) {
        Log.w(TAG, "no vibrator: ${e.message}")
        null
    }

    // ---------------------------------------------------------------------------------------
    // 3. First-run progress: native -> Java
    // ---------------------------------------------------------------------------------------

    /**
     * A long native operation is starting, and this Activity is the only place it can be
     * reported.
     *
     * Returning true is a promise: window.cpp will send [onNativeProgress] updates and one
     * [onNativeProgressEnd], and it will NOT create an SDL window of its own. Returning false
     * makes it fall back to console lines — the work still runs, it is just invisible, which is
     * the honest answer if this Activity's UI could not be built. SDL's Android video driver
     * owns exactly one surface, so a second window is not available to it in any case.
     */
    fun onNativeProgressBegin(title: String?): Boolean {
        val box = progressBox ?: return false
        runOnUiThread {
            progressTitle?.text = title ?: "Working"
            progressBar?.isIndeterminate = true
            progressLabel?.text = "Starting…"
            box.visibility = View.VISIBLE
        }
        return true
    }

    /**
     * [fraction] is 0..1, or negative when the native side does not know how far along it is —
     * which it does not, for parts of the unpack, and an indeterminate bar that says "we do not
     * know" is more useful than a bar pinned at zero that says "we are stuck".
     */
    fun onNativeProgress(label: String?, fraction: Float) {
        runOnUiThread {
            progressLabel?.text = label ?: ""
            val bar = progressBar ?: return@runOnUiThread
            if (fraction < 0f) {
                bar.isIndeterminate = true
            } else {
                bar.isIndeterminate = false
                bar.progress = (fraction.coerceIn(0f, 1f) * 1000f).toInt()
            }
        }
    }

    fun onNativeProgressEnd() {
        runOnUiThread { progressBox?.visibility = View.GONE }
    }

    private companion object {
        const val TAG = "CaseWest"
    }
}
