package com.example.wayfindr.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Counts outgoing network requests per endpoint per calendar month, on the device, for the
 * "API usage" card on the Live data screen. Only real network requests are counted (cache hits
 * are not). Kept in its own SharedPreferences file; counts start from when this was installed.
 */
object ApiUsage {
    const val ROUTES = "Google Routes API (computeRoutes)"
    const val TILES = "Google map tiles"
    const val FREE_ROUTES = "Valhalla routing (OSM, free)"
    const val OSM_TILES = "OpenStreetMap tiles (free)"
    const val OFFLINE_ROUTES = "BRouter (offline, on phone)"
    const val GEOCODE = "Android Geocoder (search)"
    const val OSM_SEARCH = "OSM search (Photon / Nominatim, free)"
    const val REVERSE_GEOCODE = "Android Geocoder (reverse)"
    const val SHORT_LINK = "Maps share-link expand"
    const val UPDATE_CHECK = "GitHub update check"

    private var prefs: SharedPreferences? = null
    private val _thisMonth = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** endpoint → request count for the current month. */
    val thisMonth: StateFlow<Map<String, Int>> = _thisMonth

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("api_usage", Context.MODE_PRIVATE)
        publish()
    }

    fun monthLabel(): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())

    private fun monthKey() = SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date())

    @Synchronized
    fun record(endpoint: String) {
        val p = prefs ?: return
        val key = "${monthKey()}|$endpoint"
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
        publish()
    }

    private fun publish() {
        val p = prefs ?: return
        val prefix = "${monthKey()}|"
        _thisMonth.value = p.all.filterKeys { it.startsWith(prefix) }
            .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(prefix) to it } }.toMap()
    }
}
