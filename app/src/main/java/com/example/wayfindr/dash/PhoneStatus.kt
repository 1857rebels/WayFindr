package com.example.wayfindr.dash

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Phone state the dash shows in its status bar, sent in the 1 Hz heartbeat exactly like the
 * original app (REForeGroundService.d.run(), decompiled):
 *  - 06 04 battery %  (+100 on the wire)
 *  - 06 0F charging   55 = charging or full, AA = on battery
 *  - 06 01 cell signal 01 = registered with a signal, 00 = none
 *  - 06 03 GPS on     55 / AA
 *  - 05 4C media volume, 05 1B call volume: 0x10 + level 0..10
 * The heartbeat used to carry fixed values (62 %, never charging, signal always on).
 */
data class PhoneStatus(
    val batteryPct: Int = 62,
    val charging: Boolean = false,
    val signal: Boolean = true,
    val gpsOn: Boolean = true,
    val mediaVolume: Int = 3,   // 0..10
    val callVolume: Int = 9,    // 0..10
) {
    companion object {
        /** Read the current phone state. Each field falls back to the default if unavailable. */
        fun read(context: Context): PhoneStatus {
            val d = PhoneStatus()
            val app = context.applicationContext
            val battery = runCatching {
                app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val pct = (app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val signal = runCatching {
                val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (tm.signalStrength?.level ?: 0) > 0 else d.signal
            }.getOrDefault(d.signal)
            val gps = runCatching {
                (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                    .isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(d.gpsOn)
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            return PhoneStatus(
                batteryPct = pct ?: d.batteryPct,
                charging = if (battery != null) charging else d.charging,
                signal = signal,
                gpsOn = gps,
                mediaVolume = am?.let { volumeLevel(it, AudioManager.STREAM_MUSIC) } ?: d.mediaVolume,
                callVolume = am?.let { volumeLevel(it, AudioManager.STREAM_VOICE_CALL) } ?: d.callVolume,
            )
        }

        private fun volumeLevel(am: AudioManager, stream: Int): Int? = runCatching {
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(stream) else 0
            volumeLevel(am.getStreamVolume(stream), min, am.getStreamMaxVolume(stream))
        }.getOrNull()

        /** Original app's bucketing: 0 when silent, else 1..10 by tenths of the stream's range. */
        fun volumeLevel(volume: Int, min: Int, max: Int): Int {
            if (volume <= 0 || max <= min) return 0
            val step = (max - min) / 10f
            return Math.ceil(volume / step.toDouble()).toInt().coerceIn(1, 10)
        }
    }
}
