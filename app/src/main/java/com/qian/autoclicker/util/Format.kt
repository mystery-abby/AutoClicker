package com.qian.autoclicker.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {

    private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fullDate = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun clock(epochMs: Long): String = hhmm.format(Date(epochMs))

    fun dateTime(epochMs: Long): String = fullDate.format(Date(epochMs))

    /** 一天中的毫秒偏移 -> HH:mm */
    fun timeOfDay(offsetMs: Long): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MILLISECOND, offsetMs.toInt())
        }
        return hhmm.format(cal.time)
    }

    fun ofDayOffset(hour: Int, minute: Int): Long =
        (hour * 60L + minute) * 60_000L
}
