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

    private fun pendingIntent(
        ctx: Context,
        schedule: Schedule,
        plannedAt: Long = 0L,
        randomDelayMs: Long = 0L
    ): PendingIntent {
        // requestCode 参与 PendingIntent 的等价判断，因此每个任务各不相同
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "${AlarmReceiver.ACTION_FIRE}.${schedule.id}"
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
            // 把"计划时刻"和"本次随机等待"带给接收器，用于写执行记录
            putExtra(AlarmReceiver.EXTRA_PLANNED_AT, plannedAt)
            putExtra(AlarmReceiver.EXTRA_RANDOM_MS, randomDelayMs)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, requestCodeFor(schedule.id), intent, flags)
    }

    /**
     * 在 [0, maxMs] 内按时/分/秒随机抽取等待时长。
     * 精度到秒，因此先取整到秒再抽，避免出现毫秒级尾巴。
     */
    private fun randomDelayMs(maxMs: Long): Long {
        val maxSeconds = (maxMs / 1000L)
        if (maxSeconds <= 0L) return 0L
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, maxSeconds + 1L) * 1000L
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
        // 先取消旧的（取消时不需要带上本次的额外参数，
        // PendingIntent 的等价判断只看 action / requestCode）
        am.cancel(pendingIntent(ctx, schedule))
        if (!schedule.enabled) return

        val plannedAt = nextTriggerAt(schedule) ?: return
        // 到点后再随机等待一段时间才真正执行。随机值每次登记时重新抽取一次，
        // 因此同一天多次触发（如按间隔）也会有不同的偏移。
        val randomMs = randomDelayMs(Schedule.effectiveRandomDelayMs(schedule))
        val fireAt = plannedAt + randomMs

        val pi = pendingIntent(ctx, schedule, plannedAt, randomMs)
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
        }
    }

    /**
     * 展示用的"下次触发时间"：取计划时刻（不含随机延迟）。
     * 随机值要到登记时才抽取，界面上不适合显示一个变动的数。
     */
    fun nextFireAtForDisplay(schedule: Schedule): Long? = nextTriggerAt(schedule)

    fun cancel(ctx: Context, schedule: Schedule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, schedule))
    }
}
