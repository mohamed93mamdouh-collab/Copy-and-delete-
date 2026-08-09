package com.fixer.fbphotomover

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fixer.fbphotomover.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Whether granted or not, proceed -- the service can still run
            // without a visible notification, though we always want to try.
            startWatcherServiceIfReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantPermissionButton.setOnClickListener { requestAllFilesAccess() }
        binding.batteryButton.setOnClickListener { requestBatteryOptimizationExemption() }
        binding.miuiSecurityButton.setOnClickListener { openMiuiSecurityApp() }

        // Creator info: GitHub placeholder link. Replace the URL below with
        // your real profile once you have one -- the text shown comes from
        // R.string.creator_github_placeholder in strings.xml.
        binding.creatorGithubText.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/your-username")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No browser app found to open this link.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val granted = hasAllFilesAccess()
        if (granted) {
            binding.statusLabel.text = getString(R.string.status_granted)
            binding.grantPermissionButton.isEnabled = false
            binding.grantPermissionButton.text = "All Files Access — Granted"

            // Backup periodic sweep (safety net).
            WorkScheduler.schedule(this)

            // Primary real-time path: needs the notification permission on
            // Android 13+ before we start the foreground service.
            ensureNotificationPermissionThenStartService()
        } else {
            binding.statusLabel.text = getString(R.string.status_not_granted)
            binding.grantPermissionButton.isEnabled = true
            binding.grantPermissionButton.text = getString(R.string.btn_grant_permission)
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun ensureNotificationPermissionThenStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                startWatcherServiceIfReady()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startWatcherServiceIfReady()
        }
    }

    private fun startWatcherServiceIfReady() {
        if (!hasAllFilesAccess()) return
        val intent = Intent(this, FileWatcherService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * Opens the system settings screen for "All files access" scoped to this
     * app. Falls back to the general all-files-access list if the
     * app-specific screen isn't available on a given OEM build.
     */
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Not needed on this Android version.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Couldn't open the permission screen automatically. " +
                        "Go to Settings → Apps → FB Photo Fixer → Permissions → Files and media.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already exempted from battery optimization.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "Couldn't open battery settings on this device.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openMiuiSecurityApp() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securitycenter.Main"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )

        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // try next candidate
            }
        }

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Open Security app → Permissions → Autostart manually to enable it for this app.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
