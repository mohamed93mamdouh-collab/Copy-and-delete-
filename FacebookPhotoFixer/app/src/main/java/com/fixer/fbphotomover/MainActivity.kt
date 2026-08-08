package com.fixer.fbphotomover

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fixer.fbphotomover.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantPermissionButton.setOnClickListener { requestAllFilesAccess() }
        binding.batteryButton.setOnClickListener { requestBatteryOptimizationExemption() }
        binding.miuiSecurityButton.setOnClickListener { openMiuiSecurityApp() }
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
            // Permission is in place: make sure the recurring job is scheduled.
            WorkScheduler.schedule(this)
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
            // Below Android 11, classic runtime storage permissions would apply.
            // minSdk is 26, and this app targets 30+ behavior, so this branch
            // is effectively legacy fallback only.
            true
        }
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
                // Some MIUI builds don't expose the app-specific screen directly.
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

    /** Asks the system to exempt this app from Doze / App Standby battery restrictions. */
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

    /**
     * MIUI/HyperOS hides Autostart + per-app battery saver level inside its own
     * "Security" app rather than stock Android settings, and there's no public
     * API to control them directly -- the best an app can do is deep-link into
     * that app's permission editor and let the user flip the switches.
     */
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

        // Fall back to this app's normal system app-info screen.
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
