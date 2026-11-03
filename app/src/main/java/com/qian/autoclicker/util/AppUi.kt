package com.qian.autoclicker.util

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 记录当前位于前台的界面，供定时任务在开始播放前把本应用让到后台。
 *
 * 定时任务在"本应用处于前台"时触发会出现两个问题：
 * 1. 注入的手势打在连点器自己的界面上，等于什么都没点；
 * 2. 手势可能误触编辑器里的"删除"按钮，进而 stop 掉刚启动的播放，
 *    表现出来就是"定时任务完全没执行"。
 *
 * 手动点运行时会 moveTaskToBack，定时任务走的是广播 → 服务，没有这一步，
 * 所以必须在这里补上。只在界面确实可见（有窗口焦点）时才退到后台，
 * 应用本来就在后台时不做任何事，避免干扰。
 */
object AppUi {

    @Volatile
    private var current: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        current = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    /**
     * 若本应用界面正在前台且可交互，则把整个任务退到后台。
     * 必须在主线程调用（BroadcastReceiver.onCreate 天然满足）。
     */
    fun hideIfVisible(): Boolean {
        val activity = current?.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        // 用生命周期判断"任务是否还在屏幕上"。
        // 不能用 hasWindowFocus：弹出对话框时焦点在对话框窗口，界面其实仍然可见，
        // 此时若跳过退后台，手势就会打在这个对话框上。
        val lifecycle = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val visible = lifecycle?.currentState
            ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ?: false
        if (!visible) return false
        return runCatching { activity.moveTaskToBack(true) }.getOrDefault(false)
    }
}
