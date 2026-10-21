package com.example.wayfindr.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoystickDebounceTest {
    private val R = 0x13; private val L = 0x14; private val D = 0x15
    private fun debounce() = JoystickDebounce(setOf(R, L, D))

    @Test fun `different direction inside the window is dropped`() {
        val d = debounce()
        assertNull(d.drop(R, 1_000, 300))
        assertEquals(120L, d.drop(D, 1_120, 300))
    }

    @Test fun `different direction after the window passes`() {
        val d = debounce()
        assertNull(d.drop(R, 1_000, 300))
        assertNull(d.drop(D, 1_300, 300))
    }

    @Test fun `same direction twice always passes`() {
        val d = debounce()
        assertNull(d.drop(D, 1_000, 300))
        assertNull(d.drop(D, 1_050, 300))
    }

    @Test fun `a dropped press does not restart the window`() {
        val d = debounce()
        assertNull(d.drop(R, 1_000, 300))
        assertEquals(200L, d.drop(D, 1_200, 300))
        assertNull(d.drop(L, 1_310, 300))
    }

    @Test fun `window 0 turns the filter off`() {
        val d = debounce()
        assertNull(d.drop(R, 1_000, 0))
        assertNull(d.drop(D, 1_001, 0))
    }

    @Test fun `non-joystick codes are never filtered`() {
        val d = debounce()
        assertNull(d.drop(R, 1_000, 300))
        assertNull(d.drop(0x18, 1_010, 300))
        assertEquals(20L, d.drop(D, 1_020, 300))
    }
}
