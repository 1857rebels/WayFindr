package com.example.wayfindr.viewmodel

/**
 * The recent joystick presses shown bottom-centre on the dash (Settings → "Show joystick
 * presses"), e.g. "DDRR". Each press lives [lifeMs] after it was made, so the oldest drops off
 * first and the box disappears [lifeMs] after the last press. At most [maxLen] are kept.
 * Pure, so it's JVM-tested.
 */
class JoystickTrail(private val lifeMs: Long = 2_000, private val maxLen: Int = 4) {
    private val presses = ArrayDeque<Pair<Char, Long>>()

    @Synchronized
    fun add(c: Char, nowMs: Long) {
        presses.addLast(c to nowMs)
        while (presses.size > maxLen) presses.removeFirst()
    }

    /** Current text, or null when nothing is live (box hidden). */
    @Synchronized
    fun text(nowMs: Long): String? {
        while (presses.isNotEmpty() && nowMs - presses.first().second >= lifeMs) presses.removeFirst()
        return if (presses.isEmpty()) null else presses.joinToString("") { it.first.toString() }
    }

    @Synchronized
    fun clear() = presses.clear()
}
