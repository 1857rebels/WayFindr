package com.example.wayfindr.dash.nav

import android.util.Log
import com.example.wayfindr.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Free, keyless routing from OpenStreetMap data: the public Valhalla server run by FOSSGIS
 * (https://valhalla1.openstreetmap.de), with Valhalla's **motorcycle** costing. Used instead of
 * the Google Routes API when "Free routing" is on in Settings.
 *
 * Differences from Google: no live traffic (ETA uses typical road speeds, no congestion colours)
 * and OSM road data. The server is a fair-use community service — only planning and reroute
 * calls go here; the 2-minute traffic refresh is skipped in free mode.
 *
 * Output is the same [Route] model, with Valhalla maneuvers translated to Google maneuver names
 * ([googleManeuverFor]) so the dash glyphs, "then …" turns, voice and chime work unchanged.
 */
object FreeRouter {
    private const val TAG = "FreeRouter"
    private const val ENDPOINT = "https://valhalla1.openstreetmap.de/route"
    private val USER_AGENT = "Wayfindr/${BuildConfig.VERSION_NAME} (personal motorcycle navigation app; Android)"

    suspend fun route(from: GeoPoint, to: GeoPoint, intermediates: List<GeoPoint> = emptyList()): Route? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("locations", JSONArray().apply {
                        (listOf(from) + intermediates + to).forEach { p ->
                            put(JSONObject().put("lat", p.lat).put("lon", p.lng).put("type", "break"))
                        }
                    })
                    put("costing", "motorcycle")
                    put("units", "kilometers")
                    put("language", "en-US")
                }
                com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.FREE_ROUTES)
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", USER_AGENT)
                    connectTimeout = 15_000
                    readTimeout = 25_000
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                conn.disconnect()
                if (code !in 200..299) {
                    Log.e(TAG, "Valhalla HTTP $code: ${text.take(300)}")
                    com.example.wayfindr.data.RideDiagnostics.log("route", "free routing failed: HTTP $code")
                    return@withContext null
                }
                parse(text)
            } catch (e: Exception) {
                Log.e(TAG, "route() failed", e)
                com.example.wayfindr.data.RideDiagnostics.log("route", "free routing failed: ${e.message}")
                null
            }
        }

    private fun parse(json: String): Route? {
        val trip = JSONObject(json).optJSONObject("trip") ?: return null
        val legs = trip.optJSONArray("legs") ?: return null

        // Legs are joined into one polyline. Each leg's shape starts on the previous leg's last
        // point, so that duplicate is dropped and the leg's shape indices are offset to match.
        val geometry = ArrayList<GeoPoint>()
        val legStartIndex = IntArray(legs.length())
        val legEndIndex = IntArray(legs.length())
        for (li in 0 until legs.length()) {
            val shape = PolylineCodec.decode(legs.getJSONObject(li).optString("shape"), precision = 6)
            if (shape.isEmpty()) return null
            val skip = if (geometry.isEmpty()) 0 else 1
            legStartIndex[li] = geometry.size - skip
            geometry.addAll(shape.drop(skip))
            legEndIndex[li] = geometry.lastIndex
        }
        if (geometry.size < 2) return null

        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])

        val maneuvers = ArrayList<Maneuver>()
        for (li in 0 until legs.length()) {
            val ms = legs.getJSONObject(li).optJSONArray("maneuvers") ?: continue
            for (mi in 0 until ms.length()) {
                val m = ms.getJSONObject(mi)
                val google = googleManeuverFor(m.optInt("type")) ?: continue
                val idx = (legStartIndex[li] + m.optInt("begin_shape_index", 0)).coerceIn(0, geometry.lastIndex)
                maneuvers.add(Maneuver(
                    type = ManeuverType.fromGoogle(google),
                    instruction = m.optString("instruction").ifBlank { "Continue" },
                    location = geometry[idx],
                    cumulativeMeters = cum[idx],
                    googleManeuver = google,
                ))
            }
        }

        val summary = trip.optJSONObject("summary")
        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = summary?.optDouble("length")?.takeIf { !it.isNaN() }?.times(1000) ?: cum.last(),
            totalSeconds = summary?.optDouble("time")?.takeIf { !it.isNaN() } ?: 0.0,
            cumulative = cum,
            legEndCumulative = legEndIndex.map { cum[it] },
            traffic = emptyList(),
        )
    }

    /**
     * Valhalla maneuver type → the Google Routes maneuver name Wayfindr already maps to dash
     * glyphs. null = not a turn the rider needs (arrival, roundabout exit, ferry exit), matching
     * what Google's step list contains.
     * Types: https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/#trip-legs-and-maneuvers
     */
    fun googleManeuverFor(type: Int): String? = when (type) {
        1, 2, 3 -> "DEPART"                       // start, start right, start left
        4, 5, 6 -> null                           // destination (Google has no arrival step)
        9 -> "TURN_SLIGHT_RIGHT"
        10 -> "TURN_RIGHT"
        11 -> "TURN_SHARP_RIGHT"
        12 -> "UTURN_RIGHT"
        13 -> "UTURN_LEFT"
        14 -> "TURN_SHARP_LEFT"
        15 -> "TURN_LEFT"
        16 -> "TURN_SLIGHT_LEFT"
        18, 20 -> "RAMP_RIGHT"                    // ramp right, exit right
        19, 21 -> "RAMP_LEFT"                     // ramp left, exit left
        23 -> "FORK_RIGHT"                        // stay right
        24 -> "FORK_LEFT"                         // stay left
        25, 37, 38 -> "MERGE"                     // merge, merge right, merge left
        26 -> "ROUNDABOUT_LEFT"                   // enter roundabout (dash shows clockwise, see DashGlyph)
        27, 29 -> null                            // roundabout exit, ferry exit
        28 -> "FERRY"
        else -> "STRAIGHT"                        // becomes, continue, ramp straight, stay straight, …
    }
}
