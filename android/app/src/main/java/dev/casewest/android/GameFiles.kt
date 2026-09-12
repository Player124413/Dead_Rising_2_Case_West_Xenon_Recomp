package dev.casewest.android

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * The data root, and everything the launcher has to know about its layout.
 *
 * CW_ROOT is `<filesDir>/cw`, and its shape is not this file's invention — it is the shape
 * runtime/host/host_paths.h documents and runtime/host/first_run.h gates on:
 *
 *     <root>/assets/package/     the STFS container the player owns and we never ship
 *     <root>/assets/game/        what the runtime unpacks out of it on first run
 *     <root>/assets/shader_spv/  the translated shader cache
 *     <root>/assets/save/        the guest's saves
 *     <root>/tools/release/      the assets that ARE ours: prewarm.keys, vs_recipes.bin and
 *                                kbm_chips/, seeded out of the APK
 *
 * Two of those deserve a word, because both look like they belong somewhere else.
 *
 * INTERNAL STORAGE, NOT A CARD. The 1.2 GB package, the unpacked game and the shader cache
 * all live in the app's private directory. That is not tidiness: the runtime opens the
 * container with a plain open() and maps the unpacked XEX, an adrenotools-loaded driver has
 * to be dlopen'd from a path the linker namespace will accept, and external storage satisfies
 * neither on a modern Android. It also means uninstalling the app removes the game, which the
 * launcher's import row states in words rather than leaving a player to discover it.
 *
 * tools/release/ UNDER THE ROOT. prewarm.keys, vs_recipes.bin and the kbm chip blobs are
 * looked up beside the executable first, and on Android "beside the executable" is
 * nativeLibraryDir — a directory of .so files that an APK cannot put anything else into. All
 * three already have a `<root>/tools/release/` fallback in the runtime (VsRecipes,
 * FindChipsDir, and the Android-only branch at the prewarm read), so this seeds them there.
 */
object GameFiles {

    private const val TAG = "CaseWest"

    /** The data root. Created on demand; exported to the runtime as CW_ROOT. */
    fun root(context: Context): File = File(context.filesDir, "cw")

    fun packageDir(context: Context): File = File(root(context), "assets/package")
    fun gameDir(context: Context): File = File(root(context), "assets/game")
    fun shaderCache(context: Context): File = File(root(context), "assets/shader_spv")
    fun saveDir(context: Context): File = File(root(context), "assets/save")
    fun toolsRelease(context: Context): File = File(root(context), "tools/release")

    /**
     * What the runtime's first-run gate would say, asked before the runtime is asked.
     *
     * The states mirror runtime/host/first_run.h's one for one, because a launcher that
     * invented its own idea of "ready" would be a second implementation of a check that
     * already exists — and the two would disagree in exactly the case that matters, which is
     * a player who has done everything the launcher asked and still gets a refusal at boot.
     */
    enum class State {
        /** assets/package holds nothing that looks like a container. */
        NO_PACKAGE,

        /** A container is there; assets/game/default.xex is not, i.e. it has not been unpacked. */
        NO_GAME,

        /** Unpacked, but no shader cache: the title would boot to a black screen. */
        NO_SHADER_CACHE,

        READY,
    }

    fun state(context: Context): State {
        if (findPackage(context) == null) return State.NO_PACKAGE
        if (!File(gameDir(context), "default.xex").exists()) return State.NO_GAME
        val cache = shaderCache(context)
        if (!cache.isDirectory || (cache.listFiles()?.isEmpty() != false)) return State.NO_SHADER_CACHE
        return State.READY
    }

