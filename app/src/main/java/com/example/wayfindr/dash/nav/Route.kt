package com.example.wayfindr.dash.nav

/** Coarse turn category, used for voice prompts. The dash glyph comes from the raw Google
 *  maneuver via [DashGlyph] instead, since the dash distinguishes more cases than this. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE;

    companion object {
        /** Map an OSRM step maneuver (type + modifier) to our enum. */
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart"   -> DEPART
            "arrive"   -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            "fork", "end of road", "turn", "new name", "continue", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left"         -> TURN_LEFT
                    "right"        -> TURN_RIGHT
                    "slight left"  -> SLIGHT_LEFT
                    "slight right" -> SLIGHT_RIGHT
                    "sharp left"   -> SHARP_LEFT
                    "sharp right"  -> SHARP_RIGHT
                    "uturn"        -> UTURN
                    else           -> CONTINUE
                }
            else -> CONTINUE
        }
        fun fromGoogle(maneuver: String): ManeuverType = when (maneuver) {

            "TURN_LEFT" -> TURN_LEFT
            "TURN_RIGHT" -> TURN_RIGHT

            "TURN_SLIGHT_LEFT" -> SLIGHT_LEFT
            "TURN_SLIGHT_RIGHT" -> SLIGHT_RIGHT

            "TURN_SHARP_LEFT" -> SHARP_LEFT
            "TURN_SHARP_RIGHT" -> SHARP_RIGHT

            "UTURN_LEFT",
            "UTURN_RIGHT" -> UTURN

            // Routes API v2 names roundabouts by exit side (LEFT/RIGHT), not rotation.
            "ROUNDABOUT_LEFT",
            "ROUNDABOUT_RIGHT",
            "ROUNDABOUT_CLOCKWISE",
            "ROUNDABOUT_COUNTERCLOCKWISE" -> ROUNDABOUT

            "DEPART" -> DEPART

            "STRAIGHT",
            "MERGE",
            "FORK_LEFT",
            "FORK_RIGHT",
            "RAMP_LEFT",
            "RAMP_RIGHT",
            "FERRY",
            "FERRY_TRAIN" -> CONTINUE

            else -> CONTINUE
        }
    }
}

/** Live traffic classification for a contiguous interval of the route polyline. */
enum class TrafficSpeed { NORMAL, SLOW, TRAFFIC_JAM, UNKNOWN }

data class TrafficInterval(
    val startPointIndex: Int,
    val endPointIndex: Int,
    val speed: TrafficSpeed,
)

/** One routing instruction located at a point along the geometry. */
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: GeoPoint,
    /** Cumulative distance (m) from the route start to this maneuver's location. */
    val cumulativeMeters: Double,
    /** Raw Google Routes API maneuver name (e.g. "TURN_SLIGHT_LEFT"); drives the dash glyph. */
    val googleManeuver: String = "",
) {
    /** Dash turn-glyph byte for the 05 02 field. */
    val dashCode: Int get() = DashGlyph.fromGoogle(googleManeuver)
}

/**
 * Dash turn-glyph codes; each value is written straight into the primary-turn byte. Cross-check: the captured route
 * card template carries 0x3C = 60 = DEPART. The old hard-coded 0x0B (11) is not in that table,
 * which is why the dash always showed the same (left) arrow.
 */
object DashGlyph {
    const val DESTINATION = 0
    const val STRAIGHT = 9
    const val TURN_LEFT = 20
    const val TURN_RIGHT = 21
    const val TURN_SHARP_LEFT = 22
    const val TURN_SHARP_RIGHT = 23
    const val TURN_SLIGHT_LEFT = 24
    const val TURN_SLIGHT_RIGHT = 25
    const val U_TURN_CLOCKWISE = 26
    const val U_TURN_COUNTERCLOCKWISE = 61
    const val MERGE = 27
    const val FORK_LEFT = 6
    const val FORK_RIGHT = 5
    const val OFF_RAMP_LEFT = 8
    const val OFF_RAMP_RIGHT = 7
    const val ROUNDABOUT_CLOCKWISE = 10
    const val ROUNDABOUT_COUNTERCLOCKWISE = 49
    const val DEPART = 60
    const val FERRY_BOAT = 62
    const val FERRY_TRAIN = 63

    /** Roundabout rotation for where I ride: India drives on the left, so roundabouts run
     *  clockwise. The Routes API only gives the exit side, not the rotation. */
    private const val ROUNDABOUT = ROUNDABOUT_CLOCKWISE

    fun fromGoogle(maneuver: String): Int = when (maneuver) {
        "TURN_LEFT"         -> TURN_LEFT
        "TURN_RIGHT"        -> TURN_RIGHT
        "TURN_SLIGHT_LEFT"  -> TURN_SLIGHT_LEFT
        "TURN_SLIGHT_RIGHT" -> TURN_SLIGHT_RIGHT
        "TURN_SHARP_LEFT"   -> TURN_SHARP_LEFT
        "TURN_SHARP_RIGHT"  -> TURN_SHARP_RIGHT
        "UTURN_LEFT"        -> U_TURN_COUNTERCLOCKWISE
        "UTURN_RIGHT"       -> U_TURN_CLOCKWISE
        "MERGE"             -> MERGE
        "FORK_LEFT"         -> FORK_LEFT
        "FORK_RIGHT"        -> FORK_RIGHT
        "RAMP_LEFT"         -> OFF_RAMP_LEFT
        "RAMP_RIGHT"        -> OFF_RAMP_RIGHT
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT",
        "ROUNDABOUT_CLOCKWISE", "ROUNDABOUT_COUNTERCLOCKWISE" -> ROUNDABOUT
        "DEPART"            -> DEPART
        "FERRY"             -> FERRY_BOAT
        "FERRY_TRAIN"       -> FERRY_TRAIN
        else                -> STRAIGHT   // STRAIGHT, NAME_CHANGE, unspecified
    }
}

/** A computed road route from origin to destination. */
data class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    /** Cumulative distance (m) at each geometry vertex — same length as [geometry]. */
    val cumulative: DoubleArray,
    /** Cumulative route distance at the end of each Google route leg / itinerary stop. */
    val legEndCumulative: List<Double> = emptyList(),
    val traffic: List<TrafficInterval> = emptyList(),
    /** Router that actually made this route (BRouter can fall back to OSM). Set by [Router.route]. */
    val source: com.example.wayfindr.dash.RoutingSource? = null,
) {
    val destination: GeoPoint? get() = geometry.lastOrNull()
}
