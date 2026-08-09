package com.fixer.fbphotomover

import android.content.ComponentName
import android.content.Intent
import android.os.Build

/**
 * Every Android OEM that heavily reskins Android (MIUI/HyperOS, ColorOS/
 * Realme UI, OriginOS/FuntouchOS, EMUI/MagicOS, One UI...) hides its own
 * "let this app run in the background" toggle in a different, undocumented
 * place. There's no public Android API for any of it -- the OS-level
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS call (already used elsewhere in this
 * app) is the only part that's universal.
 *
 * This object detects which vendor the phone is from and returns the right
 * screen to send the user to, with several known component names tried in
 * order (OEMs rename these between firmware versions) and a safe generic
 * fallback for anything unrecognized -- including Samsung, Pixel, and most
 * other brands, where no extra step is normally needed at all.
 */
object VendorAutostart {

    enum class Vendor {
        XIAOMI,          // MIUI / HyperOS (Xiaomi, Redmi, POCO)
        REALME_FAMILY,   // ColorOS / Realme UI (Realme, OPPO, newer OnePlus)
        VIVO_FAMILY,     // OriginOS / FuntouchOS (vivo, iQOO)
        HUAWEI_FAMILY,   // EMUI / MagicOS (Huawei, Honor)
        GENERIC          // Samsung, Pixel, stock Android, anything else
    }

    fun detect(): Vendor {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val signature = "$manufacturer $brand"

        return when {
            signature.contains("xiaomi") || signature.contains("redmi") || signature.contains("poco") ->
                Vendor.XIAOMI

            signature.contains("realme") || signature.contains("oppo") || signature.contains("oneplus") ->
                Vendor.REALME_FAMILY

            signature.contains("vivo") || signature.contains("iqoo") ->
                Vendor.VIVO_FAMILY

            signature.contains("huawei") || signature.contains("honor") ->
                Vendor.HUAWEI_FAMILY

            else -> Vendor.GENERIC
        }
    }

    /** Human-readable name shown in the UI. */
    fun displayName(vendor: Vendor): String = when (vendor) {
        Vendor.XIAOMI -> "Xiaomi / MIUI / HyperOS"
        Vendor.REALME_FAMILY -> "Realme / OPPO / ColorOS"
        Vendor.VIVO_FAMILY -> "vivo / iQOO"
        Vendor.HUAWEI_FAMILY -> "Huawei / Honor"
        Vendor.GENERIC -> "this phone"
    }

    /**
     * Ordered list of candidate intents to try for the vendor's own
     * autostart / background-app permission screen. The caller should try
     * each in order and stop at the first one that resolves, since exact
     * component names vary between firmware versions of the same OEM.
     */
    fun candidateIntents(vendor: Vendor): List<Intent> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.securitycenter.Main")
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )

        Vendor.REALME_FAMILY -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
                )
            )
        )

        Vendor.VIVO_FAMILY -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            )
        )

        Vendor.HUAWEI_FAMILY -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
        )

        Vendor.GENERIC -> emptyList()
    }

    /** Explanatory copy shown under the vendor button, tailored per OEM. */
    fun stepsText(vendor: Vendor): String = when (vendor) {
        Vendor.XIAOMI ->
            "1. Security app -> App Battery Saver -> find this app -> set to \"No restrictions\".\n" +
                "2. Security app -> Permissions -> Autostart -> enable Autostart for this app.\n" +
                "3. Recent apps screen -> swipe this app's card down (or tap the lock icon) to lock it."

        Vendor.REALME_FAMILY ->
            "1. Settings -> Battery -> App battery management -> find this app -> allow \"Background running\" / \"Allow auto-launch\".\n" +
                "2. Security Center -> Privacy Permissions -> Startup Manager -> enable Autostart for this app.\n" +
                "3. Recent apps screen -> tap the lock icon on this app's card so it doesn't get swept away."

        Vendor.VIVO_FAMILY ->
            "1. i Manager -> App manager -> Autostart -> enable it for this app.\n" +
                "2. i Manager -> Battery -> High background power consumption / Background app refresh -> allow this app.\n" +
                "3. Recent apps screen -> lock this app's card."

        Vendor.HUAWEI_FAMILY ->
            "1. Phone Manager (Optimizer) -> App launch -> find this app -> switch it to Manage manually, then enable Auto-launch, Secondary launch, and Run in background.\n" +
                "2. Recent apps screen -> lock this app's card."

        Vendor.GENERIC ->
            "Your phone's manufacturer usually doesn't hide an extra autostart toggle the way Xiaomi, Realme, vivo, and Huawei do. " +
                "The \"Disable Battery Optimization\" button above is normally all you need here."
    }

    /** Label for the vendor action button. Empty string means "hide the button". */
    fun buttonLabel(vendor: Vendor): String = when (vendor) {
        Vendor.XIAOMI -> "Open MIUI Security App"
        Vendor.REALME_FAMILY -> "Open Realme / ColorOS Startup Manager"
        Vendor.VIVO_FAMILY -> "Open vivo i Manager"
        Vendor.HUAWEI_FAMILY -> "Open Huawei Phone Manager"
        Vendor.GENERIC -> ""
    }
}
