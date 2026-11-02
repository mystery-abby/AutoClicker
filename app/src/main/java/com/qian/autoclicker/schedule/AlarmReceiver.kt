package com.qian.autoclicker.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.qian.autoclicker.data.RepeatMode
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.service.PlaybackService
import com.qian.autoclicker.service.ScreenWake

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.qian.autoclicker.ACTION_FIRE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action?.startsWith(ACTION_FIRE) != true) return
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return

        val schedules = Repository.loadSchedules(context)
        val schedule = schedules.firstOrNull { it.id == scheduleId } ?: return
        if (!schedule.enabled) return

        val script = Repository.findScript(context, schedule.scriptId)
        if (script == null) {
            Log.w(TAG, "schedule $scheduleId 指向的脚本不存在，停用该任务")
            schedule.enabled = false
            Repository.saveSchedules(context, schedules)
            return
        }

        // 定时任务常在熄屏/锁屏时触发，必须先把屏幕点亮并尽量解锁，
        // 否则注入的手势没有可交互目标，会被系统取消（现象：只有停止按钮，动作不执行）
        ScreenWake.wakeForSchedule(context)
        PlaybackService.start(context, script.id)

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
