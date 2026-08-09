package com.fixer.fbphotomover

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs every 15 minutes as a SAFETY NET, not the primary mechanism.
 *
 * The primary, instant path is FileWatcherService (FileObserver), which
 * moves each file within milliseconds of Facebook finishing the write. This
 * worker only matters if that service ever got killed and hasn't restarted
 * yet -- it sweeps the folder for anything that slipped through.
 */
class FileMoveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "FBPhotoFixer"
        // Backup sweep can use a shorter age threshold than before, since
        // in the normal case there's nothing left here to find at all --
        // this only exists to catch what the observer missed.
        private const val MIN_FILE_AGE_MS = 5_000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val (moved, skipped, failed) = MediaMover.sweepAll(MIN_FILE_AGE_MS)
            Log.i(TAG, "Backup sweep complete. moved=$moved skipped=$skipped failed=$failed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Backup worker failed", e)
            Result.retry()
        }
    }
}
