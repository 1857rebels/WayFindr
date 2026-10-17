package com.example.wayfindr.dash.nav

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.example.wayfindr.BuildConfig
import com.example.wayfindr.dash.RoutingSource
import com.example.wayfindr.data.SavedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Route-screen search, matched to the "Routing" setting:
 * - Google: the phone's geocoder (Google Play services backend), as before.
 * - OSM: Photon (komoot), a free OpenStreetMap search that tolerates small differences
 *   ("Garden" vs "Gardens") and prefers places near the rider; Nominatim (OSM's own, exact-name)
 *   if Photon fails. Both free, no key, identifying User-Agent, one request per typed search.
 * - BRouter (offline): BRouter has no place search, so saved destinations are matched by name on
 *   the phone first; only if none match is Nominatim tried (works only with internet).
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
    private const val PHOTON = "https://photon.komoot.io/api/"
    private val USER_AGENT = "Wayfindr/${BuildConfig.VERSION_NAME} (personal motorcycle navigation app; Android)"

    data class Hit(val name: String, val lat: Double, val lng: Double, val provider: String)

    /** What the search box should say it searches with. */
    fun label(source: RoutingSource): String = when (source) {
        RoutingSource.GOOGLE -> "Google"
        RoutingSource.OSM -> "OSM"
        RoutingSource.BROUTER -> "saved + OSM"
    }

    /** Last failure reason for the UI ("no match", "no internet", …). */
    @Volatile var lastError: String? = null
        private set

    suspend fun search(
        ctx: Context, query: String, source: RoutingSource,
        near: Pair<Double, Double>?, saved: List<SavedLocation>,
    ): Hit? {
        val q = query.trim()
        if (q.isEmpty()) return null
        lastError = null
        val hit = when (source) {
            RoutingSource.GOOGLE -> google(ctx, q)
            RoutingSource.OSM -> osm(q, near)
            RoutingSource.BROUTER -> savedMatch(q, saved) ?: osm(q, near)
        }
        val msg = hit?.let { "search '$q' (${it.provider}) → ${it.name} %.5f,%.5f".format(it.lat, it.lng) }
            ?: "search '$q' (${label(source)}) → nothing: $lastError"
        Log.i(TAG, msg)
        com.example.wayfindr.data.RideDiagnostics.log("search", msg)
        return hit
    }

    /** Saved destinations (and their stops) whose name contains every word typed. */
    internal fun savedMatch(q: String, saved: List<SavedLocation>): Hit? {
        val words = q.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        fun matches(name: String) = name.lowercase(Locale.ROOT).let { n -> words.all { it in n } }
        for (s in saved) {
            if (matches(s.name)) {
                // An itinerary's own name means its final stop.
                val last = s.waypoints.lastOrNull()
                return Hit(s.name, last?.lat ?: s.lat, last?.lng ?: s.lng, "saved place")
            }
            s.waypoints.firstOrNull { matches(it.name) && it.lat != null && it.lng != null }
                ?.let { return Hit(it.name, it.lat!!, it.lng!!, "saved place") }
        }
        lastError = "no saved place matches"
        return null
    }

    /** How many of the typed words appear in [text] (case-insensitive; "lal bagh" also matches "lalbagh"). */
    internal fun wordScore(q: String, text: String): Int {
        val t = text.lowercase(Locale.ROOT)
        val squashed = t.replace(" ", "")
        return q.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
            .count { it in t || it in squashed }
    }

    @Suppress("DEPRECATION")
    private suspend fun google(ctx: Context, q: String): Hit? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) { lastError = "no geocoder on this phone"; return@withContext null }
            com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.GEOCODE)
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 1)?.firstOrNull()
                ?: run { lastError = "no match"; return@withContext null }
            Hit(q, a.latitude, a.longitude, "Google")
        } catch (e: Exception) {
            lastError = "Google search failed (${e.message})"; null
        }
    }

    /** OpenStreetMap search: Photon first, Nominatim if Photon fails or finds nothing. */
    private suspend fun osm(q: String, near: Pair<Double, Double>?): Hit? =
        photon(q, near) ?: nominatim(q, near)

    private suspend fun photon(q: String, near: Pair<Double, Double>?): Hit? = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append(PHOTON).append("?limit=5&q=").append(URLEncoder.encode(q, "UTF-8"))
                near?.let { (lat, lng) -> append("&lat=%.5f&lon=%.5f".format(Locale.ROOT, lat, lng)) }
            }
            com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.OSM_SEARCH)
            val body = httpGet(url) ?: return@withContext null
            val features = org.json.JSONObject(body).optJSONArray("features") ?: JSONArray()
            val results = (0 until features.length()).map { features.getJSONObject(it) }
            if (results.isEmpty()) { lastError = "no match"; return@withContext null }
            fun describe(f: org.json.JSONObject) = f.getJSONObject("properties").let { pr ->
                listOf("name", "street", "district", "city", "state").mapNotNull { k -> pr.optString(k).takeIf { it.isNotBlank() } }.joinToString(", ")
            }
            Log.i(TAG, "Photon '$q': " + results.joinToString(" | ") { describe(it).take(60) })
            val f = results.withIndex().maxByOrNull { (i, it) -> wordScore(q, describe(it)) * 10 - i }!!.value
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")   // [lon, lat]
            val name = f.getJSONObject("properties").optString("name").ifBlank { describe(f).substringBefore(',') }.ifBlank { q }
            Hit(name, c.getDouble(1), c.getDouble(0), "OSM (Photon)")
        } catch (e: java.io.IOException) {
            lastError = "no internet for OpenStreetMap search"; null
        } catch (e: Exception) {
            lastError = "OpenStreetMap search failed (${e.message})"; null
        }
    }

    /** GET with the app's User-Agent; null (and [lastError]) on a non-200 answer. */
    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (conn.responseCode != 200) { lastError = "OpenStreetMap search HTTP ${conn.responseCode}"; null }
            else conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    private suspend fun nominatim(q: String, near: Pair<Double, Double>?): Hit? = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append(NOMINATIM).append("?format=jsonv2&limit=5&q=").append(URLEncoder.encode(q, "UTF-8"))
                append("&accept-language=").append(Locale.getDefault().language)
                // Prefer results near the rider (±1°, ~110 km) without excluding the rest.
                near?.let { (lat, lng) -> append("&viewbox=%.4f,%.4f,%.4f,%.4f".format(Locale.ROOT, lng - 1, lat + 1, lng + 1, lat - 1)) }
            }
            com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.OSM_SEARCH)
            val body = httpGet(url) ?: return@withContext null
            val arr = JSONArray(body)
            val results = (0 until arr.length()).map { arr.getJSONObject(it) }
            if (results.isEmpty()) { lastError = "no match"; return@withContext null }
            Log.i(TAG, "Nominatim '$q': " + results.joinToString(" | ") { it.optString("display_name").take(60) })
            // Nominatim's first answer can be a loose match (2026-10-17: "Lalbagh Botanical Garden"
            // gave "Atal Bihari Botanical Garden"), so take the one naming the most typed words.
            val r = results.withIndex().maxByOrNull { (i, it) -> wordScore(q, it.optString("display_name")) * 10 - i }!!.value
            val name = r.optString("name").ifBlank { r.optString("display_name").substringBefore(',') }.ifBlank { q }
            Hit(name, r.getString("lat").toDouble(), r.getString("lon").toDouble(), "OSM (Nominatim)")
        } catch (e: java.io.IOException) {
            lastError = "no internet for OpenStreetMap search"; null
        } catch (e: Exception) {
            lastError = "OpenStreetMap search failed (${e.message})"; null
        }
    }
}
