package com.qian.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qian.autoclicker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.ui.MainActivity

/**
 * 前台服务，保证播放期间进程存活并展示状态通知。
 * 实际的手势派发在 [ClickAccessibilityService] 中完成。
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001

        /** 启动时等待无障碍服务绑定的最长时间 */
        private const val CONNECT_WAIT_MS = 3_000L

        const val ACTION_START = "com.qian.autoclicker.PLAYBACK_START"
        const val ACTION_STOP = "com.qian.autoclicker.PLAYBACK_STOP"
        const val EXTRA_SCRIPT_ID = "script_id"

        fun start(ctx: Context, scriptId: String): Boolean {
            val intent = Intent(ctx, PlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCRIPT_ID, scriptId)
            }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure { Log.w(TAG, "启动播放服务失败", it) }.isSuccess
        }

        fun stop(ctx: Context) {
            // 先直接停掉无障碍层的播放任务（无需服务存活即可生效），
            // 再通知服务收尾。用 startService 可能把已销毁的服务重新拉起。
            ClickAccessibilityService.instance?.stopPlayback()
            runCatching {
                ctx.startService(Intent(ctx, PlaybackService::class.java).apply {
                    action = ACTION_STOP
                })
            }.onFailure { Log.w(TAG, "停止播放服务失败", it) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlaybackInternal()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID)
                startPlayback(scriptId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(scriptId: String?) {
        // Android 12+ 要求 startForegroundService 后 5 秒内必须 startForeground，
        // 否则 ANR 崩溃。因此先无条件进入前台，再做其余校验。
        startInForeground(getString(R.string.preparing_playback))
        // 播放全程保持屏幕常亮，避免长脚本跑到一半熄屏导致手势被取消
        ScreenWake.acquirePlaybackLock(this)

        if (scriptId == null) {
            Log.w(TAG, "缺少脚本 id，无法播放")
            notifyErrorAndStop()
            return
        }
        val script = Repository.findScript(this, scriptId)
        if (script == null) {
            Log.w(TAG, "脚本不存在：$scriptId")
            notifyErrorAndStop()
            return
        }
        if (script.actions.isEmpty()) {
            Log.w(TAG, "脚本没有动作")
            notifyErrorAndStop()
            return
        }
        // 无障碍实例可能正在绑定中，短暂等待后再判定，避免"点了没反应"
        serviceScope.launch {
            var waited = 0L
            while (ClickAccessibilityService.instance == null && waited < CONNECT_WAIT_MS) {
                delay(100L)
                waited += 100L
            }
            val service = ClickAccessibilityService.instance
            if (service == null) {
                Log.w(TAG, "无障碍服务未开启，无法播放")
                notifyErrorAndStop()
                return@launch
            }
            updateNotification(getString(R.string.playing, script.name), null)
            beginPlayback(service, script)
        }
    }

    private fun beginPlayback(service: ClickAccessibilityService, script: com.qian.autoclicker.data.Script) {
        service.startPlayback(
            actions = script.actions.toList(),
            repeatCount = script.repeatCount,
            onProgress = { index, total ->
                updateNotification(
                    getString(R.string.playing, script.name),
                    "$index / $total"
                )
            },
            onFinished = {
                // 正常走完：落一条成功记录（手动运行没有 pendingRecord，调用为空操作）
                com.qian.autoclicker.schedule.AlarmReceiver.finishRecord(
                    true, getString(R.string.exec_result_done)
                )
                stopPlaybackInternal()
            }
        )
    }

    /**
     * 展示一条失败提示后立即退出。原来这些分支都是静默 stopSelf()，
     * 用户看到的现象就是"点了运行毫无反应"。
     */
    private fun notifyErrorAndStop() {
        // 定时触发的失败也要留下记录，否则用户只看到"没执行"却查不到原因
        com.qian.autoclicker.schedule.AlarmReceiver.finishRecord(
            false, getString(R.string.playback_failed)
        )
        val nm = getSystemService(NotificationManager::class.java)
        runCatching {
            nm.notify(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.playback_failed), getString(R.string.enable_accessibility_first))
            )
        }
        stopPlaybackInternal()
    }

    private fun stopPlaybackInternal() {
        // 用户主动停止（通知栏 / 悬浮按钮）或异常收尾时，记录为"被停止"
        com.qian.autoclicker.schedule.AlarmReceiver.finishRecord(
            false, getString(R.string.exec_result_stopped)
        )
        ClickAccessibilityService.instance?.stopPlayback()
        ScreenWake.releasePlaybackLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        ClickAccessibilityService.instance?.stopPlayback()
        ScreenWake.releasePlaybackLock()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }
}
