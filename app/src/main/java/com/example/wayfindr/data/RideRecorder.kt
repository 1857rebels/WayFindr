package com.example.wayfindr.data

import com.example.wayfindr.dash.nav.GeoPoint
import com.example.wayfindr.dash.nav.PolylineCodec

/**
 * Accumulates GPS fixes for one ride (a connect→disconnect dash session) and produces a
 * [Ride] at the end. Points are thinned to ~every [MIN_MOVE_M] metres so the stored track
 * stays small; distance/speed are computed from the accepted points.
 *
 * Not thread-safe — driven from a single coroutine in DashViewModel.
 */
class RideRecorder {

    private val points = ArrayList<GeoPoint>()
    private var startMs = 0L
    private var lastMs = 0L
    private var distanceM = 0.0
    private var maxSpeed = 0.0
    private var last: GeoPoint? = null
    private var recording = false

    val isRecording: Boolean get() = recording
    /** Distance ridden so far this session (m). */
    val distanceSoFarM: Double get() = distanceM

    fun start() {
        points.clear(); distanceM = 0.0; maxSpeed = 0.0; last = null
        startMs = System.currentTimeMillis(); lastMs = startMs
        recording = true
    }

    fun add(lat: Double, lng: Double, speedMps: Float, accuracyM: Float, timeMs: Long) {
        if (!recording) return
        // Drop noisy fixes entirely: a fix accurate only to 30 m wanders ~30 m between samples,
        // and that wander gets counted as travel — the main reason the recorded distance ran
        // ~0.2 km over the dash's odometer. Only trust reasonably precise fixes.
        if (accuracyM > ACC_GATE_M) return
        val p = GeoPoint(lat, lng)
        val prev = last
        if (prev == null) {
            points.add(p); last = p; lastMs = timeMs; return
        }
        val step = GeoPoint.distMeters(prev, p)
        if (step < MIN_MOVE_M) return   // thin out jitter / stationary noise
        // Suppress parked drift: a step that shows up while essentially not moving is GPS wander,
        // not distance ridden. Still advance the point so we don't bank a jump when you set off.
        if (speedMps >= STILL_SPEED) distanceM += step
        // Only count max speed on genuine movement, so parked GPS speed spikes don't inflate it.
        if (speedMps > maxSpeed) maxSpeed = speedMps.toDouble()
        points.add(p); last = p; lastMs = timeMs
    }

    /**
     * Finish the ride. Returns null for a trivial session so we don't litter the history with
     * parking-lot blips. A ride must have COVERED GROUND ([MIN_RIDE_M]); the old rule also kept any
     * session that merely LASTED a while, which saved 0 km "rides" whenever you connected and sat
     * (traffic/parked) for >90 s before disconnecting — distance is the only thing that makes it a ride.
     */
    fun stop(): Ride? {
        recording = false
        val end = System.currentTimeMillis()
        val durationS = ((end - startMs) / 1000L).coerceAtLeast(0)
        if (points.size < 2 || distanceM < MIN_RIDE_M) return null
        val avg = if (durationS > 0) distanceM / durationS else 0.0
        val first = points.first(); val lastPt = points.last()
        return Ride(
            sid = WayfindrDb.newSid(),
            startMs = startMs, endMs = end,
            distanceMeters = distanceM, durationSec = durationS,
            avgSpeedMps = avg, maxSpeedMps = maxSpeed,
            trackPolyline = PolylineCodec.encode(points),
            startLat = first.lat, startLng = first.lng, endLat = lastPt.lat, endLng = lastPt.lng,
        )
    }

    companion object {
        private const val MIN_MOVE_M = 8.0     // thin track points to ~every 8 m
        private const val MIN_RIDE_M = 150.0   // discard rides that covered less than this (parking-lot blips)
        private const val ACC_GATE_M = 20f     // ignore fixes worse than this (m); noisy fixes inflate distance
        private const val STILL_SPEED = 0.7f   // m/s (~2.5 km/h); below this, a step is parked GPS drift
    }
}
