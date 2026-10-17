package com.example.wayfindr.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoystickTrailTest {
    @Test fun `presses within 2 s accumulate`() {
        val t = JoystickTrail()
        t.add('D', 0); t.add('D', 400); t.add('R', 900); t.add('R', 1_300)
        assertEquals("DDRR", t.text(1_400))
    }

    @Test fun `oldest press drops off after 2 s`() {
        val t = JoystickTrail()
        t.add('D', 0); t.add('R', 1_500)
        assertEquals("DR", t.text(1_999))
        assertEquals("R", t.text(2_000))
    }

    @Test fun `box disappears 2 s after the last press`() {
        val t = JoystickTrail()
        t.add('L', 0); t.add('R', 1_000)
        assertEquals("R", t.text(2_900))
        assertNull(t.text(3_000))
    }

    @Test fun `keeps only the last 4`() {
        val t = JoystickTrail()
        "DDRRL".forEachIndexed { i, c -> t.add(c, i * 100L) }
        assertEquals("DRRL", t.text(500))
    }
}
