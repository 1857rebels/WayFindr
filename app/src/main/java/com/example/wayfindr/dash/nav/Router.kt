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
 * Fetches a motorcycle/scooter route using Google Maps Routes API.
 *
 * Called while planning, when the phone has internet access. The resulting
 * Route can then be cached and used for offline navigation.
 *
 * Uses Google Routes API TWO_WHEELER mode.
 */
object Router {

    private const val TAG = "Router"

    private const val ENDPOINT =
        "https://routes.googleapis.com/directions/v2:computeRoutes"

    /**
     * Only request the fields Wayfindr actually needs.
     *
     * Route:
     * - distance
     * - duration
     * - full route geometry
     *
     * Steps:
     * - navigation instruction
     * - maneuver type
     * - start/end locations
     */
    private const val FIELD_MASK =
        "routes.distanceMeters," +
                "routes.duration," +
                "routes.polyline.encodedPolyline," +
                "routes.travelAdvisory.speedReadingIntervals," +
                "routes.legs.endLocation," +
                "routes.legs.steps.startLocation," +
                "routes.legs.steps.endLocation," +
                "routes.legs.steps.navigationInstruction"

    /** Which router to use ("Routing" setting). Set from DashConfig. */
    @Volatile var source = com.example.wayfindr.dash.RoutingSource.GOOGLE

    /**
     * Computes an ordered multi-stop route; [intermediates] are visited before [to].
     * BRouter runs on the phone; if it fails (app missing, area not downloaded) the free OSM
     * server is tried — never Google, so the BRouter choice can't add to the Google bill — and
     * that simply fails too when there's no internet.
     */
    suspend fun route(from: GeoPoint, to: GeoPoint, intermediates: List<GeoPoint> = emptyList()): Route? =
        when (source) {
            com.example.wayfindr.dash.RoutingSource.GOOGLE ->
                if (!com.example.wayfindr.dash.GoogleKey.present)
                    FreeRouter.route(from, to, intermediates)?.copy(source = com.example.wayfindr.dash.RoutingSource.OSM)
                else googleRoute(from, to, intermediates)?.copy(source = com.example.wayfindr.dash.RoutingSource.GOOGLE)
            com.example.wayfindr.dash.RoutingSource.OSM ->
                FreeRouter.route(from, to, intermediates)?.copy(source = com.example.wayfindr.dash.RoutingSource.OSM)
            com.example.wayfindr.dash.RoutingSource.BROUTER ->
                OfflineRouter.route(from, to, intermediates)?.copy(source = com.example.wayfindr.dash.RoutingSource.BROUTER)
                    ?: run {
                        com.example.wayfindr.data.RideDiagnostics.log("route",
                            "BRouter unavailable (${OfflineRouter.lastError}) — trying the free OSM server")
                        FreeRouter.route(from, to, intermediates)?.copy(source = com.example.wayfindr.dash.RoutingSource.OSM)
                    }
        }

