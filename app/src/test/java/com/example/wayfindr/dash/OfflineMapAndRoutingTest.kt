package com.example.wayfindr.dash

import com.example.wayfindr.dash.map.MbTiles
import com.example.wayfindr.dash.nav.DashGlyph
import com.example.wayfindr.dash.nav.OfflineRouter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineMapAndRoutingTest {
    @Test fun mbtilesRowsAreFlipped() {
        assertEquals(0, MbTiles.tmsRow(0, 0))
        assertEquals(3, MbTiles.tmsRow(2, 0))
        assertEquals(0, MbTiles.tmsRow(2, 3))
        assertEquals((1 shl 16) - 1 - 30000, MbTiles.tmsRow(16, 30000))
    }

    @Test fun overzoomCropsTheRightQuarter() {
        // One level deeper: a 256 px parent splits into four 128 px quarters.
        assertArrayEquals(intArrayOf(0, 0, 128), MbTiles.overzoomRect(256, 1, 10, 20))     // even x, even y
        assertArrayEquals(intArrayOf(128, 128, 128), MbTiles.overzoomRect(256, 1, 11, 21)) // odd, odd
        // Three levels: 32 px pieces, position from the low 3 bits of x / y.
        assertArrayEquals(intArrayOf(5 * 32, 2 * 32, 32), MbTiles.overzoomRect(256, 3, 8 * 7 + 5, 8 * 9 + 2))
    }

    private fun glyph(cmd: Int) = OfflineRouter.googleManeuverFor(cmd)?.let { DashGlyph.fromGoogle(it) }

    @Test fun brouterHintsMapToDashGlyphs() {
        assertEquals(DashGlyph.TURN_LEFT, glyph(2))
        assertEquals(DashGlyph.TURN_SLIGHT_LEFT, glyph(3))
        assertEquals(DashGlyph.TURN_SHARP_LEFT, glyph(4))
        assertEquals(DashGlyph.TURN_RIGHT, glyph(5))
        assertEquals(DashGlyph.TURN_SLIGHT_RIGHT, glyph(6))
        assertEquals(DashGlyph.TURN_SHARP_RIGHT, glyph(7))
        assertEquals(DashGlyph.FORK_LEFT, glyph(8))
        assertEquals(DashGlyph.FORK_RIGHT, glyph(9))
        assertEquals(DashGlyph.U_TURN_COUNTERCLOCKWISE, glyph(11))
        assertEquals(DashGlyph.ROUNDABOUT_CLOCKWISE, glyph(14))
        assertEquals(DashGlyph.OFF_RAMP_RIGHT, glyph(18))
        assertEquals(DashGlyph.STRAIGHT, glyph(1))
    }

    @Test fun nonTurnHintsAreDropped() {
        assertNull(OfflineRouter.googleManeuverFor(13))   // off route
        assertNull(OfflineRouter.googleManeuverFor(16))   // beeline
        assertNull(OfflineRouter.googleManeuverFor(100))  // end
    }
}
