package com.example.wayfindr.dash

import java.util.Calendar

/** Automatic dark dash map by clock time: dark from 7 pm until 5 am (phone's local time). */
object DarkSchedule {
    const val DARK_FROM_HOUR = 19
    const val DARK_UNTIL_HOUR = 5

    fun isNight(hourOfDay: Int): Boolean = hourOfDay >= DARK_FROM_HOUR || hourOfDay < DARK_UNTIL_HOUR

    fun isNightNow(): Boolean = isNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

    /** Milliseconds from [now] until the next 5 am or 7 pm (local time). */
    fun msUntilNextChange(now: Calendar = Calendar.getInstance()): Long {
        val next = (now.clone() as Calendar).apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val h = now.get(Calendar.HOUR_OF_DAY)
            when {
                h < DARK_UNTIL_HOUR -> set(Calendar.HOUR_OF_DAY, DARK_UNTIL_HOUR)
                h < DARK_FROM_HOUR -> set(Calendar.HOUR_OF_DAY, DARK_FROM_HOUR)
                else -> { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, DARK_UNTIL_HOUR) }
            }
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    }

    /** Effective dark-map state: the schedule when [auto], otherwise the rider's [manualDark]. */
    fun effective(auto: Boolean, manualDark: Boolean, night: Boolean = isNightNow()): Boolean =
        if (auto) night else manualDark
}
