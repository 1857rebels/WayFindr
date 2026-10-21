package com.example.wayfindr.viewmodel

/**
 * Drops a ghost joystick press: the bike's stick sometimes reports a second, DIFFERENT direction
 * right after the one the rider pushed (pushed RIGHT, the dash sent RIGHT then DOWN). A press in
 * another direction within the window of the last accepted press is ignored.
 *
 * The same direction twice always passes, so double-DOWN (destination list) and double-tap
 * left/right keep working. Codes outside [directions] (call/media/compass) are never filtered and
 * don't move the window. Window 0 turns the filter off. Set in Settings → "Joystick ghost-press filter".
 *
 * Pure (no Android), so it's unit-tested on the JVM.
 */
class JoystickDebounce(private val directions: Set<Int>) {
    private var lastCode = -1
    private var lastMs = Long.MIN_VALUE / 2

    /** Gap to the last accepted press when [code] is dropped, else null (accepted). */
    fun drop(code: Int, nowMs: Long, windowMs: Long): Long? {
        if (code !in directions) return null
        val gap = nowMs - lastMs
        if (windowMs > 0 && code != lastCode && gap in 0 until windowMs) return gap
        lastCode = code; lastMs = nowMs
        return null
    }
}
