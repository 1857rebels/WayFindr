package com.example.wayfindr.util

import android.util.Log
import com.example.wayfindr.data.SharedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object LocationParser {
    private const val TAG = "LocationParser"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Nothing Phone 3) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val urlRegex  = Regex("https?://[^\\s)]+")
    private val coord3d4d = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
    private val coordAt   = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordQ    = Regex("[?&](?:q|query|destination|daddr)=(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)")
    private val coordLl   = Regex("[?&]ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordGeo  = Regex("geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordSearch = Regex("/search/(-?\\d+\\.\\d+),\\+?(-?\\d+\\.\\d+)")
    private val placePath = Regex("/place/([^/@?]+)")
    private val placeQ    = Regex("[?&]q=([^&0-9\\-@][^&]*)")
    private val bareCoords = Regex("^\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*$")

    // Body-scan patterns (Google embeds coords in the place page when the URL doesn't carry them).
    private val bodyPatterns = listOf(
        coord3d4d,
        Regex("\\[null,null,(-?\\d+\\.\\d{3,}),(-?\\d+\\.\\d{3,})\\]"),
        Regex("center=(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)"),
        Regex("\"latitude\":\\s*(-?\\d+\\.\\d+)[^}]{0,60}?\"longitude\":\\s*(-?\\d+\\.\\d+)"),
        coordAt,
    )

    /** Synchronous parse of the raw shared text/URI (no network). */
    fun parse(text: String): SharedLocation {
        val trimmed = text.trim()
        Dbg.d(TAG) { "parse(): $trimmed" }
        // Strip trailing sentence punctuation that often clings to a shared link
        // ("...goo.gl/abc." / "(...)") so the redirect/resolve doesn't 404.
        val url = urlRegex.find(trimmed)?.value?.trimEnd('.', ',', ';', '!', '?', ')', ']', '"', '\'')
            ?: if (trimmed.startsWith("geo:")) trimmed else null

        val isShort = url != null && (
            url.contains("maps.app.goo.gl") || url.contains("goo.gl/maps") ||
            url.contains("g.co/kgs") || url.contains("//goo.gl/"))

        val textBefore = url?.let { trimmed.substringBefore(it).trim() }
        val textName = textBefore?.lines()?.lastOrNull { it.isNotBlank() }
            ?.removeSuffix(":")?.removePrefix("Check out")?.trim()

        val coords = if (url != null && !isShort) extractCoords(url) else extractCoords(trimmed)

        val name = when {
            !textName.isNullOrBlank() && textName != "Check out" -> textName
            url != null && !isShort -> extractPlaceName(url) ?: "Shared location"
            coords != null -> "Dropped pin"
            else -> "Loading…"
        }

        Dbg.d(TAG) { "parse() → name='$name' coords=$coords short=$isShort url=$url" }
        return SharedLocation(
            name = name,
            lat = coords?.first,
            lng = coords?.second,
            url = url,
            needsExpansion = (coords == null && url != null),
        )
    }

    /**
     * Extract the ordered stops from a Google Maps directions URL. Google represents
     * a multi-stop trip as `waypoints=...&destination=...`, or as `/maps/dir/a/b/c`.
     * The origin is intentionally omitted because Wayfindr always starts at live GPS.
     */
    fun parseStops(text: String): List<SharedLocation> {
        val url = urlRegex.find(text)?.value?.trimEnd('.', ',', ';', '!', '?', ')', ']', '"', '\'')
            ?: return emptyList()
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val rawQuery = url.substringAfter('?', "")
        val query = rawQuery.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=').lowercase()
                val value = part.substringAfter('=', "")
                value.takeIf { it.isNotBlank() }?.let { key to runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            }
        val waypointParams = query
            .filter { (key, _) -> key == "waypoints" || key.matches(Regex("waypoint\\d+")) }
            .sortedBy { (key, _) -> if (key == "waypoints") -1 else key.removePrefix("waypoint").toIntOrNull() ?: Int.MAX_VALUE }
        val tokens = mutableListOf<String>()
        waypointParams.forEach { (_, value) -> value.split('|').forEach { tokens += it.removePrefix("via:") } }
        query.firstOrNull { (key, _) -> key == "destination" }?.second?.let { tokens += it }

        if (tokens.size < 2 && decoded.contains("/dir/")) {
            val path = decoded.substringAfter("/dir/")
                .substringBefore("/data")
                .substringBefore('?')
            val rawSegments = path.split('/')
            val pathStops = rawSegments
                .filter { it.isNotBlank() && !it.startsWith("@") }
            // `/dir//stop/final` has no explicit origin; `/dir/origin/stop/final` does.
            // Wayfindr supplies origin from live GPS, so drop only an explicit origin segment.
            val hasExplicitOrigin = rawSegments.firstOrNull().orEmpty().isNotBlank()
            val routeStops = if (hasExplicitOrigin) pathStops.drop(1) else pathStops
            if (routeStops.size >= 2) tokens.apply { clear(); addAll(routeStops) }
        }

        return tokens.mapNotNull(::stopFromToken)
            .distinctBy { "${it.lat},${it.lng}:${it.name}" }
            .take(26)
    }

    /** Resolve a short Google Maps link, then parse every stop in the expanded directions URL. */
    suspend fun resolveStops(url: String): List<SharedLocation> = withContext(Dispatchers.IO) {
        val (finalUrl, body) = fetchFollowing(url)
        // Google may expose the complete itinerary only in an intermediate redirect or in the
        // returned HTML. Keep the original URL in the candidates too; it often already contains
        // the encoded waypoints even when the short-link target does not.
        listOf(url, finalUrl, body)
            .flatMap(::parseStops)
            .distinctBy { "${it.lat},${it.lng}:${it.name}" }
            .take(26)
    }

    /**
     * Network resolve of a Maps URL → (coords, place-name). Follows redirects,
     * bypasses consent interstitials, and finally scans the page body for the
     * coordinates Google embeds there. Run off the main thread.
     */
    suspend fun resolve(url: String): Pair<Pair<Double, Double>?, String?> = withContext(Dispatchers.IO) {
        val (finalUrl, body) = fetchFollowing(url)
        var coords = extractCoords(finalUrl)
        val name = extractPlaceName(finalUrl)
        if (coords == null) coords = scanBody(body)
        Dbg.i(TAG) { "resolve() → coords=$coords name=$name finalUrl=${finalUrl.take(120)}" }
        coords to name
    }

    fun extractCoords(s: String): Pair<Double, Double>? {
        for (regex in listOf(coord3d4d, coordGeo, coordSearch, coordAt, coordQ, coordLl)) {
            val m = regex.find(s) ?: continue
            val pair = m.groupValues[1].toDoubleOrNull()?.let { lat ->
                m.groupValues[2].toDoubleOrNull()?.let { lng -> lat to lng }
            } ?: continue
            if (valid(pair.first, pair.second)) return pair
        }
        return null
    }

    private fun stopFromToken(token: String): SharedLocation? {
        val clean = token.replace('+', ' ').trim().removePrefix("place/")
        if (clean.isBlank() || clean.startsWith("place_id:")) return null
        val coords = bareCoords.matchEntire(clean)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { lat ->
                it.groupValues[2].toDoubleOrNull()?.let { lng -> lat to lng }
            }
        } ?: extractCoords(clean)
        return if (coords != null && valid(coords.first, coords.second)) {
            SharedLocation(name = "Dropped pin", lat = coords.first, lng = coords.second)
        } else {
            SharedLocation(name = clean.replace('_', ' '), needsExpansion = true)
        }
    }

    fun extractPlaceName(s: String): String? {
        placePath.find(s)?.let { m ->
            return URLDecoder.decode(m.groupValues[1].replace("+", " "), "UTF-8")
                .replace("_", " ").trim().ifBlank { null }
        }
        placeQ.find(s)?.let { m ->
            return URLDecoder.decode(m.groupValues[1].replace("+", " "), "UTF-8").trim().ifBlank { null }
        }
        return null
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun valid(lat: Double, lng: Double) =
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    // Only ever fetch Google Maps / short-link hosts. A shared "location" is attacker-
    // controlled text, and fetchFollowing() chases redirects + a consent `continue=` param —
    // without this gate the app would issue GETs to any host the link points at (SSRF: leaking
    // the device's network position, probing LAN hosts, acting as an open redirector).
    private val allowedHostSuffixes = listOf("google.com", "goo.gl", "g.co", "ggpht.com")

    private fun isAllowedHost(url: String): Boolean = runCatching {
        val host = URL(url).host?.lowercase() ?: return false
        allowedHostSuffixes.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    private fun scanBody(body: String): Pair<Double, Double>? {
        if (body.isBlank()) return null
        for (p in bodyPatterns) {
            val m = p.find(body) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (valid(lat, lng)) { Dbg.d(TAG) { "scanBody matched ${p.pattern.take(24)} → $lat,$lng" }; return lat to lng }
        }
        return null
    }

    /** Follow redirects manually (incl. http↔https), bypass consent, return (finalUrl, body). */
    private fun fetchFollowing(start: String): Pair<String, String> {
        var url = start
        var body = ""
        try {
            repeat(8) { hop ->
                // Consent interstitial → the real maps URL is in the `continue=` param.
                if (url.contains("consent.google") || url.contains("/sorry/")) {
                    Regex("continue=([^&]+)").find(url)?.groupValues?.get(1)?.let {
                        url = URLDecoder.decode(it, "UTF-8")
                        Dbg.d(TAG) { "consent bypass → ${url.take(100)}" }
                    }
                }
                // Refuse to fetch anything outside the Maps/short-link allowlist — covers the
                // initial URL, every redirect target, and the consent-rewritten URL above.
                if (!isAllowedHost(url)) {
                    Log.w(TAG, "blocked non-allowlisted host: ${url.take(80)}")
                    return url to body
                }
                com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.SHORT_LINK)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    connectTimeout = 9_000
                    readTimeout = 9_000
                }
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                Dbg.d(TAG) { "hop $hop: $code ${loc?.take(90) ?: ""}" }
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    conn.disconnect()
                    url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                    // A directions URL commonly contains an @lat,lng viewport even before its
                    // waypoint parameters are fully expanded. Do not stop at that intermediate
                    // redirect or multi-stop shares lose their remaining destinations.
                    if (extractCoords(url) != null && !url.contains("/dir/") && !url.contains("waypoints=")) {
                        return url to ""
                    }
                } else {
                    body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    conn.disconnect()
                    return url to body
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchFollowing failed: ${e.message}")
        }
        return url to body
    }
}
