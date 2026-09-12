package dev.casewest.android

import android.content.Context
import android.content.SharedPreferences
import android.system.Os

/**
 * Every setting this app has, as the environment the runtime reads.
 *
 * THE MECHANISM IS `setenv` AND NOT JNI, and that is the whole design. This runtime already
 * reads its configuration from the environment on every platform — CW_VK_RES, CW_FPS_CAP,
 * CW_NO_TOUCH, the driver choice, the governor's arms — and those variables are the same
 * ones a developer types into a shell to run a control arm. A launcher that wrote them
 * through a second, Android-only channel would create two places that decide the same
 * thing, and the failure that produces is a setting that works from `adb shell` and not from
 * the UI, or the reverse, with nothing in either log to say which one was read.
 *
 * So the launcher's rows are written into the process environment before the library is
 * loaded, the runtime prints every one of them at boot (host/android_bridge.cpp's
 * CheckEnvironment dumps the whole list whether set or not), and a bug report contains the
 * configuration that actually ran.
 *
 * ORDER MATTERS. `Os.setenv` changes this process's environment, and the runtime reads it
 * with `getenv` from SDL_main — so the export has to happen before the native library runs
 * anything. GameActivity.loadLibraries does the export and then loads, which is the one
 * point in SDL's lifecycle guaranteed to precede both. Setting a variable after
 * `System.loadLibrary` is not "late", it is invisible: the reader may already have cached
 * the answer in a `std::once_flag`.
 */
