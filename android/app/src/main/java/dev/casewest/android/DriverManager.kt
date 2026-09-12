package dev.casewest.android

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Imported GPU drivers — the Turnip row in the launcher, and the reason it exists.
 *
 * WHY AN APP CANNOT JUST ASK FOR A DIFFERENT DRIVER. On a desktop the loader reads
 * VK_DRIVER_FILES and you point it at a Mesa build; on Android that mechanism does not
 * exist, because the loader is /system/lib64/libvulkan.so and the driver it opens is
 * /vendor/lib64/hw/vulkan.adreno.so, both of them outside anything an app can influence.
 * The rootless answer is libadrenotools: it loads the system loader into a private
 * namespace and redirects the ONE dlopen that fetches the driver to a file in the app's own
 * storage. gpu/vk_shadow_android.cpp does exactly that, driven by CW_VK_DRIVER_DIR and
 * CW_VK_DRIVER_NAME, and this file is how a player gets a driver into that directory.
 *
 * WHY IT MATTERS FOR THIS TITLE AND NOT ONLY FOR PERFORMANCE. The renderer requires Vulkan
 * 1.3, bufferDeviceAddress, descriptor indexing, dynamicRendering and textureCompressionBC
 * (gpu/vk_renderer.cpp's CW_FEAT list, checked at device creation and refused in words).
 * A phone whose platform loader predates Vulkan 1.3 — Android 12 and below — cannot satisfy
 * that from its own driver, and a Mesa Turnip build reporting 1.3 can. So for those devices
 * a custom driver is not an optimisation, it is the difference between booting and a
 * two-line refusal in the log. It is also the answer to BCn: adrenotools ships a patcher for
 * Adreno drivers whose hardware decodes BC textures but whose driver does not advertise it,
 * and Turnip advertises it outright.
 *
 * THE DIRECTORY IS INTERNAL STORAGE, and that is adrenotools' requirement rather than a
 * preference: a dlopen of a file on external storage is refused by the linker namespace an
 * app runs in, and the refusal arrives from inside the driver loader where it reads like a
 * corrupt download. So `<filesDir>/drivers/<slug>/`, which is also where an uninstall
 * removes it.
 *
 * A DRIVER IS THIRD-PARTY NATIVE CODE THAT RUNS IN THIS PROCESS. Nothing here can inspect
 * what it does, and the launcher says so before the import rather than after a crash. That
 * is the whole of the trust model: the player picked the file.
 */
object DriverManager {

    private const val TAG = "CaseWest"

    fun driversDir(context: Context): File = File(context.filesDir, "drivers").apply { mkdirs() }

    /** One driver on disk, described by its own meta.json. */
    data class Installed(
        val slug: String,
        val dir: File,
        /** meta.json's `libraryName` — the file inside [dir] that adrenotools dlopens. */
        val libraryName: String,
        val label: String,
        val vendor: String,
        val driverVersion: String,
        val description: String,
        val minApi: Int,
    ) {
        val supportedHere: Boolean get() = Build.VERSION.SDK_INT >= minApi

        /** What CW_VK_DRIVER_DIR / CW_VK_DRIVER_NAME need, or null if the file is gone. */
        fun toChoice(): DriverChoice? =
            if (File(dir, libraryName).exists()) DriverChoice(dir.absolutePath, libraryName, label)
            else null

        fun describe(): String = buildString {
            append(label)
            if (vendor.isNotEmpty()) append("  [").append(vendor).append(']')
            if (driverVersion.isNotEmpty()) append("  ").append(driverVersion)
            if (!supportedHere) {
                append("  — NEEDS ANDROID API ").append(minApi)
                append(", this device is ").append(Build.VERSION.SDK_INT)
            }
            if (!File(dir, libraryName).exists()) append("  — MISSING ").append(libraryName)
            if (description.isNotEmpty()) append("\n").append(description)
        }
    }

    /** Everything installed, sorted by label so the launcher's list does not move around. */
    fun list(context: Context): List<Installed> =
        driversDir(context).listFiles()?.filter { it.isDirectory }
            ?.mapNotNull { readMeta(it) }
            ?.sortedBy { it.label.lowercase() }
            ?: emptyList()

    private fun readMeta(dir: File): Installed? {
        val meta = File(dir, "meta.json")
        if (!meta.isFile) return null
        return try {
            val j = JSONObject(meta.readText())
            val lib = j.optString("libraryName", "")
            if (lib.isEmpty()) {
                Log.w(TAG, "$meta has no libraryName — adrenotools cannot load a driver it " +
                    "cannot name, so this one is not listed.")
                return null
            }
            Installed(
                slug = dir.name,
                dir = dir,
                libraryName = lib,
                label = j.optString("name", dir.name),
                vendor = j.optString("vendor", ""),
                driverVersion = j.optString("driverVersion", ""),
                description = j.optString("description", ""),
                minApi = j.optInt("minApi", 0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "$meta is not readable JSON: ${e.message}")
            null
        }
    }

    /**
     * Import a driver the player picked, and say what happened.
     *
     * Two shapes are accepted, because those are the two shapes that exist in the wild:
     *
     *   * a driver PACKAGE — a zip holding meta.json, the driver .so and any dependency
     *     libraries whose sonames were rewritten so they resolve inside the package. This is
     *     what an .adpkg is and what the well-known Turnip builds ship as.
     *   * a bare libvulkan_*.so with no metadata, which is put in a directory of its own with
     *     a meta.json written for it. It will load if the driver has no dependencies of its
     *     own, which is the honest limit of what can be promised about a file with no
     *     manifest.
     *
     * Returns null on success or the reason in words. [onProgress] gets (bytes, totalOrMinus1)
     * because a driver package is tens of megabytes and the copy comes off a content provider.
     */
    fun importPackage(
        context: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String? {
        val picked = copyToTemp(context, uri, onProgress) ?: return "Could not read that file."
        return try {
            if (isZip(picked)) importZip(context, picked) else importBareSo(context, picked, uri)
        } catch (e: Exception) {
            "Importing failed: ${e.message}"
        } finally {
            picked.delete()
        }
    }

    private fun copyToTemp(context: Context, uri: Uri, onProgress: (Long, Long) -> Unit): File? {
        return try {
            val temp = File(context.cacheDir, "driver-import.tmp")
            val total = queryLong(context, uri)
            var done = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buf = ByteArray(1 shl 18)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            } ?: return null
            temp
        } catch (e: Exception) {
            Log.w(TAG, "driver import copy: ${e.message}")
            null
        }
    }

    private fun isZip(f: File): Boolean {
        if (f.length() < 4) return false
        val head = ByteArray(4)
        f.inputStream().use { it.read(head) }
        return head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }

    private fun importZip(context: Context, zip: File): String? {
        // Read the manifest out of the archive BEFORE choosing a directory, so the slug can be
        // the driver's own name and a second import of the same driver replaces the first
        // instead of accumulating copies of it.
        var meta: JSONObject? = null
        var metaPath: String? = null
        ZipFile(zip).use { z ->
            val entry = z.entries().toList().firstOrNull { it.name.endsWith("meta.json") }
                ?: return "That zip has no meta.json, so it is not a driver package. A driver " +
                    "package carries meta.json naming the library to load; without one there " +
                    "is nothing to point adrenotools at."
            metaPath = entry.name
            meta = JSONObject(z.getInputStream(entry).reader().readText())
        }
        val libraryName = meta!!.optString("libraryName", "")
        if (libraryName.isEmpty()) {
            return "meta.json has no libraryName field, so there is no library to load."
        }

        val label = meta!!.optString("name", libraryName)
        val slug = slugFor(label, libraryName)
        val dest = File(driversDir(context), slug)
        dest.deleteRecursively()
        dest.mkdirs()

        // Everything in the archive goes into the driver's directory, flattened of any leading
        // folder: packages are made both with and without a top-level directory, and a driver
        // whose dependency libraries sit one level away from its meta.json do not resolve.
        val strip = metaPath!!.substringBeforeLast('/', "")
        ZipFile(zip).use { z ->
            for (entry in z.entries()) {
                if (entry.isDirectory) continue
                val rel = if (strip.isNotEmpty() && entry.name.startsWith("$strip/")) {
                    entry.name.substring(strip.length + 1)
                } else {
                    entry.name
                }
                // A path with .. in it is an escape from the directory we chose, and the
                // archive came from somewhere we do not control.
                if (rel.isEmpty() || rel.contains("..") || rel.startsWith('/')) {
                    Log.w(TAG, "skipping $rel: not a path this importer will write")
                    continue
                }
                val out = File(dest, rel)
                out.parentFile?.mkdirs()
                z.getInputStream(entry).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
            }
        }
        // The manifest is written last and is what makes the directory count as installed:
        // list() ignores a directory with no meta.json, so a half-copied package is never
        // offered to the player as a working driver.
        File(dest, "meta.json").writeText(meta!!.toString(2))

        if (!File(dest, libraryName).exists()) {
            dest.deleteRecursively()
            return "The package's meta.json names $libraryName but the archive does not " +
                "contain it. That is a broken package, not a broken import."
        }
        Log.i(TAG, "imported driver '$label' into $dest")
        return null
    }

    private fun importBareSo(context: Context, so: File, uri: Uri): String? {
        val name = queryName(context, uri) ?: so.name
        if (!name.endsWith(".so")) {
            return "That file is neither a driver package (a zip with meta.json) nor a .so, so " +
                "there is nothing here that can be loaded as a Vulkan driver."
        }
        val slug = slugFor(name.removeSuffix(".so"), name)
        val dest = File(driversDir(context), slug)
        dest.deleteRecursively()
        dest.mkdirs()
        so.copyTo(File(dest, name), overwrite = true)
        File(dest, "meta.json").writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("name", name.removeSuffix(".so"))
                .put("libraryName", name)
                .put("description", "Imported as a bare library: no manifest came with it, so " +
                    "its dependencies — if it has any — are not present and it may fail to load.")
                .toString(2)
        )
        return null
    }

    /** A directory name that is stable for a given driver and safe to create. */
    private fun slugFor(label: String, libraryName: String): String {
        val base = label.lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .take(48)
        return if (base.isEmpty()) libraryName.removeSuffix(".so") else base
    }

    fun delete(context: Context, driver: Installed) {
        driver.dir.deleteRecursively()
        Log.i(TAG, "deleted driver ${driver.label} (${driver.dir})")
    }

    /**
     * Drop a saved driver choice whose files are gone.
     *
     * Called from the launcher on resume. Without it the exported environment would name a
     * directory that no longer exists, and the failure that produces is a boot log full of
     * adrenotools errors when the honest state is "no driver installed" — one line here
     * instead of twenty there.
     */
    fun pruneMissing(context: Context, config: RuntimeConfig): RuntimeConfig {
        val d = config.driver ?: return config
        if (File(d.dir, d.name).exists()) return config
        Log.i(TAG, "saved driver ${d.label} is gone (${d.dir}/${d.name}) — falling back to the " +
            "system driver")
        return config.copy(driver = null).also { RuntimeConfig.save(context, it) }
    }

    private fun queryName(context: Context, uri: Uri): String? =
        queryString(context, uri, OpenableColumns.DISPLAY_NAME)

    private fun queryLong(context: Context, uri: Uri): Long =
        queryString(context, uri, OpenableColumns.SIZE)?.toLongOrNull() ?: -1L

    private fun queryString(context: Context, uri: Uri, column: String): String? {
        var cursor: android.database.Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(column), null, null, null)
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
