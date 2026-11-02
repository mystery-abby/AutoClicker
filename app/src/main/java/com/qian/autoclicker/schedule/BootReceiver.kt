package com.qian.autoclicker.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qian.autoclicker.service.KeepAliveService
import com.qian.autoclicker.util.AccessibilityUtils

/**
 * 开机或应用更新后：
 * 1. 重新登记所有定时任务（AlarmManager 的闹钟会被清空）
 * 2. 若无障碍仍处于启用状态，重新拉起守护服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Scheduler.scheduleAll(context)
                // 开机时无障碍通常已由系统恢复，这里补上守护
                if (AccessibilityUtils.isEnabled(context)) {
                    KeepAliveService.start(context)
                }
            }
        }
    }
}
