package com.qian.autoclicker.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.qian.autoclicker.data.ExecRecord
import com.qian.autoclicker.data.RepeatMode
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.service.PlaybackService
import com.qian.autoclicker.service.ScreenWake
import com.qian.autoclicker.util.AppUi

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.qian.autoclicker.ACTION_FIRE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_PLANNED_AT = "planned_at"
        const val EXTRA_RANDOM_MS = "random_ms"
        private const val TAG = "AlarmReceiver"

        /** 记录本次执行开始时刻，供 PlaybackService 写执行结果 */
        @Volatile
        var pendingRecord: PendingRecord? = null
            private set

        data class PendingRecord(
            val scheduleId: String,
            val plannedAt: Long,
            val randomDelayMs: Long,
            val startedAt: Long
        )

        /** 播放结束时由 PlaybackService 调用，写入执行记录 */
        fun finishRecord(success: Boolean, message: String) {
            val p = pendingRecord ?: return
            pendingRecord = null
            val ctx = appContext ?: return
            Repository.appendHistory(
                ctx, p.scheduleId,
                ExecRecord(
                    at = p.startedAt,
                    plannedAt = p.plannedAt,
                    randomDelayMs = p.randomDelayMs,
                    durationMs = (System.currentTimeMillis() - p.startedAt).coerceAtLeast(0L),
                    success = success,
                    message = message
                )
            )
        }

        @Volatile
        private var appContext: Context? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action?.startsWith(ACTION_FIRE) != true) return
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        appContext = context.applicationContext

        val schedules = Repository.loadSchedules(context)
        val schedule = schedules.firstOrNull { it.id == scheduleId } ?: return
        if (!schedule.enabled) return

        val plannedAt = intent.getLongExtra(EXTRA_PLANNED_AT, System.currentTimeMillis())
        val randomDelayMs = intent.getLongExtra(EXTRA_RANDOM_MS, 0L)

        val script = Repository.findScript(context, schedule.scriptId)
        if (script == null) {
            Log.w(TAG, "schedule $scheduleId 指向的脚本不存在，停用该任务")
            Repository.appendHistory(
                context, scheduleId,
                ExecRecord(
                    at = System.currentTimeMillis(), plannedAt = plannedAt,
                    randomDelayMs = randomDelayMs, durationMs = 0L,
                    success = false, message = "脚本已删除，任务已停用"
                )
            )
            schedule.enabled = false
            Repository.saveSchedules(context, schedules)
            return
        }

        // 本应用正在前台时必须先退到后台：否则注入的手势会打在连点器自己的界面上，
        // 轻则等于没点，重则误触编辑器里的删除按钮把刚启动的播放停掉。
        // onReceive 运行在主线程，可直接操作 Activity。
        AppUi.hideIfVisible()

        // 定时任务常在熄屏/锁屏时触发，必须先把屏幕点亮并尽量解锁，
        // 否则注入的手势没有可交互目标，会被系统取消（现象：只有停止按钮，动作不执行）
        ScreenWake.wakeForSchedule(context)

        // 记下本次执行的起点，播放结束（成功/失败/被停止）时由 PlaybackService 写入记录
        pendingRecord = PendingRecord(
            scheduleId = scheduleId,
            plannedAt = plannedAt,
            randomDelayMs = randomDelayMs,
            startedAt = System.currentTimeMillis()
        )
        if (!PlaybackService.start(context, script.id)) {
            finishRecord(false, "无法启动播放服务")
        }

        when (schedule.mode) {
            RepeatMode.ONCE -> {
                schedule.enabled = false
                Repository.saveSchedules(context, schedules)
            }
            RepeatMode.DAILY -> Scheduler.schedule(context, schedule)
            RepeatMode.WEEKDAYS -> Scheduler.schedule(context, schedule)
            RepeatMode.INTERVAL -> Scheduler.schedule(context, schedule)
        }
    }
}
