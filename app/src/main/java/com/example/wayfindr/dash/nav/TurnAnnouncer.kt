package com.example.wayfindr.dash.nav

/**
 * Decides WHEN turn guidance fires (no audio here, so it's JVM-tested). Rules:
 *  - each turn gets at most one FAR and one NEAR announcement per trip;
 *  - a turn is identified by its LOCATION (~11 m grid) + type, not by its distance along the
 *    route. The 2-minute traffic refresh and reroutes rebuild the route from the rider's position,
 *    which changes every maneuver's cumulative distance — keying on that made the same turn
 *    "new" again and re-chimed it every refresh (e.g. parked 36 m from a turn: chime every 2 min);
 *  - arrival fires once, and only re-arms after moving clearly away (GPS jitter around the
 *    threshold used to re-trigger it).
 * State is cleared only by [reset] (new destination / end of navigation).
 */
class TurnAnnouncer(
    private val farM: Double = 450.0,
    private val nearM: Double = 60.0,
    private val arriveM: Double = 30.0,
    private val arriveRearmM: Double = 250.0,
) {
    enum class Event { NONE, FAR, NEAR, ARRIVE }

    private val farDone = HashSet<String>()
    private val nearDone = HashSet<String>()
    private var arrived = false

    fun reset() { farDone.clear(); nearDone.clear(); arrived = false }

    fun onTick(maneuver: Maneuver?, distanceM: Double, remainingM: Double): Event {
        if (remainingM in 0.0..arriveM) {
            if (arrived) return Event.NONE
            arrived = true
            return Event.ARRIVE
        }
        if (remainingM > arriveRearmM) arrived = false

        if (maneuver == null || maneuver.type == ManeuverType.ARRIVE) return Event.NONE
        val key = keyOf(maneuver)
        return when {
            distanceM < nearM -> {
                farDone += key
                if (nearDone.add(key)) Event.NEAR else Event.NONE
            }
            distanceM <= farM -> if (farDone.add(key)) Event.FAR else Event.NONE
            else -> Event.NONE
        }
    }

    private fun keyOf(m: Maneuver) =
        "%.4f,%.4f,%s".format(java.util.Locale.ROOT, m.location.lat, m.location.lng, m.type)
}
