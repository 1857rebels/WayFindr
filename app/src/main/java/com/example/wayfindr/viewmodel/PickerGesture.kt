package com.example.wayfindr.viewmodel

/**
 * Recognises the destination-list "open" gesture from dash joystick codes.
 *
 * The dash sends one event per press and nothing while the stick is held, so press length can't be
 * measured. Gestures:
 *  - press DOWN: the dash's code [down] (0x15 — confirmed as joystick DOWN in the 2026-10-16 log).
 *    Always on, and always a DOUBLE press of DOWN within [doubleDownMs] (rider's choice: a stray
 *    press never pops the list up). Once the list is open the caller handles DOWN directly.
 *  - double-tap LEFT or RIGHT (Settings → "Destination list: double-tap"): two taps of the same
 *    direction ([left] / [right]) within [doubleTapMs].
 *
 * Pure (no Android), so it's unit-tested on the JVM.
 */
class PickerGesture(
    private val right: Int,
    private val left: Int,
    private val down: Int,
    private val doubleTapMs: Long,
    private val doubleDownMs: Long,
) {
    data class Result(
        /** This event is the open gesture. */
        val activate: Boolean,
        /** Undo the effect of the tap just before it (its zoom) — the first half of a double-tap. */
        val undoPrevTap: Boolean,
        /** A plain LEFT/RIGHT tap: the caller should snapshot state before it acts. */
        val plainTap: Boolean,
    )

    private var lastTapCode = -1
    private var lastTapMs = Long.MIN_VALUE / 2
    private var lastDownMs = Long.MIN_VALUE / 2

    fun onCode(code: Int, nowMs: Long, doubleTapMode: Boolean): Result {
        val isTap = code == right || code == left
        return when {
            doubleTapMode && isTap && code == lastTapCode && nowMs - lastTapMs <= doubleTapMs -> {
                lastTapCode = -1   // a third quick tap starts a new double-tap
                Result(activate = true, undoPrevTap = true, plainTap = false)
            }
            code == down -> {
                val double = nowMs - lastDownMs <= doubleDownMs
                lastDownMs = if (double) Long.MIN_VALUE / 2 else nowMs
                Result(activate = double, undoPrevTap = false, plainTap = false)
            }
            isTap -> {
                lastTapCode = code; lastTapMs = nowMs
                Result(activate = false, undoPrevTap = false, plainTap = true)
            }
            else -> Result(activate = false, undoPrevTap = false, plainTap = false)
        }
    }
}
