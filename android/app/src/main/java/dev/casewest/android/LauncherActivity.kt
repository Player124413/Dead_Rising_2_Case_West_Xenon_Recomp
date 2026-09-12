package dev.casewest.android

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.DragEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The launcher: the screen that decides what the game will be given before the game exists.
 *
 * It is a separate Activity from [GameActivity] and not a menu inside it, for a reason that is
 * structural rather than aesthetic — SDLActivity's main thread does not return from
 * nativeRunMain until the runtime exits, so any UI that has to be usable BEFORE a boot cannot
 * live in the Activity that boots. That is also why this screen writes [RuntimeConfig] and does
 * not touch the environment itself: exporting has to happen in the process and at the moment
 * the library loads, which is GameActivity.loadLibraries.
 *
 * WHAT IT IS FOR, in the order the sections appear:
 *
 *   1. the game file. Nothing here works without it and this build cannot supply it, so the
 *      first thing on the screen is the drop target and the honest statement of what a package
 *      is. Both halves of what a player actually has are accepted — the container file, or the
 *      whole 58410B00 folder — because that is what is on a console's hard drive.
 *   2. the GPU driver. Importing a Turnip build is not an enthusiast extra on this title: the
 *      renderer requires Vulkan 1.3, and a phone whose platform loader predates 1.3 cannot get
 *      there from its own driver. See [DriverManager] for the whole argument.
 *   3. performance. One row of it — the resolution — is a trap worth labelling, because pinning
 *      a resolution disables the governor that makes a mid-range phone playable.
 *   4. touch. Enabled, arranged and sized from [TouchSettingsActivity]; switched off here.
 *
 * Everything on this screen persists immediately. There is no Apply button, because a settings
 * screen whose changes are lost by a back press is a settings screen that gets reported as
 * broken by the player who pressed back.
 */
class LauncherActivity : Activity() {

    private var config = RuntimeConfig()

    private lateinit var gameStatus: TextView
    private lateinit var playStatus: TextView
    private lateinit var play: Button
    private lateinit var driverStatus: TextView
    private lateinit var driverList: LinearLayout
    private lateinit var driverHelp: TextView
    private lateinit var diagnostics: TextView
    private lateinit var dropZone: View

    private val ui = Handler(Looper.getMainLooper())

    /** Set while a long copy is running, so a second one cannot start under the first. */
    private var busy = false

    // Declared BEFORE the three pickers below, which register themselves in it: Kotlin runs
    // property initialisers in declaration order, and a picker built before the list exists is
    // a picker that can never be matched to its result.
    private val pickers = mutableListOf<Picker>()
    private var nextRequestCode = 100

    private val pickGameFile = registerForPick(arrayOf("*/*")) { uri ->
        if (uri != null) importGame(listOf(uri))
    }
    private val pickGameFolder = registerForPick(null) { uri ->
        if (uri != null) importGame(listOf(uri))
    }
    private val pickDriver = registerForPick(arrayOf("*/*")) { uri ->
        if (uri != null) importDriver(uri)
    }

    // ---------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        config = RuntimeConfig.load(this)

        gameStatus = findViewById(R.id.gameStatus)
        playStatus = findViewById(R.id.playStatus)
        play = findViewById(R.id.play)
        driverStatus = findViewById(R.id.driverStatus)
        driverList = findViewById(R.id.driverList)
        driverHelp = findViewById(R.id.driverHelp)
        diagnostics = findViewById(R.id.diagnostics)
        dropZone = findViewById(R.id.dropZone)

