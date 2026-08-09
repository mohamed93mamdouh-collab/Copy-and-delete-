package com.fixer.fbphotomover

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Single source of truth for the actual "copy, verify, delete" move logic.
 * Used by:
 *  - FileWatcherService (instant move, triggered by FileObserver)
 *  - FileMoveWorker (periodic backup sweep, in case the observer ever misses
 *    an event -- e.g. the service got killed and hasn't restarted yet)
 */
object MediaMover {
    const val TAG = "FBPhotoFixer"
    const val SOURCE_PATH = "DCIM/Facebook"
    const val DEST_DIR_NAME = "Facebook_Saved"

    private val MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
        "mp4", "mov", "3gp", "mkv"
    )

    fun sourceDir(): File =
        File(Environment.getExternalStorageDirectory(), SOURCE_PATH)

    fun destDir(): File {
        val d = File(Environment.getExternalStorageDirectory(), DEST_DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun isMediaFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MEDIA_EXTENSIONS.contains(ext)
    }

    /**
     * Moves a single file (given just its filename, relative to sourceDir())
     * if it still exists there. Safe to call multiple times for the same
     * name -- if the file's already gone it's a no-op.
     */
    @Synchronized
    fun moveFile(fileName: String): Boolean {
        if (!isMediaFile(fileName)) return false
        val src = File(sourceDir(), fileName)
        if (!src.exists() || !src.isFile) return false

        val dest = uniqueDestFile(destDir(), fileName)
        val copied = copyFile(src, dest)

        return if (copied && dest.length() == src.length()) {
            if (src.delete()) {
                Log.i(TAG, "Moved: $fileName -> ${dest.absolutePath}")
                true
            } else {
                Log.w(TAG, "Copied but failed to delete original: $fileName")
                dest.delete()
                false
            }
        } else {
            dest.delete()
            Log.w(TAG, "Copy failed/incomplete for: $fileName")
            false
        }
    }

    /** Scans the whole source folder once. Used as the periodic backup pass. */
    fun sweepAll(minAgeMs: Long): Triple<Int, Int, Int> {
        val dir = sourceDir()
        if (!dir.exists()) return Triple(0, 0, 0)

        val now = System.currentTimeMillis()
        var moved = 0
        var skipped = 0
        var failed = 0

        val files = dir.listFiles { f -> f.isFile && isMediaFile(f.name) } ?: emptyArray()
        for (f in files) {
            if (now - f.lastModified() < minAgeMs) {
                skipped++
                continue
            }
            if (moveFile(f.name)) moved++ else failed++
        }
        return Triple(moved, skipped, failed)
    }

    private fun uniqueDestFile(destDir: File, name: String): File {
        var candidate = File(destDir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(destDir, "${base}_$i$ext")
            i++
        }
        return candidate
    }

    private fun copyFile(src: File, dst: File): Boolean {
        return try {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyFile error for ${src.name}", e)
            false
        }
    }
}
