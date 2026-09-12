package dev.casewest.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * Asks the runtime which Vulkan driver it would get, WITHOUT committing the game to that
 * answer.
 *
 * THE PROCESS IS THE POINT. gpu/vk_shadow_android.cpp chooses its loader once, behind a
 * std::once_flag, and a library that has been loaded into a process stays loaded there: if the
 * launcher probed in its own process — which is the app's process, and therefore the game's —
 * the probe would decide the driver for the session that follows it. A player who opened the
 * launcher, looked at the diagnostics, then picked Turnip and pressed Play would get the
 * system driver, and the only evidence would be a [vk-loader] line at the top of a log they
 * were not reading. `android:process=":probe"` in the manifest puts this in a separate process
 * with its own copy of the library and its own once_flag, which makes the probe a question
 * instead of a decision.
 *
 * THE ANSWER TRAVELS AS A FILE, and that is also deliberate. A bound service with a
 * ResultReceiver would work and would be more code, and the file has a property the callback
 * does not: it survives. A player who reports "it says no Vulkan" can be asked for
 * `vulkan-probe.txt`, and the launcher shows its contents verbatim rather than a summary
 * somebody wrote. It is written to the app's private directory, read by the launcher, and
 * deleted on the next probe.
 */
class ProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread {
            val out = File(filesDir, RESULT_FILE)
            val text = probe()
            try {
                out.writeText(text)
            } catch (e: Exception) {
                Log.e(TAG, "could not write $out: ${e.message}")
            }
            stopSelf(startId)
        }.apply {
            name = "cw-probe"
            isDaemon = true
            start()
        }
        return START_NOT_STICKY
    }

    private fun probe(): String = try {
        // The same export GameActivity does, so the probe reports on the driver the player has
        // actually selected and not on a default. Seeding is not needed here: nothing this
        // reads touches the data root.
        RuntimeConfig.load(this).export(this)
        System.loadLibrary("cw_runtime")
        val ok = NativeBridge.nativeInitVulkan()
        val source = NativeBridge.nativeVulkanLoaderSource()
        val info = NativeBridge.nativeRuntimeInfo()
        buildString {
            append("vulkan loader available: ").append(if (ok) "YES" else "NO").append('\n')
            append("loader source: ").append(source).append('\n')
            append('\n').append(info)
        }
    } catch (e: UnsatisfiedLinkError) {
        // The one failure that is worth a sentence of its own: it means the APK does not
        // contain libcw_runtime.so for this device's ABI, which is a packaging problem and not
        // a device problem, and the two have different fixes.
        "libcw_runtime.so could not be loaded on this device: ${e.message}\n\n" +
            "That is a packaging failure, not a driver one — the APK has no library for this " +
            "ABI. This port builds arm64-v8a only."
    } catch (e: Throwable) {
        "probe failed: $e"
    }

    companion object {
        private const val TAG = "CaseWest"
        const val RESULT_FILE = "vulkan-probe.txt"

        fun resultFile(context: Context) = File(context.filesDir, RESULT_FILE)

        /** Ask, and clear whatever the last answer was so a stale one is never shown. */
        fun start(context: Context) {
            resultFile(context).delete()
            context.startService(Intent(context, ProbeService::class.java))
        }
    }
}