        findViewById<TextView>(R.id.subtitle).text = buildString {
            append("version ").append(versionName())
            append("  ·  arm64-v8a  ·  Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        }

        findViewById<Button>(R.id.importGame).setOnClickListener { pickGameFile.launch() }
        findViewById<Button>(R.id.importFolder).setOnClickListener { pickGameFolder.launch() }
        findViewById<Button>(R.id.importDriver).setOnClickListener { pickDriver.launch() }
        findViewById<Button>(R.id.probeDriver).setOnClickListener { probe() }
        findViewById<Button>(R.id.touchSettings).setOnClickListener {
            startActivity(Intent(this, TouchSettingsActivity::class.java))
        }
        play.setOnClickListener { launchGame() }

        wireSpinners()
        wireSwitches()
        wireDropZone()

        driverHelp.text = if (BuildConfig.HAVE_ADRENOTOOLS) {
            "A driver package is third-party native code that runs inside this app: nothing here " +
                "can inspect what it does, and importing one is a decision about a file you " +
                "chose. Mesa's Turnip builds for Adreno are what this row is for — the renderer " +
                "needs Vulkan 1.3 and BC-texture support, which Turnip provides on devices " +
                "whose own driver does not."
        } else {
            "This build was compiled WITHOUT libadrenotools, so custom drivers cannot be loaded " +
                "and the device's own Vulkan driver is used. Build with " +
                "tools/android/build_adrenotools.sh to get this row."
        }
    }

    override fun onResume() {
        super.onResume()
        config = RuntimeConfig.load(this)
        // A saved driver whose files were deleted — an uninstall of the files, a cleared cache,
        // a failed import — is dropped here rather than exported at boot, because exporting it
        // produces twenty lines of adrenotools failures where the honest state is "no driver".
        config = DriverManager.pruneMissing(this, config)
        refresh()
        // Ask again on every resume: the player may have changed the driver since the last
        // answer, and a diagnostics panel showing a stale probe is worse than an empty one.
        if (ProbeService.resultFile(this).exists()) showProbeResult()
    }

    // ---------------------------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------------------------

    private fun refresh() {
        val pkg = GameFiles.findPackage(this)
        val state = GameFiles.state(this)
        val free = GameFiles.freeBytes(this) / (1024 * 1024)

        gameStatus.text = when (state) {
            GameFiles.State.NO_PACKAGE ->
                "No game package. Import the file your Xbox 360 downloaded."
            GameFiles.State.NO_GAME ->
                "Package found: ${pkg?.name} (${pkg?.length()?.let { it / (1024 * 1024) } ?: 0} MB). " +
                    "It will be unpacked on first launch — that is the long wait, and it happens " +
                    "once."
            GameFiles.State.NO_SHADER_CACHE ->
                "Package found and unpacked. No shader cache yet: the first run translates the " +
                    "title's shaders, which needs a DXC library or a cache built on a desktop " +
                    "(see DIAGNOSTICS)."
            GameFiles.State.READY ->
                "Ready. ${pkg?.name}, shader cache present, ${free} MB free."
        }

        // The driver rows, rebuilt rather than diffed: the list is short and a row that survives
        // a delete is a row the player will press again.
        driverList.removeAllViews()
        val drivers = DriverManager.list(this)
        driverStatus.text = when {
            !BuildConfig.HAVE_ADRENOTOOLS -> "Custom drivers unavailable in this build."
            drivers.isEmpty() -> "No imported drivers. Using the device's own Vulkan driver."
            else -> "${drivers.size} driver(s) imported. " +
                (config.driver?.let { "Selected: ${it.label}" } ?: "Selected: the device's own driver.")
        }
        if (BuildConfig.HAVE_ADRENOTOOLS) {
            addDriverRow(null, config.driver == null)
            for (d in drivers) addDriverRow(d, config.driver?.name == d.libraryName &&
                config.driver?.dir == d.dir.absolutePath)
        }

        play.isEnabled = !busy
        playStatus.text = when {
            busy -> "Working…"
            state == GameFiles.State.NO_PACKAGE ->
                "Play is available once a package is imported. Everything else on this screen " +
                    "can be set up first."
            state == GameFiles.State.NO_SHADER_CACHE ->
                "Playable, with a warning: no shader cache means the first run either " +
                    "translates every shader (slow, needs DXC) or shows a black screen. Import a " +
                    "cache built with tools/build_shader_spv.sh if you have one."
            else -> "First launch unpacks the package and builds what is missing. That is a " +
                "one-time wait measured in minutes."
        }

        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val cache = shaderCacheSummary()
        val dxc = if (config.dxcLib != null) config.dxcLib else "not imported"
        val base = buildString {
            append("data root      ").append(GameFiles.root(this).absolutePath).append('\n')
            append("package        ")
            append(GameFiles.findPackage(this)?.let { "${it.name} (${it.length() / (1024 * 1024)} MB)" }
                ?: "none").append('\n')
            append("shader cache   ").append(cache).append('\n')
            append("dxc library    ").append(dxc).append('\n')
            append("adrenotools    ")
            append(if (BuildConfig.HAVE_ADRENOTOOLS) "linked in" else "NOT in this build").append('\n')
            append("driver         ")
            append(config.driver?.label ?: "system").append('\n')
            append("touch          ")
            append(if (config.touchEnabled) "on" else "off").append('\n')
            append("governor       ")
            append(if (config.autoScale) "auto-scale" else "fixed")
            append(if (config.thermal) " + thermal" else "")
            .append(if (config.pinBigCores) " + big-core pin" else "").append('\n')
            append('\n')
        }
        val probe = ProbeService.resultFile(this)
        diagnostics.text = base + if (probe.exists()) {
            try {
                probe.readText()
            } catch (e: Exception) {
                "probe result unreadable: ${e.message}"
            }
        } else {
            "No driver probe yet. \"Test driver\" asks the runtime which Vulkan driver it would " +
                "load, in a separate process, so the answer cannot decide the game's driver."
        }
    }

    private fun shaderCacheSummary(): String {
        val dir = GameFiles.shaderCache(this)
        if (!dir.isDirectory) return "absent"
        val files = dir.listFiles() ?: return "absent"
        if (files.isEmpty()) return "empty"
        val bytes = files.sumOf { it.length() }
        return "${files.size} file(s), ${bytes / (1024 * 1024)} MB"
    }

    /** One radio row per driver, plus the "device's own driver" row that is always there. */
    private fun addDriverRow(driver: DriverManager.Installed?, selected: Boolean) {
        val button = RadioButton(this).apply {
            text = driver?.describe() ?: "The device's own Vulkan driver"
            isChecked = selected
            setPadding(0, dp(6), 0, dp(6))
            textSize = 13f
            isEnabled = driver?.supportedHere != false
            setOnClickListener {
                config = config.copy(
                    driver = driver?.toChoice().also {
                        if (driver != null && it == null) {
                            toast("That driver's library is missing from its directory.")
                        }
                    }
                )
                RuntimeConfig.save(this@LauncherActivity, config)
                refresh()
            }
        }
        driverList.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        if (driver != null) {
            val delete = Button(this).apply {
                text = "Delete ${driver.label}"
                textSize = 11f
                setPadding(dp(8), 0, dp(8), 0)
                minimumWidth = 0
                minWidth = 0
                setOnClickListener {
                    if (config.driver?.dir == driver.dir.absolutePath) {
                        config = config.copy(driver = null)
                        RuntimeConfig.save(this@LauncherActivity, config)
                    }
                    DriverManager.delete(this@LauncherActivity, driver)
                    refresh()
                }
            }
            driverList.addView(delete, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Spinners and switches
    // ---------------------------------------------------------------------------------------

    /**
     * The resolution options, and why they are these.
     *
     * The renderer validates an internal resolution (even width, height 720..2880, at least
     * 16:10) and refuses one it cannot produce with a line in the log, so a spinner full of
     * arbitrary numbers would be a spinner full of refusals. These are the four the store
     * accepts on a 16:9 phone plus AUTO, which is the answer the governor wants.
     */
    private val resOptions = listOf("AUTO (governor decides)", "1920x1080", "1600x900", "1280x720", "2560x1440")
    private val msaaOptions = listOf("2x (default)", "4x", "1x (single sample)")
    private val fpsOptions = listOf("Derived from the cap (default)", "30", "60", "90", "120")

    private fun wireSpinners() {
        val res = findViewById<Spinner>(R.id.resSpinner)
        val msaa = findViewById<Spinner>(R.id.msaaSpinner)
        val fps = findViewById<Spinner>(R.id.fpsSpinner)
        fill(res, resOptions)
        fill(msaa, msaaOptions)
        fill(fps, fpsOptions)

        res.setSelection(resIndexOf(config.internalRes))
        msaa.setSelection(msaaIndexOf(config.msaa))
        fps.setSelection(fpsIndexOf(config.fpsCap))

        res.onPick { i ->
            config = config.copy(internalRes = if (i == 0) null else resOptions[i])
            save()
        }
        msaa.onPick { i ->
            config = config.copy(msaa = listOf(2, 4, 1)[i])
            save()
        }
        fps.onPick { i ->
            config = config.copy(fpsCap = if (i == 0) 0 else fpsOptions[i].toInt())
            save()
        }
    }

    private fun fill(spinner: Spinner, items: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }

    /** A Spinner whose first callback is the initial setSelection, ignored on purpose. */
    private fun Spinner.onPick(handler: (Int) -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var first = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (first) {
                    first = false
                    return
                }
                handler(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun resIndexOf(value: String?): Int =
        if (value == null) 0 else resOptions.indexOf(value).coerceAtLeast(0)

    private fun msaaIndexOf(value: Int): Int = when (value) {
        4 -> 1
        1, 0 -> 2
        else -> 0
    }

    private fun fpsIndexOf(value: Int): Int =
        if (value <= 0) 0 else fpsOptions.indexOf(value.toString()).coerceAtLeast(0)

    private fun wireSwitches() {
        bind(findViewById(R.id.autoScale), config.autoScale) { config = config.copy(autoScale = it); save() }
        bind(findViewById(R.id.thermal), config.thermal) { config = config.copy(thermal = it); save() }
        bind(findViewById(R.id.pinBig), config.pinBigCores) { config = config.copy(pinBigCores = it); save() }
        // NO JNI FROM THIS SCREEN, and it is not a style choice: libcw_runtime.so is loaded by
        // GameActivity and by the probe's own process, so in a launcher that has not yet started
        // a game the library is not present and every NativeBridge call would throw
        // UnsatisfiedLinkError. The persisted switch is enough — GameActivity.onResume reloads
        // the layout and pushes the same value through TouchOverlayView.load, which is the one
        // place that talks to the native side about touch.
        bind(findViewById(R.id.touchEnabled), config.touchEnabled) {
            config = config.copy(touchEnabled = it)
            save()
        }
    }

    private fun bind(switch: Switch, initial: Boolean, onChange: (Boolean) -> Unit) {
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun save() {
        RuntimeConfig.save(this, config)
        refresh()
    }

    // ---------------------------------------------------------------------------------------
    // Drag and drop
    // ---------------------------------------------------------------------------------------

    /**
     * The drop target.
     *
     * Drag-and-drop and the picker are two routes to the same [importGame], and both are here
     * because they answer different situations: dragging from a file manager is what a player
     * who already has the file on screen does, and the picker is what works when the drag does
     * not — which is often, since a drag out of another app only carries a readable URI if that
     * app granted one. The failure mode of a drag that did not is a SecurityException from the
     * resolver, and [GameFiles.importPackage] reports that in words that name the alternative.
     */
    private fun wireDropZone() {
        val hint = findViewById<TextView>(R.id.dropHint)
        dropZone.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    // Claiming only drags that carry content is what lets a drag of, say, text
                    // fall through to whatever else is on screen instead of being swallowed.
                    val ok = event.clipData != null
                    dropZone.isActivated = ok
                    if (ok) hint.text = "Drop the game package to import it"
                    ok
                }

                DragEvent.ACTION_DRAG_ENTERED -> {
                    dropZone.isActivated = true
                    true
                }

                DragEvent.ACTION_DRAG_EXITED -> {
                    hint.text = "or drag the package here from a file manager"
                    true
                }

                DragEvent.ACTION_DROP -> {
                    hint.text = "or drag the package here from a file manager"
                    dropZone.isActivated = false
                    val clip: ClipData? = event.clipData
                    if (clip == null || busy) return@setOnDragListener true
                    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
                    if (uris.isEmpty()) {
                        toast("That drag carried no files.")
                    } else {
                        importGame(uris)
                    }
                    true
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    dropZone.isActivated = false
                    hint.text = "or drag the package here from a file manager"
                    true
                }

                else -> false
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Importing
    // ---------------------------------------------------------------------------------------

    private fun importGame(uris: List<Uri>) {
        if (busy) return
        busy = true
        refresh()
        val label = if (uris.size == 1) "Importing the game package…" else "Importing ${uris.size} files…"
        work(label) { progress ->
            var failure: String? = null
            for (uri in uris) {
                failure = GameFiles.importPackage(this, uri) { done, total ->
                    if (total > 0) progress("$label ${done * 100 / total}%  (${done / (1024 * 1024)} of ${total / (1024 * 1024)} MB)")
                    else progress("$label ${done / (1024 * 1024)} MB")
                }
                if (failure != null) break
            }
            failure
        }
    }

    private fun importDriver(uri: Uri) {
        if (busy) return
        busy = true
        refresh()
        work("Importing the driver package…") { progress ->
            DriverManager.importPackage(this, uri) { done, total ->
                if (total > 0) progress("Importing the driver package… ${done * 100 / total}%")
            }
        }
    }

    /**
     * Run [body] off the UI thread with its progress reported on it.
     *
     * A 1.2 GB copy from a content provider takes a minute or more on real storage, and doing it
     * on the UI thread is an ANR — the system's own dialog saying the app is not responding,
     * which is the one message that makes a working import look like a hung one. The body
     * returns null on success or the reason in words.
     */
    private fun work(startMessage: String, body: ((String) -> Unit) -> String?) {
        gameStatus.text = startMessage
        play.isEnabled = false
        Thread {
            // Progress is throttled to whole percent steps by the caller's own arithmetic; the
            // post is what has to be careful, because a thousand runOnUiThread calls a second is
            // a UI thread doing nothing but drawing text.
            var lastPosted = 0L
            val report: (String) -> Unit = { msg ->
                val now = System.currentTimeMillis()
                if (now - lastPosted > 120) {
                    lastPosted = now
                    ui.post { if (busy) gameStatus.text = msg }
                }
            }
            val failure = try {
                body(report)
            } catch (e: Throwable) {
                Log.e(TAG, "import failed", e)
                "Import failed: ${e.message}"
            }
            ui.post {
                busy = false
                if (failure != null) {
                    gameStatus.text = failure
                    toast(failure)
                }
                refresh()
            }
        }.apply {
            name = "cw-import"
            start()
        }
    }

    // ---------------------------------------------------------------------------------------
    // The probe
    // ---------------------------------------------------------------------------------------

    /**
     * Ask the runtime which driver it would load — in another process, so the answer cannot
     * decide anything. See [ProbeService] for why the process separation is the whole point.
     */
    private fun probe() {
        if (busy) return
        diagnostics.text = "Probing… (a separate process loads the runtime and asks it)"
        ProbeService.start(this)
        // Polling a file rather than binding a service: the answer is small, it arrives within a
        // second, and a file the player can be asked to send is worth more than a callback that
        // only this screen ever saw.
        val deadline = System.currentTimeMillis() + 8000
        val tick = object : Runnable {
            override fun run() {
                val f = ProbeService.resultFile(this@LauncherActivity)
                if (f.exists()) {
                    showProbeResult()
                } else if (System.currentTimeMillis() < deadline) {
                    ui.postDelayed(this, 250)
                } else {
                    diagnostics.text = "The probe did not answer within 8 s. That is worth " +
                        "reporting: it means the runtime library did not load in the probe " +
                        "process, and `adb logcat -s CaseWest` has the reason."
                }
            }
        }
        ui.postDelayed(tick, 250)
    }

    private fun showProbeResult() {
        refreshDiagnostics()
        toast("Probe result below")
    }

    // ---------------------------------------------------------------------------------------
    // Launching
    // ---------------------------------------------------------------------------------------

    private fun launchGame() {
        if (busy) {
            toast("Still importing — wait for it to finish.")
            return
        }
        val state = GameFiles.state(this)
        if (state == GameFiles.State.NO_PACKAGE) {
            // Allowed rather than blocked, and the reason is honesty about what this screen
            // knows: the runtime's own first-run gate is the authority on whether a package is
            // present, it searches recursively and it prints what it found. A launcher that
            // refused to start would be a second implementation of that check, and the two would
            // disagree about a package dropped in one folder deep. What the player gets instead
            // is the runtime's own message, on screen, in its own words.
            toast("No package imported — starting anyway so the runtime can say what it finds.")
        }
        startActivity(GameFiles.playIntent(this))
    }

    // ---------------------------------------------------------------------------------------
    // Small things
    // ---------------------------------------------------------------------------------------

    /**
     * A picker, wrapped so that the three of them are one mechanism.
     *
     * `registerForPick(null)` is the folder case: ACTION_OPEN_DOCUMENT_TREE, which returns a
     * tree URI that [GameFiles] walks. The permission flags matter — without them the URI is
     * readable once and not again, and an import that survives an app restart is an import the
     * player does not have to repeat.
     */
    private fun registerForPick(
        mimeTypes: Array<String>?,
        onPicked: (Uri?) -> Unit,
    ): Picker = Picker(mimeTypes, onPicked).also { pickers.add(it) }

    private inner class Picker(
        private val mimeTypes: Array<String>?,
        private val onPicked: (Uri?) -> Unit,
    ) {
        private val requestCode = nextRequestCode++

        fun launch() {
            val intent = if (mimeTypes == null) {
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                }
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, requestCode)
            } catch (e: Exception) {
                toast("No document picker on this device: ${e.message}")
            }
        }

        fun handle(uri: Uri?) = onPicked(uri)
    }

    @Deprecated("Framework callback; the pickers are indexed by request code.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = if (data?.data != null) {
            data.data
        } else {
            data?.clipData?.getItemAt(0)?.uri
        }
        if (uri == null) return
        // Take the read permission for as long as it can be held: an imported driver is read
        // again at every boot, and a URI whose grant expired would make the second boot fail on
        // a driver that worked the first time.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "no persistable permission for $uri: ${e.message}")
        }
        pickers.firstOrNull { it.requestCode == requestCode }?.handle(uri)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CaseWest"
    }
}
