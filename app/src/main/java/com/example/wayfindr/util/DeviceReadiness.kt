package com.example.wayfindr.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Cross-OEM background-execution readiness.
 *
 * Stock Android keeps a foreground service alive with the screen off, but OxygenOS, Samsung
 * One UI, MIUI, ColorOS/Realme etc. aggressively kill apps that aren't exempt from battery
 * optimization — which silently stops the dash stream (the whole point of the app) the moment
 * the screen goes off. The behaviour was device-dependent because nothing here asked for the
 * exemption; this makes it uniform.
 *
 * The `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog is a standard framework intent and
 * behaves the same on every OEM. The OEM-specific "Autostart" / "Sleeping apps" toggles can't be
 * granted programmatically — only the app's settings page can be opened for the rider to flip them.
 */
object DeviceReadiness {

    private const val PREFS = "wayfindr_readiness"
    private const val KEY_BATTERY_ASKED = "battery_exemption_asked"

    /** True if the OS won't throttle/kill us in the background (needed for screen-off streaming). */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * Whether we've already shown the rider the battery-exemption dialog once. We fold the request
     * into the first Connect tap and remember it here, so we never re-prompt on later connects even
     * if they declined (they can still grant it later from system settings).
     */
    fun batteryExemptionAsked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BATTERY_ASKED, false)

    fun markBatteryExemptionAsked(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BATTERY_ASKED, true).apply()
    }

    /**
     * Standard system dialog to exempt the app from battery optimization — uniform across OEMs.
     * (Lint flags BatteryLife for Play Store apps; Wayfindr is sideloaded per-rider, and the
     * exemption is essential for the screen-off use case, so the prompt is justified.)
     */
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app's system settings page — every OEM exposes its battery/autostart controls here. */
    fun appSettingsIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * True if the app has "Allow all the time" location access. Essential on Android 10+ to
     * prevent the OS from throttling GPS updates to just a few times per hour when the screen
     * is off (background state).
     */
    fun hasBackgroundLocation(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return true
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Location services (the master toggle, not the permission) must be ON for WiFi scan results
     * to be returned on most OEMs — that's how a not-yet-paired dash SSID is discovered. Once the
     * dash is learned, scanning isn't needed, so this only matters on first pairing.
     */
    fun locationServicesEnabled(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    }.getOrDefault(true)
}
