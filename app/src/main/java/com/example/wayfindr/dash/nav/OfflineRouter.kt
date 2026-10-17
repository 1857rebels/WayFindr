package com.example.wayfindr.dash.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import btools.routingapp.IBRouterService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Offline routing on the phone through the **BRouter** app (F-Droid / Play: btools.routingapp).
 * BRouter does the routing from its own downloaded map segments (.rd5, chosen in BRouter's
 * download manager) and exposes it to other apps as a bound service — no internet needed.
 *
 * The track comes back as GeoJSON with "voicehints" (turn instructions keyed to a point index),
 * which are translated to Google maneuver names ([googleManeuverFor]) so the dash glyphs,
 * "then …" turns, voice and chime work unchanged. No traffic data.
 */
object OfflineRouter {
    private const val TAG = "OfflineRouter"
    const val BROUTER_PACKAGE = "btools.routingapp"
    private const val BROUTER_SERVICE = "btools.routingapp.BRouterService"
    /** BRouter's stock car profile — fastest roads; the closest stock fit for a motorcycle. */
    const val PROFILE = "car-fast"
    /** Used when car-fast fails (see [route]). Stock BRouter profile, a bit slower roads. */
    const val FALLBACK_PROFILE = "car-eco"

    private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /** Is the BRouter app installed? */
    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(BROUTER_PACKAGE, 0); true }.getOrDefault(false)

    /** Last failure reason, for the ride log / UI ("not installed", "segment missing", …). */
    @Volatile var lastError: String? = null
        private set

    suspend fun route(from: GeoPoint, to: GeoPoint, intermediates: List<GeoPoint> = emptyList()): Route? {
        val ctx = appContext ?: return fail("not initialised")
        if (!isInstalled(ctx)) return fail("BRouter app is not installed")
        val points = listOf(from) + intermediates + to
        var text = withContext(Dispatchers.IO) { callService(ctx, points) } ?: return null
        com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.OFFLINE_ROUTES)
        if (!text.trimStart().startsWith("{")) {
            // car-fast's final "re-tracking" pass fails on some long routes in BRouter 1.7.10
            // (Bangalore → Udupi, 2026-10-17: every attempt, with or without turn hints), while
            // car-eco routes the same trip in ~5 s. So any car-fast failure retries with car-eco.
            Log.i(TAG, "car-fast failed (${text.take(120)}) — retrying with $FALLBACK_PROFILE")
            val retry = withContext(Dispatchers.IO) {
                callService(ctx, points, Bundle().apply { putString("profile", FALLBACK_PROFILE) })
            } ?: return null
            com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.OFFLINE_ROUTES)
            text = retry
        }
        if (!text.trimStart().startsWith("{")) return fail("BRouter: ${text.take(200)}")
        return runCatching { parse(text, points.drop(1)) }.getOrElse { fail("unreadable BRouter result (${it.message})") }
            ?.also { lastError = null }
    }

    /**
     * Debug builds only: `adb shell am broadcast -a com.wayfindr.BROUTER_PROBE --es lonlats
     * "lon,lat|lon,lat" [--es profile car-eco] [--es timode 0]` logs BRouter's raw answer under
     * the OfflineRouter tag — for finding which request settings BRouter accepts.
     */
    fun registerDebugProbe(context: Context) {
        if (!com.example.wayfindr.BuildConfig.DEBUG) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pts = intent.getStringExtra("lonlats")?.split('|')?.map {
                    val (lon, lat) = it.split(','); GeoPoint(lat.toDouble(), lon.toDouble())
                } ?: return
                val extra = Bundle().apply {
                    intent.getStringExtra("profile")?.let { putString("profile", it) }
                    intent.getStringExtra("timode")?.let { putString("timode", it) }
                }
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    val res = callService(c.applicationContext, pts, extra)
                    Log.i(TAG, "PROBE ${intent.getStringExtra("profile") ?: PROFILE} timode=${intent.getStringExtra("timode") ?: "3"} → ${res?.take(300)}")
                }
            }
        }
        val filter = android.content.IntentFilter("com.wayfindr.BROUTER_PROBE")
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(rx, filter)
    }

    private suspend fun callService(ctx: Context, points: List<GeoPoint>, overrides: Bundle? = null): String? {
        val connected = CompletableDeferred<IBRouterService?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(IBRouterService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { connected.complete(null) }
        }
        val intent = Intent().setClassName(BROUTER_PACKAGE, BROUTER_SERVICE)
        val bound = runCatching { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) return fail("couldn't connect to the BRouter service (open BRouter once?)")
        return try {
            val service = withTimeoutOrNull(8_000) { connected.await() } ?: return fail("BRouter service didn't answer")
            val params = Bundle().apply {
                putDoubleArray("lats", DoubleArray(points.size) { points[it].lat })
                putDoubleArray("lons", DoubleArray(points.size) { points[it].lng })
                putString("profile", PROFILE)
                putString("trackFormat", "json")
                putString("timode", "3")              // 3 = OsmAnd-style voice hints (turns)
                // Seconds (BRouter's default is 60). Long routes on a phone can need more.
                putString("maxRunningTime", "180")
                // Every other key is handed to BRouter's routing parameters, so nothing else is sent.
                overrides?.let { putAll(it) }
            }
            val t0 = System.currentTimeMillis()
            val result = runCatching { service.getTrackFromParams(params) }.getOrElse { return fail("BRouter call failed: ${it.message}") }
            val msg = "offline route (BRouter ${params.getString("profile")}, ${points.size} points) in ${System.currentTimeMillis() - t0} ms: " +
                (result?.takeIf { it.trimStart().startsWith("{") }?.let { "${it.length} chars" } ?: (result?.take(120) ?: "null"))
            Log.i(TAG, msg)
            com.example.wayfindr.data.RideDiagnostics.log("route", msg)
            result ?: fail("BRouter returned nothing")
        } finally {
            runCatching { ctx.unbindService(conn) }
        }
    }

    private fun fail(why: String): Nothing? {
        lastError = why
        Log.w(TAG, why)
        com.example.wayfindr.data.RideDiagnostics.log("route", "offline routing failed: $why")
        return null
    }

    private fun parse(json: String, stops: List<GeoPoint>): Route? {
        val feature = JSONObject(json).getJSONArray("features").getJSONObject(0)
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
        val geometry = ArrayList<GeoPoint>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            geometry.add(GeoPoint(c.getDouble(1), c.getDouble(0)))   // [lon, lat, ele]
        }
        if (geometry.size < 2) return null
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])

        val props = feature.getJSONObject("properties")
        val maneuvers = ArrayList<Maneuver>()
        maneuvers.add(Maneuver(ManeuverType.DEPART, "Head out", geometry[0], 0.0, googleManeuver = "DEPART"))
        props.optJSONArray("voicehints")?.let { hints ->
            for (i in 0 until hints.length()) {
                val h = hints.getJSONArray(i)
                val idx = h.getInt(0).coerceIn(0, geometry.lastIndex)
                val cmd = h.getInt(1)
                val exit = h.optInt(2, 0)
                val google = googleManeuverFor(cmd) ?: continue
                maneuvers.add(Maneuver(
                    type = ManeuverType.fromGoogle(google),
                    instruction = instructionFor(cmd, exit),
                    location = geometry[idx],
                    cumulativeMeters = cum[idx],
                    googleManeuver = google,
                ))
            }
        }
        // BRouter returns one line for all stops: find each stop along it, in order, so multi-stop
        // progress and per-stop distance keep working.
        var from = 0
        val legEnds = stops.mapIndexed { i, stop ->
            if (i == stops.lastIndex) return@mapIndexed cum.last()
            var best = from; var bestD = Double.MAX_VALUE
            for (k in from until geometry.size) {
                val d = GeoPoint.distMeters(stop, geometry[k])
                if (d < bestD) { bestD = d; best = k }
            }
            from = best
            cum[best]
        }
        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = props.optString("track-length").toDoubleOrNull() ?: cum.last(),
            totalSeconds = props.optString("total-time").toDoubleOrNull() ?: (cum.last() / 11.0),
            cumulative = cum,
            legEndCumulative = legEnds,
            traffic = emptyList(),
        )
    }

    /**
     * BRouter voice-hint command (VoiceHint.java) → Google maneuver name used for dash glyphs.
     * null = not a turn to show (beeline, off-route, end).
     */
    fun googleManeuverFor(cmd: Int): String? = when (cmd) {
        1 -> "STRAIGHT"                 // C: continue
        2 -> "TURN_LEFT"                // TL
        3 -> "TURN_SLIGHT_LEFT"         // TSLL
        4 -> "TURN_SHARP_LEFT"          // TSHL
        5 -> "TURN_RIGHT"               // TR
        6 -> "TURN_SLIGHT_RIGHT"        // TSLR
        7 -> "TURN_SHARP_RIGHT"         // TSHR
        8 -> "FORK_LEFT"                // KL: keep left
        9 -> "FORK_RIGHT"               // KR: keep right
        10, 11 -> "UTURN_LEFT"          // TLU, TU
        12 -> "UTURN_RIGHT"             // TRU
        14, 15 -> "ROUNDABOUT_LEFT"     // RNDB, RNLB (dash shows clockwise, see DashGlyph)
        17 -> "RAMP_LEFT"               // EL: exit left
        18 -> "RAMP_RIGHT"              // ER: exit right
        else -> null                    // 13 off-route, 16 beeline, 100 end, unknown
    }

    private fun instructionFor(cmd: Int, exit: Int): String = when (cmd) {
        1 -> "Continue"
        2 -> "Turn left"; 3 -> "Slight left"; 4 -> "Sharp left"
        5 -> "Turn right"; 6 -> "Slight right"; 7 -> "Sharp right"
        8 -> "Keep left"; 9 -> "Keep right"
        10, 11, 12 -> "Make a U-turn"
        14, 15 -> if (exit > 0) "At the roundabout, take exit $exit" else "Enter the roundabout"
        17 -> "Take the exit on the left"; 18 -> "Take the exit on the right"
        else -> "Continue"
    }
}
