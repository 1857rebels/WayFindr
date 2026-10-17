package com.example.wayfindr.dash.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeRouterTest {
    private fun glyph(valhallaType: Int) = FreeRouter.googleManeuverFor(valhallaType)?.let { DashGlyph.fromGoogle(it) }

    @Test fun turnsMapToDashGlyphs() {
        assertEquals(DashGlyph.TURN_LEFT, glyph(15))
        assertEquals(DashGlyph.TURN_RIGHT, glyph(10))
        assertEquals(DashGlyph.TURN_SLIGHT_LEFT, glyph(16))
        assertEquals(DashGlyph.TURN_SHARP_RIGHT, glyph(11))
        assertEquals(DashGlyph.U_TURN_COUNTERCLOCKWISE, glyph(13))
        assertEquals(DashGlyph.ROUNDABOUT_CLOCKWISE, glyph(26))
        assertEquals(DashGlyph.FORK_LEFT, glyph(24))
        assertEquals(DashGlyph.OFF_RAMP_RIGHT, glyph(20))
        assertEquals(DashGlyph.MERGE, glyph(38))
        assertEquals(DashGlyph.DEPART, glyph(1))
        assertEquals(DashGlyph.STRAIGHT, glyph(8))
    }

    @Test fun nonTurnsAreDropped() {
        assertNull(FreeRouter.googleManeuverFor(4))   // destination
        assertNull(FreeRouter.googleManeuverFor(27))  // roundabout exit
    }

    @Test fun voiceTypeFollows() {
        assertEquals(ManeuverType.TURN_LEFT, ManeuverType.fromGoogle(FreeRouter.googleManeuverFor(15)!!))
        assertEquals(ManeuverType.ROUNDABOUT, ManeuverType.fromGoogle(FreeRouter.googleManeuverFor(26)!!))
    }
}
