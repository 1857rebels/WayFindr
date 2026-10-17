package com.example.wayfindr.dash.map

import com.example.wayfindr.dash.nav.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTilePlanTest {
    // Straight line north from Bengaluru; 1° of latitude ≈ 111 km.
    private fun line(km: Double) = listOf(GeoPoint(13.0, 77.6), GeoPoint(13.0 + km / 111.2, 77.6))

    @Test fun `short route gets riding zoom along its whole length`() {
        val route = line(7.0)
        val tiles = RouteTilePlan.plan(route)
        val endY19 = Mercator.latToTileY(route[1].lat, 19).toInt()
        assertTrue(tiles.any { it.z == 19 && it.y == endY19 })
        val n = tiles.size
        assertTrue("7 km plan was $n tiles", n in 300..900)
    }

    @Test fun `long route gets riding zoom only for the first 50 km but overview all the way`() {
        val route = line(200.0)
        val tiles = RouteTilePlan.plan(route)
        val y19At60km = Mercator.latToTileY(13.0 + 60.0 / 111.2, 19).toInt()
        val y19At40km = Mercator.latToTileY(13.0 + 40.0 / 111.2, 19).toInt()
        assertTrue(tiles.any { it.z == 19 && it.y == y19At40km })
        assertTrue(tiles.none { it.z == 19 && it.y == y19At60km })
        val endY14 = Mercator.latToTileY(route[1].lat, 14).toInt()
        assertTrue(tiles.any { it.z == 14 && it.y == endY14 })
    }

    @Test fun `riding tiles come before overview tiles`() {
        val tiles = RouteTilePlan.plan(line(10.0))
        val firstOverview = tiles.indexOfFirst { it.z in RouteTilePlan.OVERVIEW_ZOOMS }
        val lastRiding = tiles.indexOfLast { it.z in RouteTilePlan.RIDING_ZOOMS }
        assertTrue(lastRiding < firstOverview)
    }

    @Test fun `long straight segment has no gaps at z19`() {
        val tiles = RouteTilePlan.plan(line(5.0)).filter { it.z == 19 }.map { it.y }.toSet()
        val y0 = Mercator.latToTileY(13.0, 19).toInt(); val y1 = Mercator.latToTileY(13.0 + 5.0 / 111.2, 19).toInt()
        for (y in minOf(y0, y1)..maxOf(y0, y1)) assertTrue("gap at y=$y", y in tiles)
    }

    @Test fun `no duplicates`() {
        val tiles = RouteTilePlan.plan(line(20.0))
        assertEquals(tiles.size, tiles.toSet().size)
    }
}