data class RuntimeConfig(
    // --- the game ---------------------------------------------------------------
    /**
     * The internal resolution to pin, as "WxH", or null to let the runtime decide.
     *
     * NULL IS THE DEFAULT AND IT IS THE INTERESTING CASE. Setting CW_VK_RES pins the
     * resolution for a measurement run, and host/android_perf.cpp reads that pin at init and
     * DISABLES the governor: an auto-scaler inside a resolution experiment measures the
     * scaler, not the game. So a player who picks a fixed resolution is also, knowingly,
     * switching off the thing that keeps the frame rate up on a hot phone — which is why the
     * launcher's row says so instead of offering it as a free upgrade.
     */
    val internalRes: String? = null,

    /** 0/1 = single sample, 2 = the shipped default, 4 = as much as the driver allows. */
    val msaa: Int = 2,

    /** Frames per second to pace at, 20..500, or 0 for "leave it to the persisted setting". */
    val fpsCap: Int = 0,

    // --- touch ------------------------------------------------------------------
    /**
     * The player-facing "disable touch controls" switch.
     *
     * Off means the overlay publishes nothing and cpu/touch_input.cpp's merge contributes
     * nothing — a Bluetooth pad and a keyboard still work, because touch is the fourth input
     * source and not a mode. Written as CW_NO_TOUCH, which is an off-by-presence variable:
     * any value but "0" disables it, so this writes "1" only when the switch is off and
     * writes nothing at all when it is on.
     */
    val touchEnabled: Boolean = true,

    // --- GPU driver -------------------------------------------------------------
    /**
     * The chosen driver, or null for the device's own.
     *
     * Both halves of adrenotools' pair have to be present for a custom driver to be used —
     * [DriverChoice.dir] is the directory holding the driver's .so and [DriverChoice.name] is
     * the `libraryName` from its meta.json — and gpu/vk_shadow_android.cpp says so in the log
     * when only one is. See [DriverManager] for how an imported .adpkg becomes one of these.
     */
    val driver: DriverChoice? = null,

    /**
     * adrenotools' "turbo" mode, which asks the driver to skip a layer of its own indirection.
     * Off by default: it is a real speed-up on some Adreno generations and a real instability
     * on others, and a setting whose effect depends on hardware the app cannot identify is a
     * setting the player should choose rather than inherit.
     */
    val turbo: Boolean = false,

    // --- the governor -----------------------------------------------------------
    /**
     * Frame-rate arm: lower the internal resolution when the measured frame rate sits under
     * the target, raise it back when there is headroom. ON by default.
     *
     * These two are FEATURE-named variables, so "0" is the off spelling and anything else
     * leaves the default — the opposite direction from CW_NO_TOUCH, and the inversion is
     * documented at EnvSaysOff in host/android_perf.cpp because getting it backwards is
     * silent. Both are written explicitly, "1" or "0", rather than omitted when on: the boot
     * log then shows the launcher's answer instead of "(unset)", and "(unset)" is what a
     * launcher that forgot to export anything looks like.
     */
    val autoScale: Boolean = true,

    /** Thermal arm: lower the ceiling when the OS reports the device getting hot. ON by default. */
    val thermal: Boolean = true,

    /**
     * Pin the pump thread to the big cores. OFF by default, and the reason is that it is a
     * trade rather than a win: it stops the scheduler migrating the thread that paces every
     * frame onto a little core mid-frame, and it makes that thread compete with the render
     * thread for the same cluster. host/android_perf.cpp prints a line only when it is on,
     * precisely because a session that ran with it should say so.
     */
    val pinBigCores: Boolean = false,

    /**
     * What the governor aims at, 10..240. Zero means "derive it": the player's frame-rate cap
     * when they set one, and the console's own 30 when they did not.
     */
    val targetFps: Int = 0,

    // --- optional, imported by hand ---------------------------------------------
    /**
     * An arm64 libdxcompiler.so the player imported, or null.
     *
     * The shader translator dlopens DXC to build the SPIR-V cache. There is no prebuilt
     * arm64-Android libdxcompiler.so published anywhere this project can download, so the
     * honest paths are the two the launcher offers: ship a cache built on a desktop
     * (tools/build_shader_spv.sh, copied into CW_ROOT/assets/shader_spv), or import a library
     * somebody built. Without either, the runtime still boots, still refuses each translation
     * with one log line, and presents the black screen the first-run check exists to prevent —
     * which is why the launcher states the situation rather than hiding the row.
     *
     * Unset, the translator also tries <nativeLibraryDir>/libdxcompiler.so, so an APK that
     * bundles one in jniLibs needs no variable at all.
     */
    val dxcLib: String? = null,
) {

    /**
     * The environment this configuration means, in the order it should be read in a log.
     *
     * [context] supplies the two paths that are the app's and not a setting: CW_ROOT, the
     * private data root every other path hangs off, and the driver directory, which has to be
     * internal storage rather than a card (adrenotools' own requirement — a dlopen of a file
     * on sdcard is refused by the linker namespace, and the refusal reads like a corrupt
     * driver).
     */
    fun environment(context: Context): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        // The one variable without which nothing works: host/host_paths.cpp resolves every
        // asset, cache and save path from it, and on Android there is no executable directory
        // to fall back to — /proc/self/exe is the framework's app_process64 in a read-only
        // tree. The runtime refuses loudly when it is missing and names this file.
        env["CW_ROOT"] = GameFiles.root(context).absolutePath

        // The renderer. CW_VKDRAW is off-by-absence (vk_renderer.cpp's EnvOn is
        // `getenv(n) != nullptr`), so a game build sets it and a headless gate run does not.
        env["CW_VKDRAW"] = "1"

        internalRes?.let { env["CW_VK_RES"] = it }
        env["CW_VK_MSAA"] = msaa.toString()
        if (fpsCap > 0) env["CW_FPS_CAP"] = fpsCap.toString()

        if (!touchEnabled) env["CW_NO_TOUCH"] = "1"

        if (driver != null) {
            env["CW_VK_DRIVER_DIR"] = driver.dir
            env["CW_VK_DRIVER_NAME"] = driver.name
        } else {
            // Said out loud rather than left unset: a launcher that forgot to export the
            // driver the player picked and a launcher that was told to use the system one
            // look identical in a log that prints only what is set.
            env["CW_VK_NO_CUSTOM_DRIVER"] = "1"
        }
        if (turbo) env["CW_VK_TURBO"] = "1"

        env["CW_ANDROID_AUTO_SCALE"] = if (autoScale) "1" else "0"
        env["CW_ANDROID_THERMAL"] = if (thermal) "1" else "0"
        env["CW_ANDROID_PIN_BIG"] = if (pinBigCores) "1" else "0"
        if (targetFps > 0) env["CW_ANDROID_TARGET_FPS"] = targetFps.toString()

        dxcLib?.let { env["CW_DXC_LIB"] = it }

        return env
    }

    /**
     * Write [environment] into this process, and say what was written.
     *
     * Called from GameActivity.loadLibraries, before the library is loaded. `Os.setenv` with
     * overwrite=true is the whole implementation; the log line is the part that earns its
     * place, because it is what makes a phone's logcat self-describing — the runtime prints
     * the same variables again from SDL_main, and the two lists agreeing is the evidence that
     * nothing between here and there changed one.
     */
    fun export(context: Context) {
        val env = environment(context)
        for ((key, value) in env) {
            // Os.setenv throws ErrnoException on a failure that, in practice, means the name
            // contained '=' — which would be a bug in this file and not a device problem, so
            // it is reported as one rather than swallowed.
            try {
                Os.setenv(key, value, true)
            } catch (e: Exception) {
                android.util.Log.e("CaseWest", "setenv($key) failed: $e")
            }
        }
        android.util.Log.i(
            "CaseWest",
            "exported ${env.size} env vars before loading libcw_runtime.so: " +
                env.entries.joinToString(" ") { "${it.key}=${it.value}" }
        )
    }

    companion object {

        private const val PREFS = "cw_runtime_config"

        fun load(context: Context): RuntimeConfig {
            val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val driverDir = p.getString("driverDir", null)
            val driverName = p.getString("driverName", null)
            return RuntimeConfig(
                internalRes = p.getString("internalRes", null)?.takeIf { it.isNotEmpty() },
                msaa = p.getInt("msaa", 2),
                fpsCap = p.getInt("fpsCap", 0),
                touchEnabled = p.getBoolean("touchEnabled", true),
                // Both halves or neither: a saved pair whose directory has since been deleted
                // is dropped here rather than exported, because exporting it produces a boot
                // log full of adrenotools failures when the honest state is "no driver".
                // DriverManager.pruneMissing is what removes the stale preference.
                driver = if (!driverDir.isNullOrEmpty() && !driverName.isNullOrEmpty() &&
                    java.io.File(driverDir, driverName).exists()
                ) {
                    DriverChoice(driverDir, driverName, p.getString("driverLabel", driverName)!!)
                } else {
                    null
                },
                turbo = p.getBoolean("turbo", false),
                autoScale = p.getBoolean("autoScale", true),
                thermal = p.getBoolean("thermal", true),
                pinBigCores = p.getBoolean("pinBigCores", false),
                targetFps = p.getInt("targetFps", 0),
                dxcLib = p.getString("dxcLib", null)?.takeIf { java.io.File(it).exists() },
            )
        }

        fun save(context: Context, config: RuntimeConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("internalRes", config.internalRes ?: "")
                .putInt("msaa", config.msaa)
                .putInt("fpsCap", config.fpsCap)
                .putBoolean("touchEnabled", config.touchEnabled)
                .putString("driverDir", config.driver?.dir)
                .putString("driverName", config.driver?.name)
                .putString("driverLabel", config.driver?.label)
                .putBoolean("turbo", config.turbo)
                .putBoolean("autoScale", config.autoScale)
                .putBoolean("thermal", config.thermal)
                .putBoolean("pinBigCores", config.pinBigCores)
                .putInt("targetFps", config.targetFps)
                .putString("dxcLib", config.dxcLib)
                .apply()
        }
    }
}

/**
 * One imported GPU driver, in the two halves adrenotools asks for plus the name a human reads.
 *
 * [dir] MUST be internal storage — not a card, not a shared volume. adrenotools dlopens the
 * driver file, and the linker namespace an app runs in refuses to open a library from
 * external storage; the refusal surfaces inside the driver, which makes it look like a
 * corrupt download rather than a wrong directory. [name] is the `libraryName` field of the
 * driver package's meta.json, i.e. the actual file name inside [dir].
 */
data class DriverChoice(val dir: String, val name: String, val label: String)
