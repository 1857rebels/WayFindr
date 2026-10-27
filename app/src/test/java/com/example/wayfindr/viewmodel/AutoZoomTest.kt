package com.example.wayfindr.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoZoomTest {
    private val lat = 13.0   // Bangalore

    private fun input(
        t: Long, kmh: Double, zoom: Int, turnM: Double? = null, key: Double? = null, then: Double? = null,
    ) = AutoZoom.Input(t, (kmh / 3.6).toFloat(), lat, turnM, key, then, zoom, 11..20, headingUp = true)

    /** Runs the zoom forward like the 4 Hz frame loop and returns where it settles. */
    private fun settle(z: AutoZoom, kmh: Double, start: Int, turnM: Double? = null, key: Double? = null,
                       then: Double? = null, fromMs: Long = 0L, forMs: Long = 30_000L): Int {
        var zoom = start
        var t = fromMs
        while (t <= fromMs + forMs) {
            z.update(input(t, kmh, zoom, turnM, key, then))?.let { zoom = it.zoom }
            t += 250
        }
        return zoom
    }

    /** Is a point [distM] ahead on screen at [zoom]? (within the ahead pixels) */
    private fun visible(distM: Double, zoom: Int) =
        distM <= AutoZoom.AHEAD_PX_HEADING_UP * AutoZoom.metresPerPx(zoom, lat)

    @Test fun `zoom follows speed when no turn is near`() {
        assertEquals(19, settle(AutoZoom(), 15.0, 19))
        assertEquals(18, settle(AutoZoom(), 35.0, 19))
        assertEquals(17, settle(AutoZoom(), 60.0, 19))
        assertEquals(16, settle(AutoZoom(), 90.0, 19))
    }

    @Test fun `the turn and the road after it stay visible on approach`() {
        for (d in listOf(140.0, 100.0, 60.0, 30.0, 10.0)) {
            val zoom = settle(AutoZoom(), 30.0, 19, turnM = d, key = 1.0)
            assertTrue("turn at $d m off screen at zoom $zoom", visible(d + AutoZoom.OUTGOING_M, zoom))
        }
    }

    @Test fun `close to a turn it zooms in, but never past the fit`() {
        val far = settle(AutoZoom(), 30.0, 18, turnM = 140.0, key = 1.0)
        val near = settle(AutoZoom(), 30.0, far, turnM = 20.0, key = 1.0)
        assertTrue(near > far)
        assertEquals(AutoZoom.fitZoom(20.0 + AutoZoom.OUTGOING_M, lat, AutoZoom.AHEAD_PX_HEADING_UP), near)
    }

    @Test fun `a turn off screen pulls the zoom out at once`() {
        val z = AutoZoom()
        z.update(input(0, 30.0, 19))
        val d = z.update(input(250, 30.0, 19, turnM = 120.0, key = 1.0))!!
        assertTrue(visible(120.0 + AutoZoom.OUTGOING_M, d.zoom))
    }

    @Test fun `a close then-turn is kept in view too`() {
        val zoom = settle(AutoZoom(), 30.0, 19, turnM = 40.0, key = 1.0, then = 60.0)
        assertTrue(visible(40.0 + 60.0 + AutoZoom.OUTGOING_M, zoom))
    }

    @Test fun `manual zoom holds until the turn is passed`() {
        val z = AutoZoom()
        z.manual(0, turnKey = 500.0)
        assertNull(z.update(input(10_000, 90.0, 19, turnM = 400.0, key = 500.0)))
        // Next turn now: auto zoom resumes.
        assertEquals(16, settle(z, 90.0, 19, turnM = 2_000.0, key = 3_000.0, fromMs = 20_000))
    }

    @Test fun `zooming in waits and steps one level`() {
        val z = AutoZoom()
        z.update(input(0, 15.0, 16))
        assertNull(z.update(input(1_000, 15.0, 16)))
        assertEquals(17, z.update(input(2_250, 15.0, 16))!!.zoom)
    }
}
