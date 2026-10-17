package com.example.wayfindr.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import com.example.wayfindr.util.Dbg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** GPS position via LocationManager (no Play Services dependency). */
class LocationTracker(context: Context) {
    companion object {
        private const val TAG = "LocationTracker"
        // While a GPS fix is younger than this, ignore coarse NETWORK fixes entirely.
        private const val GPS_STALE_MS = 10_000L
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    private val listener = LocationListener { loc ->
        val cur = _location.value
        if (acceptFix(cur, loc)) {
            _location.value = loc
            Dbg.d(TAG) { "fix ${loc.provider} acc=${loc.accuracy} (${loc.latitude},${loc.longitude})" }
        } else {
            Log.d(TAG, "REJECT ${loc.provider} acc=${loc.accuracy} dt=${loc.time - (cur?.time ?: 0)}ms")
        }
    }

    /**
     * Decide whether [loc] should replace [cur]. This is the FIRST line against teleport/jitter:
     * a clean fix stream is what the camera smoothing and predictor assume.
     *  - GPS always wins over a coarse NETWORK fix while a recent GPS fix exists (else the marker
     *    jumps to a cell-tower estimate km away — the classic screen-off failure).
     *  - Physically impossible jumps (>306 km/h sustained) are dropped outright.
     *  - Accuracy-+speed-aware outlier gate: a jump well beyond what our speed and the two fixes'
     *    error radii can explain is a GPS glitch — reject it so it never reaches the map. Recovers
     *    after a few rejects (or a long gap) so a real reposition still resyncs.
     */
    private fun acceptFix(cur: Location?, loc: Location): Boolean {
        if (cur == null) { rejectStreak = 0; return true }
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (!isGps && cur.provider == LocationManager.GPS_PROVIDER &&
            loc.time - cur.time < GPS_STALE_MS
        ) return false
        if (loc.time < cur.time) return false
        val dt = (loc.time - cur.time) / 1000.0
        val jump = cur.distanceTo(loc)
        // A last-known fix can be stale when navigation starts. After a long gap there is no
        // reliable way to judge the distance or speed between fixes, so accept the fresh fix to
        // resynchronize instead of rejecting every update behind the hard jump guard.
        if (dt > GPS_STALE_MS / 1000.0) {
            rejectStreak = 0
            return true
        }
        // Hard cap: nothing on this bike sustains 85 m/s — that's a corrupt fix.
        if (dt > 0 && jump > 200f && jump / dt > 85.0) return reject()
        // Outlier gate (skipped for long gaps, which legitimately cover ground, and after a few
        // rejects so we resync to a real reposition instead of freezing on the wrong spot).
        if (dt in 0.0..8.0 && rejectStreak < 3) {
            val plausibleSpeed = maxOf(cur.speed, loc.speed).coerceAtLeast(1f) // m/s, floored
            val expected = plausibleSpeed * dt
            val noise = (loc.accuracy + cur.accuracy)                          // combined error radius
            // Urban canyon / building facade: reported accuracy is often optimistic while the
            // point jumps tens of metres. A tight gate dropped those fixes, starved the age
            // clock, and flashed "GPS lost" even though GPS was still talking.
            val gate = expected + noise * 2.5 + 25f
            if (jump > gate && jump > 40f) return reject()
        }
        rejectStreak = 0
        return true
    }

    // Consecutive outlier rejects — bounded so a genuine reposition can't lock the marker out.
    private var rejectStreak = 0
    private fun reject(): Boolean { rejectStreak++; return false }

    private var running = false
    private var handlerThread: android.os.HandlerThread? = null

    /** Requires ACCESS_FINE_LOCATION at runtime; no-ops without it. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        try {
            _location.value = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            // Move location updates to a dedicated background thread. Using the main looper
            // can lead to throttling when the screen is off and the UI thread is de-prioritized.
            handlerThread = android.os.HandlerThread("LocationTracker").apply { start() }
            val looper = handlerThread!!.looper
            
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 500L, 0f, listener, looper)
                }
            }
            running = true
            Log.i(TAG, "Location updates started on background thread")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing — GPS disabled")
        } catch (e: Exception) {
            Log.w(TAG, "GPS start failed: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        lm.removeUpdates(listener)
        handlerThread?.quitSafely()
        handlerThread = null
        running = false
    }

    /** Best last-known fix without starting updates (for routing before connecting). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): android.location.Location? = try {
        _location.value
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    } catch (e: Exception) {
        null
    }
}
