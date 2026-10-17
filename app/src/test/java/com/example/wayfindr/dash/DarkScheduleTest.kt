package com.example.wayfindr.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkScheduleTest {
    @Test fun darkFromSevenPm() {
        assertFalse(DarkSchedule.isNight(18))
        assertTrue(DarkSchedule.isNight(19))
        assertTrue(DarkSchedule.isNight(23))
    }

    @Test fun lightFromFiveAm() {
        assertTrue(DarkSchedule.isNight(0))
        assertTrue(DarkSchedule.isNight(4))
        assertFalse(DarkSchedule.isNight(5))
        assertFalse(DarkSchedule.isNight(12))
    }

    private fun at(h: Int, m: Int) = java.util.Calendar.getInstance().apply {
        set(2026, 9, 16, h, m, 0); set(java.util.Calendar.MILLISECOND, 0)
    }

    @Test fun sleepsUntilNextBoundary() {
        val min = 60_000L
        assertEquals(30 * min, DarkSchedule.msUntilNextChange(at(18, 30)))        // → 7 pm
        assertEquals(10 * 60 * min, DarkSchedule.msUntilNextChange(at(19, 0)))    // → 5 am next day
        assertEquals(60 * min, DarkSchedule.msUntilNextChange(at(4, 0)))          // → 5 am
        assertEquals(14 * 60 * min, DarkSchedule.msUntilNextChange(at(5, 0)))     // → 7 pm
    }

    @Test fun manualIgnoresClock() {
        assertEquals(true, DarkSchedule.effective(auto = false, manualDark = true, night = false))
        assertEquals(false, DarkSchedule.effective(auto = false, manualDark = false, night = true))
        assertEquals(true, DarkSchedule.effective(auto = true, manualDark = false, night = true))
        assertEquals(false, DarkSchedule.effective(auto = true, manualDark = true, night = false))
    }
}
