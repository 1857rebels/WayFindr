package com.example.wayfindr.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerGestureTest {
    private val RIGHT = 0x13
    private val LEFT = 0x14
    private val DOWN = 0x15
    private fun g() = PickerGesture(RIGHT, LEFT, DOWN, doubleTapMs = 500, doubleDownMs = 1_500)

    // ── double-tap mode ──

    @Test fun `two right taps within 500 ms activate and undo the first tap`() {
        val d = g()
        val first = d.onCode(RIGHT, 1_000, doubleTapMode = true)
        assertTrue(first.plainTap); assertFalse(first.activate)
        val second = d.onCode(RIGHT, 1_400, doubleTapMode = true)
        assertTrue(second.activate); assertTrue(second.undoPrevTap)
    }

    @Test fun `two left taps within 500 ms also activate`() {
        val d = g()
        d.onCode(LEFT, 13_209, true)
        assertTrue(d.onCode(LEFT, 13_338, true).activate)   // the rider's real double-press, 2026-10-16
    }

    @Test fun `left then right quickly is not a double-tap`() {
        val d = g()
        d.onCode(LEFT, 1_000, true)
        assertFalse(d.onCode(RIGHT, 1_200, true).activate)
    }

    @Test fun `two taps further apart are two plain taps`() {
        val d = g()
        d.onCode(RIGHT, 1_000, true)
        val second = d.onCode(RIGHT, 1_600, true)
        assertFalse(second.activate); assertTrue(second.plainTap)
    }

    @Test fun `third quick tap does not activate again`() {
        val d = g()
        d.onCode(RIGHT, 1_000, true)
        assertTrue(d.onCode(RIGHT, 1_300, true).activate)
        assertFalse(d.onCode(RIGHT, 1_500, true).activate)
    }

    @Test fun `double down also works in double-tap mode`() {
        val d = g()
        assertFalse(d.onCode(DOWN, 1_000, true).activate)
        assertTrue(d.onCode(DOWN, 1_400, true).activate)
    }

    // ── down mode ──

    @Test fun `double down activates without undoing anything`() {
        val d = g()
        d.onCode(RIGHT, 5_000, false)
        d.onCode(DOWN, 5_100, false)
        val r = d.onCode(DOWN, 5_200, doubleTapMode = false)
        assertTrue(r.activate); assertFalse(r.undoPrevTap)
    }

    @Test fun `a single down does nothing but a double down within 1_5 s opens`() {
        val d = g()
        assertFalse(d.onCode(DOWN, 1_000, false).activate)
        assertTrue(d.onCode(DOWN, 2_400, false).activate)
        assertFalse(d.onCode(DOWN, 2_600, false).activate)   // third press starts over
    }

    @Test fun `with the 3 s window a re-press after a lost second press still opens`() {
        val d = PickerGesture(RIGHT, LEFT, DOWN, doubleTapMs = 500, doubleDownMs = 3_000)
        assertFalse(d.onCode(DOWN, 10_000, false).activate)   // first press; its partner got lost
        assertTrue(d.onCode(DOWN, 12_800, false).activate)    // rider presses again after the hint
        assertFalse(d.onCode(DOWN, 20_000, false).activate)
        assertFalse(d.onCode(DOWN, 23_100, false).activate)   // >3 s: starts over
    }

    @Test fun `two downs too far apart do not open`() {
        val d = g()
        d.onCode(DOWN, 1_000, false)
        assertFalse(d.onCode(DOWN, 2_600, false).activate)
    }

    @Test fun `double-tap does nothing special in down mode`() {
        val d = g()
        d.onCode(RIGHT, 1_000, false)
        assertFalse(d.onCode(RIGHT, 1_200, false).activate)
    }
}