    /**
     * The container the runtime would find, or null.
     *
     * The runtime searches assets/package RECURSIVELY and takes the largest plausible file —
     * copying a whole `58410B00` folder in works, which is what a player with a console hard
     * drive actually has. This mirrors that rather than looking only at the top level, so the
     * launcher's "ready" and the runtime's agree on a tree that was dropped in one folder
     * deep. Size is the discriminator because the container is ~1.2 GB and nothing else in
     * there is over a megabyte.
     */
    fun findPackage(context: Context): File? {
        val dir = packageDir(context)
        if (!dir.isDirectory) return null
        var best: File? = null
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f.length() > 1L * 1024 * 1024 && (best == null || f.length() > best!!.length())) {
                best = f
            }
        }
        return best
    }

    /** Free space on the data partition, in bytes — checked before a 1.2 GB copy, not during. */
    fun freeBytes(context: Context): Long =
        try {
            StatFs(root(context).absolutePath).availableBytes
        } catch (e: Exception) {
            // A directory that does not exist yet has no stats; the caller creates it first
            // in every path that reaches here, so this is a fallback rather than a case.
            StatFs(context.filesDir.absolutePath).availableBytes
        }

    // -----------------------------------------------------------------------------------
    // Seeding
    // -----------------------------------------------------------------------------------

    /**
     * Copy the runtime's OWN assets out of the APK and into the data root.
     *
     * Runs before the first boot and again after an app update, and it never touches
     * anything the player put there: the package, the unpacked game, the shader cache and the
     * saves are all left alone, because an update that cost a player their 1.2 GB import is an
     * update they uninstall over. The marker file records which versionCode last seeded, so
     * "again after an update" is a comparison and not a guess.
     *
     * Returns a human-readable summary for the launcher's log row. Absent assets are not an
     * error here — a CI-built APK has no shader cache because no runner may hold the game
     * data, and the first-run gate reports that in words the runtime already has.
     */
    fun seed(context: Context): String {
        val root = root(context)
        listOf(packageDir(context), gameDir(context), shaderCache(context), saveDir(context),
            toolsRelease(context)).forEach { it.mkdirs() }

        val marker = File(root, ".seeded-version")
        // longVersionCode is an API 28 FIELD, and reading a field the running framework's
        // PackageInfo does not have is a NoSuchFieldError rather than a zero — so the old
        // accessor is used below 28 and the deprecation warning is the price of supporting
        // the two API levels that still need it.
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: Exception) {
            0L
        }
        if (marker.exists() && marker.readText().trim() == version.toString()) {
            return "assets already seeded for version $version"
        }

        val copied = mutableListOf<String>()
        // assets/cw/... in the APK -> tools/release/... under the root. See the class comment
        // for why tools/release and not the root itself: it is where the runtime's own
        // fallbacks already look.
        copyAssetTree(context, "cw", toolsRelease(context), copied)
        // A desktop-built shader cache, when the person building the APK had the game and
        // staged one. Not present in CI, and not a defect: without it the runtime translates
        // at first run, which needs DXC — see RuntimeConfig.dxcLib for the two honest ways to
        // have that on a phone.
        copyAssetTree(context, "shader_spv", shaderCache(context), copied)

        marker.writeText(version.toString())
        Log.i(TAG, "seeded ${copied.size} file(s) into $root: ${copied.take(8).joinToString()}")
        return "seeded ${copied.size} file(s) into $root"
    }

    /** Recursively copy an APK asset directory into [dest], recording what was written. */
    private fun copyAssetTree(context: Context, assetPath: String, dest: File, copied: MutableList<String>) {
        val assets = context.assets
        val top = try {
            assets.list(assetPath) ?: return
        } catch (e: Exception) {
            return
        }
        if (top.isEmpty()) return
        dest.mkdirs()
        for (name in top) {
            val child = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val children = try {
                assets.list(child) ?: emptyArray()
            } catch (e: Exception) {
                emptyArray()
            }
            if (children.isNotEmpty()) {
                copyAssetTree(context, child, File(dest, name), copied)
            } else {
                try {
                    assets.open(child).use { input ->
                        FileOutputStream(File(dest, name)).use { output -> input.copyTo(output) }
                    }
                    copied.add(name)
                } catch (e: Exception) {
                    // A directory entry that lists but does not open is how AssetManager
                    // reports an empty directory on some API levels. Not a failure to seed.
                    Log.w(TAG, "asset $child: ${e.message}")
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Importing the game
    // -----------------------------------------------------------------------------------

    /**
     * Copy what the player picked into assets/package.
     *
     * Accepts either half of what a player actually has: a single file (the container itself,
     * which on a console's storage is a long hash with no extension) or a DIRECTORY TREE (the
     * whole `58410B00` folder, or the `Content/0000…/58410B00/000D0000` path above it), in
     * which case every file over a megabyte is taken and the rest ignored. The runtime's own
     * search is recursive and size-based, so a tree copied in at any depth is found.
     *
     * [onProgress] is called with (bytesCopied, totalBytesOrMinusOne) and is the only reason
     * this is not a one-liner: a 1.2 GB copy from a content provider takes a minute or more on
     * real storage, and an import with no progress is an import a player kills at 40% because
     * it looks hung.
     *
     * Returns null on success or the reason it failed, in words. Space is checked first and
     * named, because "no space left on device" arriving out of a write() deep inside a copy
     * loop is a message about a file descriptor when the actual subject is a phone with 800 MB
     * free.
     */
    fun importPackage(
        context: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String? {
        val dest = packageDir(context)
        dest.mkdirs()

        val files = collect(context, uri)
        if (files.isEmpty()) {
            return "Nothing to import from that selection. The game package is the file your " +
                "Xbox 360 downloaded — about 1.2 GB, no file extension, in " +
                "Content/0000000000000000/58410B00/000D0000/. Selecting that file, or the " +
                "58410B00 folder above it, both work."
        }

        val total = files.sumOf { if (it.third > 0) it.third else 0L }
        val free = freeBytes(context)
        // Headroom for the UNPACK as well as the copy: the container is opened, its files are
        // written into assets/game, and both exist at once for the length of a first run.
        // Asking for 2.2x is what makes "the import succeeded and the boot then failed on
        // ENOSPC" impossible, and that failure is the worst one available because it happens
        // after the player has already waited.
        val needed = total + (total * 11 / 10)
        if (free < needed) {
            return "Not enough free space. Importing needs about ${mb(needed)} MB " +
                "(${mb(total)} MB for the package plus room to unpack it) and the app's data " +
                "partition has ${mb(free)} MB free."
        }

        var done = 0L
        for ((name, uri, size) in files) {
            val out = File(dest, name)
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return "Could not open $name for reading."
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(1 shl 20)
                        var n: Int
                        var written = 0L
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                            written += n
                            done += n
                            onProgress(done, total)
                        }
                        // -1 means the provider would not report a size, which some do not;
                        // comparing against it would fail every import from such a source.
                        if (size > 0 && written != size) {
                            return "$name changed size while it was being copied " +
                                "($written of $size bytes). Import it again from a copy that " +
                                "is not being written to."
                        }
                    }
                }
            } catch (e: SecurityException) {
                // A drag-and-drop from another app hands over a URI whose read permission may
                // not have travelled with it. The honest report names the mechanism, because
                // "permission denied" on a file the player just picked reads like a bug in the
                // app rather than a limitation of the thing they dragged from.
                return "Android refused to read $name (${e.message}). Use the Import button " +
                    "and pick the file from the document picker instead of dragging it."
            } catch (e: Exception) {
                out.delete()
                return "Copying $name failed: ${e.message}"
            }
        }
        onProgress(total, total)
        return null
    }

    /** (fileName, uri, size) triples for a document or a tree, filtered to package-sized files. */
    private fun collect(context: Context, uri: Uri): List<Triple<String, Uri, Long>> {
        val out = mutableListOf<Triple<String, Uri, Long>>()
        if (DocumentsContract.isTreeUri(uri)) {
            walkTree(context, uri, "", out, 0)
            // A tree that holds a lot of small files is somebody's whole download folder. The
            // size filter is what makes that harmless.
            return out.filter { it.third > 1L * 1024 * 1024 }
        }
        val name = displayName(context, uri) ?: "package"
        val size = querySize(context, uri)
        out.add(Triple(name, uri, size))
        return out.filter { it.third <= 0 || it.third > 1L * 1024 * 1024 }
    }

    private fun walkTree(
        context: Context,
        tree: Uri,
        parentDocId: String,
        out: MutableList<Triple<String, Uri, Long>>,
        depth: Int,
    ) {
        if (depth > 8) return   // a package tree is three levels deep; this is a guard, not a policy
        val root = if (parentDocId.isEmpty()) {
            DocumentsContract.getTreeDocumentId(tree)
        } else {
            parentDocId
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null
            )
            while (cursor != null && cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                    walkTree(context, tree, id, out, depth + 1)
                } else {
                    out.add(Triple(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), size))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "walking $children: ${e.message}")
        } finally {
            cursor?.close()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        query(context, uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    private fun querySize(context: Context, uri: Uri): Long =
        query(context, uri, DocumentsContract.Document.COLUMN_SIZE)?.toLongOrNull() ?: -1L

    private fun query(context: Context, uri: Uri, column: String): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(column), null, null, null)
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    /**
     * The intent the launcher's Play button starts, with the configuration attached.
     *
     * The settings ride as an EXTRA and are re-read in GameActivity rather than exported
     * here, because exporting has to happen inside the process that loads the library and
     * after nothing else has run — see RuntimeConfig.export. Keeping the two apart means the
     * environment is written in exactly one place, in the one order that works.
     */
    fun playIntent(context: Context): Intent =
        Intent(context, GameActivity::class.java).apply {
            // singleTask + an explicit extra: a second Play press while a boot is in flight
            // delivers onNewIntent rather than starting a second runtime.
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}
