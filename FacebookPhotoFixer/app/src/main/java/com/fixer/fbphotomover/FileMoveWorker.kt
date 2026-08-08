package com.fixer.fbphotomover

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Runs periodically via WorkManager.
 *
 * Logic:
 *  1. List files sitting in /storage/emulated/0/DCIM/Facebook/
 *  2. Skip anything that isn't an image/video, and skip anything that was
 *     modified very recently (Facebook may still be writing it).
 *  3. Copy each qualifying file into /storage/emulated/0/Facebook_Saved/
 *  4. Verify the copy (byte-for-byte length match) before touching the original.
 *  5. Delete the original from DCIM/Facebook so MediaStore/Google Photos
 *     drops it and never syncs it.
 */
class FileMoveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "FBPhotoFixer"
        const val SOURCE_PATH = "DCIM/Facebook"
        const val DEST_DIR_NAME = "Facebook_Saved"

        // Don't touch a file until it's been sitting untouched for this long,
        // so we never grab something Facebook is still writing to disk.
        private const val MIN_FILE_AGE_MS = 20_000L

        private val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif",
            "mp4", "mov", "3gp", "mkv"
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val root = Environment.getExternalStorageDirectory()
            val sourceDir = File(root, SOURCE_PATH)
            val destDir = File(root, DEST_DIR_NAME)

            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                Log.d(TAG, "Source folder does not exist yet, nothing to do.")
                return@withContext Result.success()
            }

            if (!destDir.exists()) {
                val created = destDir.mkdirs()
                Log.d(TAG, "Created destination dir: $created")
            }

            val candidates = sourceDir.listFiles { f ->
                f.isFile && MEDIA_EXTENSIONS.contains(f.extension.lowercase())
            } ?: emptyArray()

            var moved = 0
            var skipped = 0
            var failed = 0

            val now = System.currentTimeMillis()

            for (srcFile in candidates) {
                val age = now - srcFile.lastModified()
                if (age < MIN_FILE_AGE_MS) {
                    skipped++
                    continue
                }

                val destFile = uniqueDestFile(destDir, srcFile.name)

                val success = copyFile(srcFile, destFile)
                if (success && destFile.length() == srcFile.length()) {
                    val deleted = srcFile.delete()
                    if (deleted) {
                        moved++
                        Log.d(TAG, "Moved: ${srcFile.name} -> ${destFile.absolutePath}")
                    } else {
                        // Copy succeeded but original couldn't be removed.
                        // Remove the duplicate we just created and retry next run.
                        Log.w(TAG, "Copied but failed to delete original: ${srcFile.name}")
                        destFile.delete()
                        failed++
                    }
                } else {
                    // Copy failed or was incomplete -- clean up the partial file.
                    destFile.delete()
                    failed++
                    Log.w(TAG, "Failed to copy: ${srcFile.name}")
                }
            }

            Log.i(TAG, "Run complete. moved=$moved skipped=$skipped failed=$failed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }

    /** Avoids overwriting an existing file of the same name in the destination. */
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
