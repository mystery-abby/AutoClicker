package com.qian.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.qian.autoclicker.data.Action
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 持有无障碍服务实例，并对外提供手势播放能力。
 * 播放时的坐标来自录制阶段记录的绝对像素，屏幕旋转/分辨率变化会导致偏移，
 * 因此播放前调用 [enableAutoRotateOffFromUi] 之类的手段由上层保证屏幕方向稳定。
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickAccessibilityService"

        /** GestureDescription 允许的单次手势最长时长，超过会被系统拒绝 */
        private const val MAX_GESTURE_MS = 59_000L

        /** 连续这么多次手势失败就认为环境异常，停止播放 */
        private const val MAX_CONSECUTIVE_FAILURES = 5

        /** 开始播放前的等待时间，让界面切换完成 */
        private const val START_GRACE_MS = 800L

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set
    }

    /**
     * 协程里未捕获的异常会直接让进程崩溃，而进程一崩系统就解绑无障碍服务，
     * 用户看到的就是"无障碍莫名其妙被关闭"。这里兜住所有异常，只记日志。
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        android.util.Log.e(TAG, "无障碍服务协程异常（已拦截，避免进程崩溃）", e)
    }

    /**
     * 必须跑在主线程：协程里会直接操作浮窗 View（停止按钮的进度文字、移除浮窗），
     * 而 View 是在主线程 addView 的，从后台线程改会抛 CalledFromWrongThreadException。
     * 那会让播放协程在第一个动作之前就异常结束，表现为"点运行后停止按钮闪一下就消失、
     * 一个手势都不执行"。手势派发是异步回调 + 挂起等待，放主线程不会阻塞 UI。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler
    )
    private var playbackJob: Job? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        stopPlayback()
        recorderOverlay?.remove()
        recorderOverlay = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        stopPlayback()
        recorderOverlay?.remove()
        recorderOverlay = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private var recorderOverlay: RecorderOverlay? = null

    /** 运行期间悬浮在桌面上的停止按钮 */
    private var floatButton: PlaybackFloatButton? = null

    private fun showFloatButton(total: Int) {
        hideFloatButton()
        floatButton = PlaybackFloatButton(this) { stopPlaybackAndService() }.also {
            it.show()
            it.updateProgress(0, total)
        }
    }

    private fun hideFloatButton() {
        floatButton?.remove()
        floatButton = null
    }

    /** 用户点浮窗上的停止：既要停手势派发，也要收掉前台服务与通知 */
    private fun stopPlaybackAndService() {
        stopPlayback()
        PlaybackService.stop(this)
    }

    /**
     * 开始录制浮层。返回 null 表示成功，否则返回错误描述。
     * 任何异常都在这里被吞掉并反馈给界面，绝不让无障碍服务进程崩溃——
     * 一旦崩溃，系统会解绑服务，用户看到的就是"无障碍莫名其妙变成未开启"。
     */
    fun startRecorder(scriptId: String, replace: Boolean): String? {
        return runCatching {
            // 录制与播放必须互斥：正在进行的播放持有的是"开始时"的动作快照，
            // 用户删除动作后它仍会继续回放旧内容，看起来就像"已删除的动作又自动运行了"。
            stopPlayback()

            recorderOverlay?.remove()
            recorderOverlay = null
            val overlay = RecorderOverlay(this, scriptId, replace)
            overlay.show()
            recorderOverlay = overlay
            null
        }.getOrElse { e ->
            android.util.Log.e("ClickAccessibilityService", "启动录制浮层失败", e)
            recorderOverlay?.let { runCatching { it.remove() } }
            recorderOverlay = null
            e.message ?: e.javaClass.simpleName
        }
    }

    fun stopRecorder() {
        recorderOverlay?.remove()
        recorderOverlay = null
    }

    /** 录制浮层自行关闭后（点完成），清掉引用。 */
    fun onRecorderClosed() {
        recorderOverlay = null
    }

    /**
     * 执行单个动作，返回 true 表示手势完成（或被系统取消）。
     */
    suspend fun performAction(action: Action): Boolean {
        // 手势时长必须在系统允许范围内，否则构造 StrokeDescription 会抛
        // IllegalArgumentException，进而在协程中崩掉整个进程
        val duration = action.durationMs.coerceIn(1L, MAX_GESTURE_MS)

        val gesture = runCatching { buildGesture(action, duration) }.getOrElse { e ->
            android.util.Log.e(TAG, "构建手势失败，跳过该动作", e)
            return false
        }

        return suspendCancellableCoroutine { cont ->
            val dispatched = runCatching {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
            }.getOrElse { e ->
                android.util.Log.e(TAG, "派发手势失败", e)
                false
            }
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    private fun buildGesture(
        action: Action,
        duration: Long
    ): GestureDescription {
        val path = Path()
        when (action.type) {
            Action.Type.TAP, Action.Type.LONG_PRESS -> {
                path.moveTo(action.x1.toFloat(), action.y1.toFloat())
            }
            Action.Type.SWIPE -> {
                // 按录制的完整轨迹回放，曲线才能还原；老数据无轨迹时退化为直线
                val points = action.effectivePath()
                path.moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * 播放脚本。onProgress(index, total) 用于给上层/通知刷新进度。
     * 返回是否正常走完（停止或被取消返回 false）。
     */
    fun startPlayback(
        actions: List<Action>,
        repeatCount: Int,
        onProgress: (Int, Int) -> Unit,
        onFinished: () -> Unit
    ) {
        stopPlayback()
        // 播放与录制互斥，避免录制浮层和注入手势互相干扰
        recorderOverlay?.remove()
        recorderOverlay = null

        if (actions.isEmpty()) {
            onFinished()
            return
        }
        running.set(true)
        showFloatButton(actions.size)
        playbackJob = scope.launch {
            try {
                // 调用方刚触发 moveTaskToBack，需要给系统时间把本应用切到后台、目标界面显示出来。
                // 否则第一个动作会打在本应用自己的界面上（可能误触删除按钮）。
                delay(START_GRACE_MS)
                var loop = 0
                var consecutiveFailures = 0
                while (isActive) {
                    if (repeatCount in 1..loop) break
                    for ((index, action) in actions.withIndex()) {
                        if (!isActive) break
                        onProgress(index, actions.size)
                        floatButton?.updateProgress(index, actions.size)
                        // 单个手势被系统取消（例如目标界面正在切换、锁屏、有其它窗口抢占）
                        // 不应该终止整个脚本，跳过该动作继续执行即可。
                        // 连续失败过多才判定为环境异常并停下，避免空转刷屏。
                        val ok = performAction(action)
                        if (!ok) {
                            consecutiveFailures++
                            android.util.Log.w(TAG, "动作 #$index 执行失败（连续 $consecutiveFailures 次）")
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                android.util.Log.e(TAG, "连续失败过多，停止播放")
                                break
                            }
                        } else {
                            consecutiveFailures = 0
                        }
                        delay(action.delayMs.coerceAtLeast(0L))
                    }
                    loop++
                }
            } finally {
                // 必须放在 finally：否则异常时 running 会永远为 true，
                // 界面会一直认为"脚本正在运行"，拒绝后续所有播放请求。
                running.set(false)
                hideFloatButton()
            }
            if (isActive) onFinished()
        }
    }

    /**
     * 录制浮层收到手势后，立刻把它派发回下层 App。
     * 注意：注入的手势会落在最上层可触摸的窗口上，如果浮层仍可触摸就会再次被记录，
     * 形成"记录→回放→再记录"的死循环。调用方必须在回放期间让浮层不可触摸，
     * 这里通过 onDone 回调通知恢复。
     */
    fun replayGesture(
        action: Action,
        startDelayMs: Long = 30L,
        onDone: () -> Unit = {}
    ) {
        scope.launch {
            try {
                // 留一点时间让 WindowManager 生效"不可触摸"标志，避免注入的手势
                // 抢在标志生效前又落回浮层
                if (startDelayMs > 0) delay(startDelayMs)
                performAction(action)
            } finally {
                onDone()
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        running.set(false)
        hideFloatButton()
    }

}
