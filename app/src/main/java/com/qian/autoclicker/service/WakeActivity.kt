package com.qian.autoclicker.service

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * 定时任务触发的瞬间，如果屏幕熄灭或处于锁屏，注入的手势没有任何可交互目标，
 * 会被系统直接取消，表现出来就是"停止按钮出现了但脚本没执行"。
 *
 * 这个页面只做三件事，然后立刻关掉自己：
 * 1. 点亮屏幕并置于前台（系统允许后台启动 Activity 的少数场景之一是
 *    AlarmManager 精确闹钟触发的 PendingIntent）；
 * 2. 在无安全锁屏时把锁屏划开；
 * 3. 交还焦点给下层 App。
 *
 * 注意：设置了密码/图案/指纹的安全锁屏无法用代码绕过（系统安全限制），
 * 这种情况下只能把屏幕点亮，用户仍需手动解锁后才能让脚本点得动。
 */
class WakeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 无安全锁屏时直接划开；有密码时 dismiss 会失败，属于预期行为
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            runCatching { km.requestDismissKeyguard(this, null) }
        }

        // 不等待播放，播放由 PlaybackService 独立推进；这里只是把屏幕和锁屏处理掉
        window.decorView.postDelayed({ finish() }, LEAVE_DELAY_MS)
    }

    private companion object {
        /** 留一点时间让系统完成亮屏与解锁动画，再退出 */
        const val LEAVE_DELAY_MS = 600L
    }
}
