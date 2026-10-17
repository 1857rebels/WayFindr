package com.example.wayfindr.dash.map

import com.example.wayfindr.dash.nav.GeoPoint

/**
 * Which tiles to cache when a route is set (pure, JVM-tested):
 *  - RIDING zoom ([RIDING_ZOOMS], the levels the dash is actually viewed at; default nav zoom 19)
 *    as a 3-tile-wide band along the first [ridingMeters] of the route — the whole route when
 *    it's shorter than that;
 *  - OVERVIEW zoom ([OVERVIEW_ZOOMS]) along the WHOLE route.
 * Riding tiles come first and in route order, so the stretch you ride first is cached first.
 * The polyline is resampled every [SAMPLE_M] so long straight segments don't leave gaps.
 */
object RouteTilePlan {
    val RIDING_ZOOMS = 15..19
    val OVERVIEW_ZOOMS = 11..14
    const val RIDING_METERS = 50_000.0
    private const val SAMPLE_M = 30.0

    data class Tile(val z: Int, val x: Int, val y: Int)

    fun plan(route: List<GeoPoint>, ridingMeters: Double = RIDING_METERS): List<Tile> {
        if (route.size < 2) return emptyList()
        val out = LinkedHashSet<Tile>()
        val samples = resample(route)
        for ((p, along) in samples) {
            if (along > ridingMeters) break
            for (z in RIDING_ZOOMS) addBand(out, p, z, radius = 1)
        }
        for ((p, _) in samples) for (z in OVERVIEW_ZOOMS) addBand(out, p, z, radius = if (z == 14) 1 else 0)
        return out.toList()
    }

    private fun addBand(out: MutableSet<Tile>, p: GeoPoint, z: Int, radius: Int) {
        val cx = Mercator.lngToTileX(p.lng, z).toInt()
        val cy = Mercator.latToTileY(p.lat, z).toInt()
        val max = 1 shl z
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val y = cy + dy
            if (y in 0 until max) out += Tile(z, ((cx + dx) % max + max) % max, y)
        }
    }

    /** Points every [SAMPLE_M] along the polyline, each with its distance from the start. */
    private fun resample(route: List<GeoPoint>): List<Pair<GeoPoint, Double>> {
        val pts = ArrayList<Pair<GeoPoint, Double>>()
        var along = 0.0
        pts += route[0] to 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]; val b = route[i]
            val seg = GeoPoint.distMeters(a, b)
            val n = (seg / SAMPLE_M).toInt()
            for (k in 1..n) {
                val f = k * SAMPLE_M / seg
                pts += GeoPoint(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f) to along + k * SAMPLE_M
            }
            along += seg
            pts += b to along
        }
        return pts
    }
}
