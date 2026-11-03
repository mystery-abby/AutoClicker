package com.qian.autoclicker.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * 定时任务的屏幕处理。
 *
 * 从精确闹钟的 BroadcastReceiver 里启动 Activity 是被系统允许的（白名单场景），
 * 所以这里可以直接拉起 [WakeActivity]：它会点亮屏幕并尝试划开无密码锁屏。
 * 同时持有一个短时 WakeLock，避免刚点亮就因为没人按键而立刻回到休眠。
 */
object ScreenWake {

    private const val TAG = "ScreenWake"

    /** 播放期间的亮屏锁，播放结束由 PlaybackService 释放 */
    @Volatile
    private var playbackLock: PowerManager.WakeLock? = null

    fun wakeForSchedule(ctx: Context) {
        val app = ctx.applicationContext
        // 屏幕已经亮着且未锁屏时不要拉起 WakeActivity：
        // 它会把本应用的任务重新拉到前台，反而让手势打在自己的界面上。
        if (needsWake(app)) {
            runCatching {
                app.startActivity(
                    Intent(app, WakeActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                )
            }.onFailure { android.util.Log.w(TAG, "唤起亮屏页失败", it) }
        }

        acquirePlaybackLock(app)
    }

    /** 屏幕熄灭或处于锁屏时才需要唤醒 */
    private fun needsWake(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (!pm.isInteractive) return true
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isKeyguardLocked == true
    }

    /** 播放期间保持屏幕常亮，否则脚本跑到一半屏幕超时会熄屏 */
    fun acquirePlaybackLock(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (playbackLock?.isHeld == true) return
        runCatching {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "AutoClicker:playback"
            ).apply {
                setReferenceCounted(false)
                acquire(PLAYBACK_LOCK_MS)
                playbackLock = this
            }
        }.onFailure { android.util.Log.w(TAG, "申请亮屏锁失败", it) }
    }

    fun releasePlaybackLock() {
        runCatching {
            playbackLock?.takeIf { it.isHeld }?.release()
        }
        playbackLock = null
    }

    /** 最长保持 2 小时，即使漏掉释放也不会永久占着屏幕 */
    private const val PLAYBACK_LOCK_MS = 2 * 60 * 60 * 1000L
}
