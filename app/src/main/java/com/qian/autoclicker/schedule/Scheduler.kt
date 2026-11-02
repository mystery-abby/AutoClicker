package com.qian.autoclicker.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qian.autoclicker.data.RepeatMode
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.data.Schedule
import java.util.Calendar

/**
 * 基于 AlarmManager 的定时调度。
 * - ONCE / DAILY：用 setExactAndAllowWhileIdle 精确定时
 * - INTERVAL：串联式精确闹钟，每次触发后重新排下一次
 */
object Scheduler {

    fun requestCodeFor(scheduleId: String): Int =
        scheduleId.hashCode() and 0x7FFFFFFF

    private fun pendingIntent(ctx: Context, schedule: Schedule): PendingIntent {
        // requestCode 参与 PendingIntent 的等价判断，因此每个任务各不相同
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "${AlarmReceiver.ACTION_FIRE}.${schedule.id}"
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, requestCodeFor(schedule.id), intent, flags)
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** 下一次触发的 epoch 毫秒；返回 null 表示不再触发 */
    fun nextTriggerAt(schedule: Schedule, from: Long = System.currentTimeMillis()): Long? {
        if (!schedule.enabled) return null
        return when (schedule.mode) {
            RepeatMode.ONCE -> {
                // triggerAt 存储为绝对 epoch 毫秒
                if (schedule.triggerAt > from) schedule.triggerAt else null
            }
            RepeatMode.DAILY -> nextAtTimeOfDay(from, schedule.triggerAt, skipWeekends = false)
            RepeatMode.WEEKDAYS -> nextAtTimeOfDay(from, schedule.triggerAt, skipWeekends = true)
            RepeatMode.INTERVAL -> {
                val interval = schedule.intervalMs.coerceAtLeast(60_000L)
                var candidate = schedule.triggerAt
                if (candidate <= from) {
                    val missed = (from - candidate) / interval + 1
                    candidate += missed * interval
                }
                candidate
            }
        }
    }

    /**
     * 计算下一个"每天某时刻"的触发点。
     * @param offsetMs 一天中的毫秒偏移
     * @param skipWeekends true 时跳过周六日（仅工作日）
     */
    private fun nextAtTimeOfDay(from: Long, offsetMs: Long, skipWeekends: Boolean): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 从今天开始逐天尝试，最多找 8 天（足够跨过任何周末）
        repeat(9) {
            val candidate = cal.timeInMillis + offsetMs
            val afterNow = candidate > from
            val dayOk = !skipWeekends || !isWeekend(cal)
            if (afterNow && dayOk) return candidate
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        // 理论上不会走到这里（8 天内必有工作日），兜底返回明天的同一时刻
        return cal.timeInMillis + offsetMs
    }

    private fun isWeekend(cal: Calendar): Boolean = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SATURDAY, Calendar.SUNDAY -> true
        else -> false
    }

    fun scheduleAll(ctx: Context) {
        val schedules = Repository.loadSchedules(ctx)
        schedules.forEach { schedule(ctx, it) }
    }

    fun schedule(ctx: Context, schedule: Schedule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, schedule)
        am.cancel(pi)
        if (!schedule.enabled) return

        val triggerAt = nextTriggerAt(schedule) ?: return
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(ctx: Context, schedule: Schedule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, schedule))
    }
}
