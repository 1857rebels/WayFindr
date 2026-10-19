package com.example.wayfindr.util

import com.example.wayfindr.data.SavedLocation
import com.example.wayfindr.data.SharedLocation
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A saved destination (every stop, in order, with names) as a `wayfindr://` link — no server.
 *
 * `wayfindr://route?n=<name>&s=<lat>,<lng>,<stop name>&s=…&v=1`: each stop is one `s` parameter;
 * names are percent-encoded, so commas, `&`, `|` and unicode survive. `v` comes last so trailing
 * punctuation can't eat into a stop. The link is shared as a QR code ([RouteQr]); scanning it with
 * the phone camera opens Wayfindr (ACTION_VIEW), and text containing the link shared into the app
 * (ACTION_SEND) works too — both reach RouteViewModel.handleSharedText.
 */
object RouteShare {
    const val SCHEME = "wayfindr"
    private const val PREFIX = "$SCHEME://route?"
    private const val VERSION = 1
    private const val MAX_STOPS = 26
    private val linkRegex = Regex("""wayfindr://route\?[^\s<>"]+""")

    data class SharedRoute(val name: String, val stops: List<SharedLocation>)

    /** Stops of a saved destination: its itinerary, or the single place it was saved as. */
    fun stopsOf(loc: SavedLocation): List<SharedLocation> =
        loc.waypoints.filter { it.lat != null && it.lng != null }
            .ifEmpty { listOf(SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng)) }

    fun link(name: String, stops: List<SharedLocation>): String = buildString {
        append(PREFIX).append("n=").append(enc(oneLine(name)))
        stops.filter { it.lat != null && it.lng != null }.take(MAX_STOPS).forEach { s ->
            append("&s=").append(coord(s.lat!!)).append(',').append(coord(s.lng!!))
            val stopName = oneLine(s.name)
            if (stopName.isNotEmpty()) append(',').append(enc(stopName))
        }
        append("&v=").append(VERSION)
    }

    /** Find and decode a wayfindr:// link anywhere in [text]; null when there is none or it has no valid stop. */
    fun decode(text: String): SharedRoute? {
        val link = linkRegex.find(text)?.value ?: return null
        var name = ""
        val stops = mutableListOf<SharedLocation>()
        for (part in link.removePrefix(PREFIX).split('&')) {
            val key = part.substringBefore('=')
            val raw = part.substringAfter('=', "")
            when (key) {
                "n" -> name = dec(raw) ?: ""
                "s" -> {
                    // lat,lng[,name] — the name may itself contain commas (limit = 3).
                    val f = (dec(raw) ?: continue).split(',', limit = 3)
                    val lat = f.getOrNull(0)?.trim()?.toDoubleOrNull() ?: continue
                    val lng = f.getOrNull(1)?.trim()?.toDoubleOrNull() ?: continue
                    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
                    stops += SharedLocation(name = f.getOrNull(2)?.trim().orEmpty().ifEmpty { "Stop ${stops.size + 1}" },
                        lat = lat, lng = lng)
                }
                // "v" and unknown keys are ignored so newer links still import their stops.
            }
            if (stops.size >= MAX_STOPS) break
        }
        if (stops.isEmpty()) return null
        return SharedRoute(name.ifBlank { stops.last().name }, stops)
    }

    private fun coord(v: Double): String =
        String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun dec(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()
}
