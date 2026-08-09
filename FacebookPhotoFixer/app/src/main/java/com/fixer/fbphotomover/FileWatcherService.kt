package com.fixer.fbphotomover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground service that watches DCIM/Facebook in real time with a
 * FileObserver and moves each file the instant it's finished writing --
 * typically within milliseconds, well before Google Photos' own MediaStore
 * scan picks it up.
 *
 * Why CLOSE_WRITE and not CREATE:
 * CREATE fires the moment Facebook opens a new empty file, before any bytes
 * are written. Moving at that point would grab a 0-byte / partial file.
 * CLOSE_WRITE fires only once the app that was writing to the file closes
 * it, which means the write is complete and it's safe to move.
 */
class FileWatcherService : Service() {

    companion object {
        const val CHANNEL_ID = "fb_photo_fixer_watch"
        const val NOTIFICATION_ID = 1001
        const val TAG = "FBPhotoFixer"
    }

    private var facebookDirObserver: FileObserver? = null
    private var dcimDirObserver: FileObserver? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startWatching()
        // START_STICKY: ask Android to recreate the service if it gets
        // killed under memory pressure (MIUI is aggressive about this).
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        facebookDirObserver?.stopWatching()
        facebookDirObserver = null
        dcimDirObserver?.stopWatching()
        dcimDirObserver = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---- Foreground notification plumbing ----

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notif_watching))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---- FileObserver wiring ----

    private fun startWatching() {
        val fbDir = MediaMover.sourceDir()
        if (fbDir.exists() && fbDir.isDirectory) {
            watchFacebookDir(fbDir)
        } else {
            // Facebook creates DCIM/Facebook lazily on first save. Watch DCIM
            // itself until that folder appears, then switch targets.
            watchParentForFacebookDirCreation()
        }

        // Catch anything that already landed there in the gap before this
        // service (re)started -- e.g. right after a reboot or a kill/restart.
        ioExecutor.execute {
            val (moved, _, _) = MediaMover.sweepAll(minAgeMs = 0L)
            if (moved > 0) Log.i(TAG, "Startup catch-up moved $moved file(s)")
        }
    }

    private fun watchParentForFacebookDirCreation() {
        val dcimDir = File(Environment.getExternalStorageDirectory(), "DCIM")
        if (!dcimDir.exists()) return

        dcimDirObserver = createObserver(dcimDir, FileObserver.CREATE) { path ->
            if (path == "Facebook") {
                mainHandler.post {
                    dcimDirObserver?.stopWatching()
                    dcimDirObserver = null
                    watchFacebookDir(MediaMover.sourceDir())
                }
            }
        }
        dcimDirObserver?.startWatching()
        Log.i(TAG, "DCIM/Facebook doesn't exist yet -- watching DCIM/ for it to appear")
    }

    private fun watchFacebookDir(dir: File) {
        facebookDirObserver?.stopWatching()
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        facebookDirObserver = createObserver(dir, mask) { path -> handleEvent(path) }
        facebookDirObserver?.startWatching()
        Log.i(TAG, "Watching ${dir.absolutePath} in real time")
        mainHandler.post { updateNotification(getString(R.string.notif_watching)) }
    }

    private fun createObserver(dir: File, mask: Int, onEvent: (String?) -> Unit): FileObserver {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(path)
            }
        }
    }

    private fun handleEvent(path: String?) {
        if (path.isNullOrBlank()) return
        // Do the actual file I/O off the observer's callback thread.
        ioExecutor.execute {
            val moved = MediaMover.moveFile(path)
            if (moved) {
                mainHandler.post {
                    updateNotification(getString(R.string.notif_last_moved, path))
                }
            }
        }
    }
}
