package com.fixer.fbphotomover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

            if (hasAccess) {
                // Backup periodic sweep.
                WorkScheduler.schedule(context)

                // Primary real-time watcher. Notification permission (if
                // applicable) was already granted in a previous app launch,
                // or the service just runs without a visible notification.
                val serviceIntent = Intent(context, FileWatcherService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
