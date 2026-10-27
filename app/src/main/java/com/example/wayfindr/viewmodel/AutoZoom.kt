package com.example.wayfindr.viewmodel

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/**
 * Navigation auto zoom, modelled on Google Maps' nav camera (dist/Maps.apk, 2026-10-27: the
 * camera itself is native code not in that APK, but its modes are named in the Java layer —
 * ZOOM_TO_FIT_STEP_RELEVANT_INFO and ZOOM_STICKY_UNTIL_NEXT_MANEUVER_CAMERA_MOMENT):
 *
 *  - Cruising: zoom follows speed — out on fast roads, in at city speed.
 *  - Approaching a turn: fit the turn on screen. Zoom in only as far as still shows the turn
 *    point plus [OUTGOING_M] of the road after it (and the "then" turn when it's close behind),
 *    so the junction is never zoomed off the dash. If the turn doesn't fit, zoom out at once.
 *  - The rider zooms by hand: auto zoom pauses until the next turn is passed (sticky until the
 *    next maneuver), or [MANUAL_HOLD_MS] when there's no route.
 *  - Zooming in waits for the target to hold [ZOOM_IN_HOLD_MS] and moves one level at a time, so
 *    the map doesn't pump at every speed wobble.
 *
 * Pure (no Android), so it's unit-tested on the JVM.
 */
class AutoZoom {
    data class Input(
        val nowMs: Long,
        val speedMps: Float,
        val lat: Double,
        /** Distance along the route to the next turn, null when not navigating. */
        val turnM: Double?,
        /** Identifies the next turn (its cumulative metres); changes once the turn is passed. */
        val turnKey: Double?,
        /** Gap from the next turn to the one after it, when that one should be in view too. */
        val thenGapM: Double?,
        val current: Int,
        val range: IntRange,
        val headingUp: Boolean,
    )

    data class Decision(val zoom: Int, val reason: String)

    private var speedEma = -1.0
    private var lastSpeedMs = 0L
    private var speedZoom = -1
    private var pausedKey: Double? = null
    private var pausedUntilMs = 0L
    private var wantIn = -1
    private var wantInSinceMs = 0L
    private var lastChangeMs = Long.MIN_VALUE / 2

    /** The rider zoomed by hand: leave the zoom alone until the next turn is passed. */
    fun manual(nowMs: Long, turnKey: Double?) {
        pausedKey = turnKey
        pausedUntilMs = nowMs + if (turnKey != null) MANUAL_MAX_HOLD_MS else MANUAL_HOLD_MS
    }

    val paused: Boolean get() = pausedUntilMs > 0L

    /** The zoom to switch to now, or null to keep the current one. */
    fun update(i: Input): Decision? {
        val speed = smoothSpeed(i.nowMs, i.speedMps)
        if (pausedUntilMs > 0L) {
            val turnPassed = pausedKey != null && i.turnKey != pausedKey
            if (!turnPassed && i.nowMs < pausedUntilMs) return null
            pausedUntilMs = 0L; pausedKey = null
        }
        val base = speedZoomFor(speed)
        val aheadPx = if (i.headingUp) AHEAD_PX_HEADING_UP else AHEAD_PX_NORTH_UP
        val window = max(TURN_WINDOW_MIN_M, speed * TURN_WINDOW_S)
        var target = base
        var mustShow = false
        var reason = "%.0f km/h".format(speed * 3.6)
        if (i.turnM != null && i.turnM <= window) {
            val frameM = i.turnM + (i.thenGapM ?: 0.0) + OUTGOING_M
            val fit = fitZoom(frameM, i.lat, aheadPx)
            target = fit.coerceIn(base - MAX_OUT_FOR_TURN, MAX_TURN_ZOOM)
            mustShow = i.current > fit
            reason += ", turn in %.0f m".format(i.turnM) + if (i.thenGapM != null) " + %.0f m".format(i.thenGapM) else ""
        }
        target = target.coerceIn(i.range)
        return when {
            target == i.current -> { wantIn = -1; null }
            target < i.current -> {
                // Zoom out: at once when the turn would be off screen, else one level per step.
                wantIn = -1
                if (!mustShow && i.nowMs - lastChangeMs < STEP_MS) null
                else { lastChangeMs = i.nowMs; Decision(if (mustShow) target else i.current - 1, reason) }
            }
            else -> {
                // Zoom in: only once the target has held a while, one level at a time.
                if (target != wantIn) { wantIn = target; wantInSinceMs = i.nowMs }
                if (i.nowMs - wantInSinceMs < ZOOM_IN_HOLD_MS || i.nowMs - lastChangeMs < STEP_MS) null
                else { lastChangeMs = i.nowMs; Decision(i.current + 1, reason) }
            }
        }
    }