    private suspend fun googleRoute(from: GeoPoint, to: GeoPoint, intermediates: List<GeoPoint>): Route? =
        withContext(Dispatchers.IO) {

            try {
                val body = buildRequest(from, to, intermediates)

                com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.ROUTES)
                val conn =
                    (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {

                        requestMethod = "POST"
                        doOutput = true

                        setRequestProperty(
                            "Content-Type",
                            "application/json"
                        )

                        setRequestProperty(
                            "X-Goog-Api-Key",
                            com.example.wayfindr.BuildConfig.MAPS_API_KEY
                        )

                        setRequestProperty(
                            "X-Goog-FieldMask",
                            FIELD_MASK
                        )

                        connectTimeout = 15_000
                        readTimeout = 20_000
                    }

                conn.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode

                val responseBody =
                    if (responseCode in 200..299) {
                        conn.inputStream.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    } else {
                        conn.errorStream?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        } ?: ""
                    }

                conn.disconnect()

                if (responseCode !in 200..299) {
                    Log.e(
                        TAG,
                        "Google Routes API failed: HTTP $responseCode: $responseBody"
                    )
                    return@withContext null
                }

                parse(responseBody)

            } catch (e: Exception) {
                Log.e(TAG, "route() failed", e)
                null
            }
        }

    /**
     * Build a Google Routes API TWO_WHEELER request.
     */
    private fun buildRequest(
        from: GeoPoint,
        to: GeoPoint,
        intermediates: List<GeoPoint>,
    ): JSONObject {

        return JSONObject().apply {

            put(
                "origin",
                waypoint(from)
            )

            put(
                "destination",
                waypoint(to)
            )

            if (intermediates.isNotEmpty()) {
                put("intermediates", JSONArray().apply {
                    intermediates.forEach { put(waypoint(it)) }
                })
            }

            // Motorcycle / scooter routing
            put("travelMode", "TWO_WHEELER")

            // Request traffic-aware routing.
            put("routingPreference", "TRAFFIC_AWARE")
            put("extraComputations", JSONArray().put("TRAFFIC_ON_POLYLINE"))

            put("computeAlternativeRoutes", false)

            put(
                "routeModifiers",
                JSONObject().apply {
                    put("avoidTolls", false)
                    put("avoidHighways", false)
                    put("avoidFerries", false)
                }
            )

            put("languageCode", "en-US")
            put("units", "METRIC")
        }
    }

    /**
     * Convert a GeoPoint to a Google Routes API waypoint.
     */
    private fun waypoint(point: GeoPoint): JSONObject =
        JSONObject().apply {
            put(
                "location",
                JSONObject().apply {
                    put(
                        "latLng",
                        JSONObject().apply {
                            put("latitude", point.lat)
                            put("longitude", point.lng)
                        }
                    )
                }
            )
        }

    private fun parse(json: String): Route? {

        val root = JSONObject(json)

        val routes = root.optJSONArray("routes")

        if (routes == null || routes.length() == 0) {
            Log.w(TAG, "Google Routes API returned no routes: $json")
            return null
        }

        val route = routes.getJSONObject(0)

        /*
         * Google returns:
         *
         * routes[0].polyline.encodedPolyline
         */
        val encodedPolyline =
            route.optJSONObject("polyline")
                ?.optString("encodedPolyline")
                .orEmpty()

        if (encodedPolyline.isBlank()) {
            Log.w(TAG, "Route contains no encoded polyline")
            return null
        }

        val geometry = PolylineCodec.decode(encodedPolyline)

        if (geometry.size < 2) {
            Log.w(TAG, "Decoded route contains fewer than 2 points")
            return null
        }

        /*
         * Build cumulative distance for Wayfindr's navigation engine.
         */
        val cumulative = DoubleArray(geometry.size)

        for (i in 1 until geometry.size) {
            cumulative[i] =
                cumulative[i - 1] +
                        GeoPoint.distMeters(
                            geometry[i - 1],
                            geometry[i]
                        )
        }

        val maneuvers = parseManeuvers(
            route = route,
            geometry = geometry,
            cumulative = cumulative
        )

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = route.optDouble(
                "distanceMeters",
                cumulative.last()
            ),
            totalSeconds = parseDuration(
                route.optString("duration", "0s")
            ),
            cumulative = cumulative,
            legEndCumulative = parseLegEnds(route, geometry, cumulative),
            traffic = parseTraffic(route, geometry.lastIndex),
        )
    }

    private fun parseTraffic(route: JSONObject, lastPointIndex: Int): List<TrafficInterval> {
        val intervals = route.optJSONObject("travelAdvisory")
            ?.optJSONArray("speedReadingIntervals") ?: return emptyList()
        return buildList {
            for (index in 0 until intervals.length()) {
                val item = intervals.optJSONObject(index) ?: continue
                val start = item.optInt("startPolylinePointIndex", 0).coerceIn(0, lastPointIndex)
                val end = item.optInt("endPolylinePointIndex", lastPointIndex).coerceIn(start, lastPointIndex)
                val speed = when (item.optString("speed")) {
                    "NORMAL" -> TrafficSpeed.NORMAL
                    "SLOW" -> TrafficSpeed.SLOW
                    "TRAFFIC_JAM" -> TrafficSpeed.TRAFFIC_JAM
                    else -> TrafficSpeed.UNKNOWN
                }
                if (end > start) add(TrafficInterval(start, end, speed))
            }
        }
    }

    private fun parseLegEnds(route: JSONObject, geometry: List<GeoPoint>, cumulative: DoubleArray): List<Double> {
        val legs = route.optJSONArray("legs") ?: return emptyList()
        return buildList {
            for (index in 0 until legs.length()) {
                val point = legs.optJSONObject(index)?.optJSONObject("endLocation")
                    ?.optJSONObject("latLng") ?: continue
                add(nearestCumulative(GeoPoint(point.optDouble("latitude"), point.optDouble("longitude")), geometry, cumulative))
            }
        }
    }

    /**
     * Parse Google Routes API step navigation instructions into Wayfindr
     * Maneuver objects.
     */
    private fun parseManeuvers(
        route: JSONObject,
        geometry: List<GeoPoint>,
        cumulative: DoubleArray
    ): List<Maneuver> {

        val maneuvers = ArrayList<Maneuver>()

        val legs = route.optJSONArray("legs") ?: return maneuvers

        for (legIndex in 0 until legs.length()) {

            val leg = legs.optJSONObject(legIndex) ?: continue

            val steps = leg.optJSONArray("steps") ?: continue

            for (stepIndex in 0 until steps.length()) {

                val step =
                    steps.optJSONObject(stepIndex) ?: continue

                // A step's navigationInstruction is the maneuver that BEGINS the step, so it
                // happens at the step's start point. Anchoring it at the end showed every turn
                // one step late (the previous step's arrow at the next junction).
                val startLocation =
                    step.optJSONObject("startLocation")
                        ?.optJSONObject("latLng")
                        ?: continue

                val point = GeoPoint(
                    startLocation.optDouble("latitude"),
                    startLocation.optDouble("longitude")
                )

                val navigationInstruction =
                    step.optJSONObject("navigationInstruction")

                val instruction =
                    navigationInstruction
                        ?.optString("instructions")
                        ?.ifBlank { null }
                        ?: "Continue"

                val googleManeuver =
                    navigationInstruction
                        ?.optString("maneuver")
                        .orEmpty()

                val type = ManeuverType.fromGoogle(
                    googleManeuver
                )

                maneuvers.add(
                    Maneuver(
                        type = type,
                        instruction = instruction,
                        location = point,
                        cumulativeMeters = nearestCumulative(
                            point,
                            geometry,
                            cumulative
                        ),
                        googleManeuver = googleManeuver
                    )
                )
            }
        }

        return maneuvers
    }

    /**
     * Google returns protobuf Duration strings, for example:
     *
     * "302s"
     * "3600s"
     * "3723.5s"
     */
    private fun parseDuration(duration: String): Double {
        return duration
            .removeSuffix("s")
            .toDoubleOrNull()
            ?: 0.0
    }

    /**
     * Find the route geometry vertex closest to a maneuver.
     */
    private fun nearestCumulative(
        point: GeoPoint,
        geometry: List<GeoPoint>,
        cumulative: DoubleArray
    ): Double {

        var bestDistance = Double.MAX_VALUE
        var bestCumulative = 0.0

        for (i in geometry.indices) {

            val distance =
                GeoPoint.distMeters(point, geometry[i])

            if (distance < bestDistance) {
                bestDistance = distance
                bestCumulative = cumulative[i]
            }
        }

        return bestCumulative
    }
}
