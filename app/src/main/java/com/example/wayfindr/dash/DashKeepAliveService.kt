package com.example.wayfindr.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.wayfindr.MainActivity
import com.example.wayfindr.R

/**
 * Foreground service that keeps Wayfindr streaming to the dash while the
 * phone screen is OFF — the whole reason this app exists.
 *
 * It does NOT own the streaming pipeline (that stays in DashViewModel, which
 * survives Activity onStop). Its job is purely to stop Android from freezing
 * the process:
 *   • PARTIAL_WAKE_LOCK  — CPU keeps running the 4 Hz encoder/RTP loop with
 *     the screen off (otherwise Doze suspends the coroutine → dash times out).
 *   • WifiLock (LOW_LATENCY / HIGH_PERF) — stops WiFi power-save from tearing
 *     down the WifiNetworkSpecifier link to the Console hotspot.
 *   • Ongoing notification — required for a foreground service; also lets the
 *     rider re-open the app.
 */
class DashKeepAliveService : Service() {
    companion object {
        private const val TAG          = "DashKeepAlive"
        private const val CHANNEL_ID   = "wayfindr_dash"
        private const val NOTIF_ID     = 4701
        const val ACTION_START = "com.example.wayfindr.DASH_START"
        const val ACTION_STOP  = "com.example.wayfindr.DASH_STOP"

        fun start(context: Context) {
            val i = Intent(context, DashKeepAliveService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DashKeepAliveService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else        -> startForegroundLocks()
        }
        // START_STICKY: if the OS kills us under memory pressure, restart so the
        // ride keeps streaming.
        return START_STICKY
    }

    private fun startForegroundLocks() {
        createChannel()
        // The LOCATION type is what lets GPS keep updating with the screen off —
        // without it Android 14+ freezes location for backgrounded apps and the
        // rider marker sticks at its first fix. BUT on Android 14+ starting an FGS
        // with the LOCATION type while ACCESS_FINE/COARSE_LOCATION is NOT granted
        // throws SecurityException and crashes the process. This service can be
        // started from the "Start navigation" flow before the rider has ever granted
        // location (the Dash screen is the only place that requests it), so only
        // claim the LOCATION type when the permission is actually held. CONNECTED_DEVICE
        // is satisfied by CHANGE_NETWORK_STATE (a normal, install-time permission), so
        // it's always safe.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else Log.w(TAG, "Location not granted — starting FGS without the LOCATION type")
                startForeground(NOTIF_ID, buildNotification(), type)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            acquireLocks()
            Log.i(TAG, "Foreground service up — wake+wifi locks held")
        } catch (e: Exception) {
            // Never let a foreground-service start crash the app (e.g. the OS denying
            // the type, or a background-start window restriction). The streaming
            // pipeline lives in DashViewModel and can still run; we just lose the
            // wake/wifi locks (screen-off resilience), which the rider can recover
            // from the Dash screen once permissions are granted.
            Log.e(TAG, "Foreground service start failed — continuing without locks", e)
            stopSelf()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wayfindr:dash").apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()

        // LOW_LATENCY keeps the radio from entering power-save states that cause jitter
        // or disconnects during active streaming. HIGH_PERF is a lighter hint;
        // LOW_LATENCY is a stronger guarantee on Android 10+ devices.
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(mode, "wayfindr:dash").apply {
                setReferenceCounted(false)
            }
        }
        if (wifiLock?.isHeld == false) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        Log.i(TAG, "Foreground service stopped — locks released")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Dash streaming", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Keeps the map streaming to the Console Dash" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("WayfindR — streaming to dash")
            .setContentText("Map is live on the Console. Screen can stay off.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