    private fun smoothSpeed(nowMs: Long, mps: Float): Double {
        val dt = if (lastSpeedMs == 0L) 0.0 else (nowMs - lastSpeedMs) / 1000.0
        lastSpeedMs = nowMs
        val a = if (speedEma < 0) 1.0 else (1 - Math.exp(-dt.coerceIn(0.0, 5.0) / SPEED_TAU_S))
        speedEma = if (speedEma < 0) mps.toDouble() else speedEma + (mps - speedEma) * a
        return speedEma
    }

    /** Speed bands with hysteresis so cruising at a band edge doesn't flip the zoom. */
    private fun speedZoomFor(mps: Double): Int {
        val kmh = mps * 3.6
        val raw = SPEED_BANDS.indexOfFirst { kmh < it }.let { if (it < 0) SPEED_BANDS.size else it }
        val cur = if (speedZoom < 0) raw else MAX_SPEED_ZOOM - speedZoom
        val band = when {
            raw > cur && kmh >= SPEED_BANDS[raw - 1] + HYSTERESIS_KMH -> raw
            raw < cur && kmh < SPEED_BANDS[raw] - HYSTERESIS_KMH -> raw
            speedZoom < 0 -> raw
            else -> cur
        }
        speedZoom = MAX_SPEED_ZOOM - band
        return speedZoom
    }

    companion object {
        /** Speed band edges (km/h): below 25 → 19, 25–45 → 18, 45–75 → 17, above → 16. */
        private val SPEED_BANDS = doubleArrayOf(25.0, 45.0, 75.0)
        private const val MAX_SPEED_ZOOM = 19
        private const val HYSTERESIS_KMH = 5.0
        private const val SPEED_TAU_S = 3.0
        /** Closest zoom used for a turn. */
        private const val MAX_TURN_ZOOM = 19
        /** A far turn never pulls the zoom out more than this below the speed zoom. */
        private const val MAX_OUT_FOR_TURN = 2
        /** Road after the turn kept in view, so the rider sees where it goes. */
        const val OUTGOING_M = 30.0
        /** A "then" turn this close behind the next one is framed with it. */
        const val THEN_TURN_MAX_GAP_M = 100.0
        /** Start framing the turn this far ahead (or [TURN_WINDOW_S] at the current speed). */
        private const val TURN_WINDOW_MIN_M = 150.0
        private const val TURN_WINDOW_S = 12.0
        /**
         * Pixels of road visible ahead of the rider inside the round dash. Heading-up puts the
         * rider at 66% of the 300 px frame, ~186 px below the top of the visible circle; north-up
         * centres the rider (~138 px). Using ~60% leaves room for the road beyond the turn.
         */
        const val AHEAD_PX_HEADING_UP = 110.0
        const val AHEAD_PX_NORTH_UP = 85.0
        private const val ZOOM_IN_HOLD_MS = 2_000L
        private const val STEP_MS = 1_500L
        private const val MANUAL_HOLD_MS = 120_000L
        private const val MANUAL_MAX_HOLD_MS = 600_000L

        /** Metres per pixel of 256 px web-Mercator tiles. */
        fun metresPerPx(zoom: Int, lat: Double): Double =
            156_543.033_92 * cos(Math.toRadians(lat)) / (1 shl zoom)

        /** Highest zoom at which [distM] fits in [aheadPx] pixels. */
        fun fitZoom(distM: Double, lat: Double, aheadPx: Double): Int =
            floor(ln(aheadPx * 156_543.033_92 * cos(Math.toRadians(lat)) / distM.coerceAtLeast(1.0)) / ln(2.0)).toInt()
    }
}
