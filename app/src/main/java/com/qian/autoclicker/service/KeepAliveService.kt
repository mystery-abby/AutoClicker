package com.qian.autoclicker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qian.autoclicker.R
import com.qian.autoclicker.util.AccessibilityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍守护前台服务。
 *
 * 无障碍服务和本应用在同一个进程里，进程被杀 → 服务被系统解绑 → 用户看到"无障碍被关闭"。
 * 国产 ROM 的省电策略在应用处于后台空闲时会优先回收这类进程。
 * 保持一个前台服务可以大幅降低被回收的概率，这是唯一不依赖用户手动加白名单的办法。
 *
 * 注意：无法用代码"重新开启"无障碍（系统安全限制），只能保活 + 提示用户。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1003

        /** 每隔一段时间检查无障碍是否仍处于启用状态 */
        private const val CHECK_INTERVAL_MS = 5_000L

        fun start(ctx: Context) {
            setWanted(ctx, true)
            val intent = Intent(ctx, KeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure {
                // Android 12+ 后台启动前台服务受限时降级，避免直接抛到调用方
                Log.w(TAG, "启动守护服务失败，尝试普通启动", it)
                runCatching { ctx.startService(intent) }
            }
        }

        fun stop(ctx: Context) {
            setWanted(ctx, false)
            runCatching {
                ctx.startService(
                    Intent(ctx, KeepAliveService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            }
        }

        private const val ACTION_STOP = "com.qian.autoclicker.KEEPALIVE_STOP"

        /**
         * 是否应当保持守护。用持久化标记避免"服务被重启 → 发现无障碍已关 → 退出 →
         * START_STICKY 又拉起"的死循环。
         */
        private const val PREFS = "autoclicker"
        private const val KEY_WANTED = "keepalive_wanted"

        private fun setWanted(ctx: Context, wanted: Boolean) {
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WANTED, wanted).apply()
        }

        private fun isWanted(ctx: Context): Boolean =
            ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WANTED, false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 用户已经明确不需要守护（无障碍已关闭），别再自我重启
        if (!isWanted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafely()
        startWatching()
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val notification = buildNotification(
            getString(R.string.keepalive_title),
            getString(R.string.keepalive_text)
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.e(TAG, "进入前台失败", it)
            stopSelf()
        }
    }

    /**
     * 定期检查无障碍是否还开着。如果被系统或用户关掉，就更新通知提示，
     * 并退出守护（避免留下一条误导性的"运行中"通知）。
     */
    private fun startWatching() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                val enabled = runCatching { AccessibilityUtils.isEnabled(this@KeepAliveService) }
                    .getOrDefault(false)
                if (!enabled) {
                    Log.w(TAG, "检测到无障碍服务已被关闭")
                    // 无障碍已关闭，守护失去意义：清掉标记并退出，避免 START_STICKY 反复拉起
                    setWanted(this@KeepAliveService, false)
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val settingsIntent = PendingIntent.getActivity(
            this, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(settingsIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = getString(R.string.keepalive_channel_desc)
            }
        )
    }
}
