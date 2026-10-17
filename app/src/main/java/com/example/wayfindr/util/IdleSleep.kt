package com.example.wayfindr.util

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Puts the app to sleep when it isn't being used: no app screen visible AND no link to the dash's
 * Wi-Fi for [IDLE_MS] (10 min). Asleep means nothing keeps running in the background:
 *  - DashViewModel stops any connect/reconnect attempt, GPS, wake/Wi-Fi locks and loops,
 *  - RouteViewModel stops its GPS updates,
 *  - the notification listener is unbound, so Android stops waking the app for every
 *    notification on the phone (it's only needed for music/calls on the dash).
 * Opening the app, or the dash link coming up, wakes everything again.
 *
 * The 10-minute timer is an inexact AlarmManager alarm, not an in-app timer: Android freezes a
 * backgrounded app's process within seconds, and a frozen process never runs its own timers.
 * The alarm briefly thaws it to do the shutdown. Doze may deliver it a few minutes late.
 *
 * Components watch [asleep]; the notification listener is handled here directly.
 */
object IdleSleep {
    private const val TAG = "IdleSleep"
    const val IDLE_MS = 10 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Application
    private var startedActivities = 0
    private var dashLinked = false
    private var alarmSet = false

    private val _asleep = MutableStateFlow(false)
    /** True while the app is asleep (see class doc). */
    val asleep: StateFlow<Boolean> = _asleep.asStateFlow()

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                // Also re-binds the listener after a reinstall, which leaves it unbound.
                if (startedActivities == 1) rebindNotificationListener()
                reevaluate()
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                reevaluate()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // A process started by the system with no screen (e.g. to bind the notification
        // listener) counts as idle from the start.
        reevaluate()
        registerDebugTrigger()
    }

    /**
     * Debug builds only: `adb shell am broadcast -a com.wayfindr.IDLE_SLEEP_NOW -p <package>`
     * acts as if the 10-minute alarm went off, to test sleep without waiting.
     */
    private fun registerDebugTrigger() {
        if (!com.example.wayfindr.BuildConfig.DEBUG) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { Log.i(TAG, "debug: sleep now"); onAlarm() }
        }
        val filter = android.content.IntentFilter("com.wayfindr.IDLE_SLEEP_NOW")
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(rx, filter)
    }

    /** Called with whether the phone is joined to the dash's Wi-Fi (or streaming to it). */
    fun setDashLinked(linked: Boolean) {
        main.post {
            if (dashLinked == linked) return@post
            dashLinked = linked
            if (linked && _asleep.value) rebindNotificationListener()
            reevaluate()
        }
    }

    private fun inUse() = startedActivities > 0 || dashLinked

    private fun reevaluate() {
        if (inUse()) {
            if (alarmSet) Log.i(TAG, "in use (screens=$startedActivities, dash=$dashLinked) — sleep cancelled")
            cancelAlarm()
            if (_asleep.value) wake()
        } else if (!_asleep.value && !alarmSet) {
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + IDLE_MS, alarmIntent())
            alarmSet = true
            Log.i(TAG, "idle — sleeping in ${IDLE_MS / 60_000} min unless the app or dash is used")
        }
    }

    /** The alarm went off (see [AlarmReceiver]). */
    internal fun onAlarm() {
        alarmSet = false
        if (!inUse() && !_asleep.value) enterSleep()
    }

    private fun enterSleep() {
        Log.i(TAG, "asleep: no screen and no dash link for ${IDLE_MS / 60_000} min")
        cancelAlarm()
        _asleep.value = true
        com.example.wayfindr.media.WayfindrNotificationListener.unbindNow()
    }

    private fun wake() {
        Log.i(TAG, "awake")
        _asleep.value = false
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarm() {
        // Always cancel, even if this process didn't set it: an alarm that restarted a killed
        // process gets a fresh alarm from init(), which must not survive the sleep.
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent())
        alarmSet = false
    }

    private fun rebindNotificationListener() {
        runCatching {
            if (com.example.wayfindr.media.MediaInfoProvider.isAccessGranted(app)) {
                NotificationListenerService.requestRebind(
                    ComponentName(app, com.example.wayfindr.media.WayfindrNotificationListener::class.java))
            }
        }
    }

    /** Receives the 10-minute idle alarm (registered in the manifest, not exported). */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onAlarm()
    }
}
