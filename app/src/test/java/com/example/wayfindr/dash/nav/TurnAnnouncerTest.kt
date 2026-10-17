package com.example.wayfindr.dash.nav

import com.example.wayfindr.dash.nav.TurnAnnouncer.Event
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnAnnouncerTest {
    private fun turn(lat: Double, cum: Double, type: ManeuverType = ManeuverType.TURN_LEFT) =
        Maneuver(type, "", GeoPoint(lat, 77.0), cum)

    @Test fun `far then near once each`() {
        val a = TurnAnnouncer(); val t = turn(12.9, 1_000.0)
        assertEquals(Event.NONE, a.onTick(t, 800.0, 5_000.0))
        assertEquals(Event.FAR, a.onTick(t, 400.0, 5_000.0))
        assertEquals(Event.NONE, a.onTick(t, 300.0, 5_000.0))
        assertEquals(Event.NEAR, a.onTick(t, 50.0, 5_000.0))
        assertEquals(Event.NONE, a.onTick(t, 20.0, 5_000.0))
    }

    @Test fun `route refresh with new cumulative distance does not re-chime the same turn`() {
        val a = TurnAnnouncer()
        assertEquals(Event.NEAR, a.onTick(turn(12.9, 1_000.0), 36.0, 6_900.0))
        // Traffic refresh: route rebuilt from the rider's spot → same turn, cumulative 36 m now.
        assertEquals(Event.NONE, a.onTick(turn(12.9, 36.0), 36.0, 6_900.0))
    }

    @Test fun `gps jitter flipping between two turns does not re-chime`() {
        val a = TurnAnnouncer(); val t1 = turn(12.9, 1_000.0); val t2 = turn(12.91, 1_200.0)
        assertEquals(Event.NEAR, a.onTick(t1, 30.0, 5_000.0))
        a.onTick(t2, 190.0, 5_000.0)
        assertEquals(Event.NONE, a.onTick(t1, 25.0, 5_000.0))
    }

    @Test fun `first seen already close gives only near`() {
        val a = TurnAnnouncer(); val t = turn(12.9, 100.0)
        assertEquals(Event.NEAR, a.onTick(t, 40.0, 5_000.0))
        assertEquals(Event.NONE, a.onTick(t, 100.0, 5_000.0))   // jitter back out to "far" range
    }

    @Test fun `arrival fires once despite jitter around the threshold`() {
        val a = TurnAnnouncer()
        assertEquals(Event.ARRIVE, a.onTick(null, 0.0, 25.0))
        assertEquals(Event.NONE, a.onTick(null, 0.0, 40.0))
        assertEquals(Event.NONE, a.onTick(null, 0.0, 20.0))
    }

    @Test fun `reset allows announcements again`() {
        val a = TurnAnnouncer(); val t = turn(12.9, 1_000.0)
        a.onTick(t, 50.0, 5_000.0); a.reset()
        assertEquals(Event.NEAR, a.onTick(t, 50.0, 5_000.0))
    }
}
