package com.qian.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.qian.autoclicker.R
import com.qian.autoclicker.data.Action
import com.qian.autoclicker.data.Pt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.hypot

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

        /**
         * 手势回调的额外超时。个别 ROM（尤其是 HarmonyOS 4.2.0）在手势被系统取消或
         * 窗口切换时不会回调 GestureResultCallback，协程会永久挂起：录制时表现为"画布
         * 一直不可触摸，之后所有点击都没反应"，播放时表现为整个脚本卡死。
         * 这里把超时设短，且超时时若 dispatchGesture 已返回 true（手势被接受），则直接
         * 当作"已注入成功"处理，避免把"其实点中了"的动作误删或让脚本提前停掉。
         */
        private const val GESTURE_CALLBACK_EXTRA_MS = 400L

        /**
         * 录制期间派发手势前，先把全屏录制画布从窗口层级移除的缓冲时间。
         * removeView 通过主线程消息队列异步生效，必须留出时间让窗口真正消失，
         * 否则注入的手势仍会落回画布（HarmonyOS 4.2.0 会把"服务给自身窗口注入手势"
         * 当成回环直接丢弃）。过长会拖慢连点，过短则移除来不及生效。
         */
        private const val DISPATCH_HIDE_MS = 40L

        /** 点击手势的最小保底时长，保证在迟钝的 ROM 上也能被识别为一次点击 */
        private const val MIN_TAP_MS = 60L

        /**
         * 滑动手势的最小保底时长。录制时用户"按下去立刻甩出"的快速滑动，时长可能只有
         * 几十毫秒，部分 ROM（HarmonyOS / MIUI 等）会把这种过短的滑动手势整条取消，
         * 表现为"回放时滑动轨迹不生效、界面不滚动"。
         */
        private const val MIN_SWIPE_MS = 150L

        /**
         * 滑动注入的最大速度（像素/毫秒）。轨迹很长但录制时长极短的甩动，注入速度会
         * 超过系统允许范围而被丢弃；按此速度上限反推一个更长的时长，宁可慢一点也确保生效。
         */
        private const val MAX_SWIPE_SPEED_PX_PER_MS = 2.5f

        /**
         * 单条滑动轨迹注入时的最大点数。录制时按帧采样（一次滑动几百个点），
         * 多段超长路径在部分 ROM 上会被整条取消或只走第一小段，这里均匀重采样降点数。
         */
        private const val MAX_SWIPE_POINTS = 32

        /** 轨迹上相邻两点的最小间距（像素），更近的点视为重复点，构建 Path 时丢弃 */
        private const val MIN_POINT_DISTANCE_PX = 1f

        /** 执行「回到主界面」前后的停顿，等桌面切换动画稳定再继续后续动作 */
        private const val HOME_SETTLE_MS = 600L

        /** 派发结果：1=完成 0=被取消或超时 -1=根本没派发出去 */
        const val DISPATCH_RESULT_COMPLETED = 1
        const val DISPATCH_RESULT_FAILED = 0
        const val DISPATCH_RESULT_REJECTED = -1

        /** 手势队列繁忙时的最大重试次数 */
        private const val DISPATCH_MAX_RETRY = 4

        /**
         * 录制回放前的延迟，等浮层的"不可触摸"标志生效。
         * 这个值直接叠加到每次点击的响应延迟上，必须尽量小。
         */
        private const val REPLAY_START_DELAY_MS = 10L

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set
    }

    /** 显式主线程 Handler，供 dispatchGesture 回调使用（规避 4.2.0 传 null 时回调丢失） */
    private val gestureHandler = Handler(Looper.getMainLooper())

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
            // 上一次录制残留的回放队列也要清掉，否则新录制的注入会被旧队列插队
            clearReplayQueue()

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
    suspend fun performAction(action: Action): Boolean =
        performActionDetailed(action) == DISPATCH_COMPLETED

    /**
     * 执行单个动作并返回详细结果（见 [DISPATCH_COMPLETED] 等常量）。
     *
     * HarmonyOS / 部分国产 ROM 的 dispatchGesture 在手势队列繁忙时会直接返回 false，
     * 而队列繁忙在快速连点时非常常见。原来只重试一次，仍会丢点击，用户感知就是
     * "点了没反应"。这里改为多次退避重试：被拒（REJECTED）表示手势根本没注入，
     * 重试是安全的。
     */
    /** 当前前台是否为锁屏/钥匙守卫界面（SystemUI 的 keyguard 窗口） */
    private fun isOnKeyguard(): Boolean {
        val pkg = runCatching { rootInActiveWindow?.packageName }.getOrNull() ?: return false
        return pkg.contains("systemui", true) || pkg.contains("keyguard", true)
    }

    suspend fun performActionDetailed(action: Action): Int {
        // 手势时长必须在系统允许范围内，否则构造 StrokeDescription 会抛
        // IllegalArgumentException，进而在协程中崩掉整个进程
        val duration = action.durationMs.coerceIn(1L, MAX_GESTURE_MS)

        // 「回到主界面」用系统全局动作实现：注入手势无法触发系统导航（系统会拒绝），
        // 只有 performGlobalAction(GLOBAL_ACTION_HOME) 才能在录制回放两种场景都真正回桌面。
        if (action.type == Action.Type.GO_HOME) {
            // 锁屏/密码界面上 GLOBAL_ACTION_HOME 完全无效（不解锁、也不上滑）。
            // 解锁脚本的第一步"从底部上滑划开锁屏"在旧版本被记成了 GO_HOME，
            // 回放到锁屏就变成一次无效的回桌面，整个解锁流程卡死、图案永远画不上。
            // 锁屏状态下若该动作保留了录制轨迹，按普通滑动手势回放才能真正划开锁屏。
            if (isOnKeyguard() && action.effectivePath().size >= 2) {
                android.util.Log.i(TAG, "锁屏界面：「回到主界面」按滑动轨迹回放以划开锁屏")
                return dispatchWithRetries(
                    action.copy(type = Action.Type.SWIPE),
                    duration,
                    degradeSwipe = false
                )
            }
            val ok = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                .getOrDefault(false)
            android.util.Log.i(TAG, "执行「回到主界面」GLOBAL_ACTION_HOME: $ok")
            delay(HOME_SETTLE_MS)
            return DISPATCH_COMPLETED
        }

        val result = dispatchWithRetries(action, duration, degradeSwipe = false)
        if (result == DISPATCH_FAILED && action.type == Action.Type.SWIPE) {
            // 带完整轨迹的多段手势被系统取消（部分 ROM 对多段 Path 支持很差：
            // 手势被整条丢弃或只走第一小段），退化为"起点→终点"的直线再试一次。
            // 宁可轨迹简单一点，也要保证界面真正滚动。
            android.util.Log.w(TAG, "轨迹滑动被系统取消，退化为起终点直线重试")
            return dispatchWithRetries(action, duration, degradeSwipe = true)
        }
        return result
    }

    /**
     * 构建并派发一次手势，队列繁忙（REJECTED）时退避重试。
     * @param degradeSwipe 为 true 时忽略录制轨迹，直接用起终点直线，用于兜底重试
     */
    private suspend fun dispatchWithRetries(
        action: Action,
        duration: Long,
        degradeSwipe: Boolean
    ): Int {
        val (gesture, strokeMs) = runCatching { buildGesture(action, duration, degradeSwipe) }.getOrElse { e ->
            android.util.Log.e(TAG, "构建手势失败，跳过该动作", e)
            return DISPATCH_FAILED
        }
        // 系统同一时刻只接受一条注入手势。录制回放队列和播放协程都跑在同一个
        // scope 上，若不加锁，二者的手势会互相挤占，被拒的一方表现为"点了没反应"。
        // 用互斥锁保证全局串行，这是 HarmonyOS 上最关键的约束。
        return gestureMutex.withLock {
            var backoff = 40L
            for (attempt in 0..DISPATCH_MAX_RETRY) {
                val result = awaitGesture(gesture, strokeMs)
                if (result != DISPATCH_REJECTED) return@withLock result
                if (attempt < DISPATCH_MAX_RETRY) {
                    android.util.Log.w(
                        TAG, "dispatchGesture 被拒（队列繁忙），${backoff}ms 后重试 (${attempt + 1}/$DISPATCH_MAX_RETRY)"
                    )
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(400L)
                }
            }
            android.util.Log.e(TAG, "dispatchGesture 连续被拒，放弃本次动作")
            DISPATCH_REJECTED
        }
    }

    /** 派发结果：完成 / 被系统取消或超时 / 根本没派发出去 */
    private val DISPATCH_COMPLETED = DISPATCH_RESULT_COMPLETED
    private val DISPATCH_FAILED = DISPATCH_RESULT_FAILED
    private val DISPATCH_REJECTED = DISPATCH_RESULT_REJECTED

    /** 全局手势互斥：系统同一时刻只接受一条注入手势，录制与播放必须串行 */
    private val gestureMutex = Mutex()

    private suspend fun awaitGesture(
        gesture: GestureDescription,
        duration: Long
    ): Int {
        // HarmonyOS 4.2.0 的已知差异：无障碍服务注入的手势会被最上层的
        // TYPE_ACCESSIBILITY_OVERLAY 窗口（我们的录制画布）吸收——系统把"服务给自己窗口
        // 注入手势"当成回环直接丢弃，导致录制时"计数但页面不动"、回放也整段失效。
        // 4.3.0 已修复该回环判定，4.2.0 仍需我们自己把画布临时移出窗口层级。
        // 录制期间 recorderOverlay 非空，派发前移除画布、派发后再加回。
        val overlay = recorderOverlay
        if (overlay != null) {
            overlay.hideCanvasForDispatch()
            // removeView 通过主线程消息队列异步生效，留出时间让窗口真正从层级中消失，
            // 否则注入手势仍会落回画布。DISPATCH_HIDE_MS 已折中，过长会拖慢连点。
            delay(DISPATCH_HIDE_MS)
        }
        try {
            return suspendCancellableCoroutine { cont ->
                // 兜底超时：手势时长 + 缓冲。系统若无回调，这里解挂，避免录制/播放永久卡住。
                // HarmonyOS 4.2.0 可能根本不回调 onCompleted/onCancelled，但只要
                // dispatchGesture 返回了 true（手势已接受），实际通常已注入成功，
                // 因此超时时按"成功"处理，避免把"其实点中了"的动作误删或让脚本停掉。
                var dispatched = false
                val timeoutJob = scope.launch {
                    delay(duration + GESTURE_CALLBACK_EXTRA_MS)
                    if (cont.isActive) {
                        android.util.Log.w(
                            TAG,
                            "手势回调超时（未收到 onCompleted/onCancelled），按已注入处理"
                        )
                        cont.resume(if (dispatched) DISPATCH_COMPLETED else DISPATCH_FAILED)
                    }
                }
                cont.invokeOnCancellation { timeoutJob.cancel() }
                dispatched = runCatching {
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(description: GestureDescription?) {
                                timeoutJob.cancel()
                                if (cont.isActive) cont.resume(DISPATCH_COMPLETED)
                            }

                            override fun onCancelled(description: GestureDescription?) {
                                timeoutJob.cancel()
                                if (cont.isActive) cont.resume(DISPATCH_FAILED)
                            }
                        },
                        gestureHandler
                    )
                }.getOrElse { e ->
                    android.util.Log.e(TAG, "派发手势失败", e)
                    false
                }
                if (!dispatched && cont.isActive) {
                    timeoutJob.cancel()
                    cont.resume(DISPATCH_REJECTED)
                }
            }
        } finally {
            overlay?.showCanvasAfterDispatch()
        }
    }

    /**
     * 构建手势，同时返回实际使用的笔画时长（滑动的时长可能被抬高，见下方注释）。
     * @param degradeSwipe 为 true 时忽略录制轨迹，直接用起终点直线（轨迹手势被系统取消后的兜底）
     */
    private fun buildGesture(
        action: Action,
        duration: Long,
        degradeSwipe: Boolean = false
    ): Pair<GestureDescription, Long> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - 1).coerceAtLeast(0).toFloat()
        val maxY = (dm.heightPixels - 1).coerceAtLeast(0).toFloat()
        // HarmonyOS 4.2.0 对"落在屏幕可视区域之外"的注入手势会直接丢弃（不回调），
        // 这里把所有坐标夹到 [0, 屏幕尺寸-1]，避免边缘/底部点击静默失效。
        val clampX = { x: Float -> x.coerceIn(0f, maxX) }
        val clampY = { y: Float -> y.coerceIn(0f, maxY) }

        val path = Path()
        var strokeMs = duration
        when (action.type) {
            Action.Type.TAP, Action.Type.LONG_PRESS -> {
                path.moveTo(clampX(action.x1.toFloat()), clampY(action.y1.toFloat()))
                // 点击手势时长过短（<60ms）在部分 4.2.0 机型上不会被识别为点击而整条丢点，
                // 这里给点击类手势一个保底下限。
                if (action.type == Action.Type.TAP) strokeMs = duration.coerceAtLeast(MIN_TAP_MS)
            }
            Action.Type.SWIPE -> {
                // 按录制的完整轨迹回放，曲线才能还原；老数据无轨迹时退化为直线
                val points = swipeStrokePoints(action, degradeSwipe)
                val first = points.first()
                path.moveTo(clampX(first.x), clampY(first.y))
                for (i in 1 until points.size) {
                    path.lineTo(clampX(points[i].x), clampY(points[i].y))
                }
                // 滑动的时长保底：
                // 1) 太短（用户按下就甩出，录制时长可能只有几十毫秒）会被部分 ROM 整条取消；
                // 2) 轨迹很长而时长极短时注入速度超过系统允许范围，同样会被丢弃。
                // 因此按「最小 150ms + 最高 2.5px/ms」反推一个可用的时长，宁可慢一点也不丢动作。
                val length = polylineLength(points)
                strokeMs = maxOf(
                    duration,
                    MIN_SWIPE_MS,
                    (length / MAX_SWIPE_SPEED_PX_PER_MS).toLong()
                ).coerceIn(1L, MAX_GESTURE_MS)
            }
            // 回到主界面由 performActionDetailed 中的 performGlobalAction 处理，不会走到这里，
            // 这里补一个空分支仅为满足 when 的穷尽性检查。
            Action.Type.GO_HOME -> { }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, strokeMs)
        return GestureDescription.Builder().addStroke(stroke).build() to strokeMs
    }

    /**
     * 把录制的轨迹整理成可以直接注入的点列：
     * - 去掉相邻的重复点（零长度线段会让部分 ROM 判定路径非法而整条丢弃手势）
     * - 退化点不足两个时补一个微小的位移，保证手势仍是一次有效滑动
     * - 点数过多时均匀重采样（一次滑动有几百个采样点，多段超长路径在部分 ROM 上只走第一段）
     */
    private fun swipeStrokePoints(action: Action, degradeSwipe: Boolean): List<Pt> {
        val raw = action.effectivePath()
        if (degradeSwipe) {
            val first = raw.first()
            val last = raw.last()
            return if (hypot(last.x - first.x, last.y - first.y) >= MIN_POINT_DISTANCE_PX) {
                listOf(first, last)
            } else {
                listOf(first, Pt(first.x + MIN_POINT_DISTANCE_PX, first.y))
            }
        }
        val cleaned = ArrayList<Pt>(raw.size)
        raw.forEach { p ->
            val prev = cleaned.lastOrNull()
            if (prev == null || hypot(p.x - prev.x, p.y - prev.y) >= MIN_POINT_DISTANCE_PX) {
                cleaned.add(p)
            }
        }
        if (cleaned.size < 2) {
            // 整条轨迹的点重合（例如终点被夹到屏幕边缘），补一个微小位移
            val base = cleaned.firstOrNull() ?: Pt(action.x1.toFloat(), action.y1.toFloat())
            return listOf(base, Pt(base.x + MIN_POINT_DISTANCE_PX, base.y))
        }
        if (cleaned.size <= MAX_SWIPE_POINTS) return cleaned
        // 均匀重采样，保留首尾点
        val sampled = ArrayList<Pt>(MAX_SWIPE_POINTS)
        val step = (cleaned.size - 1).toFloat() / (MAX_SWIPE_POINTS - 1)
        for (i in 0 until MAX_SWIPE_POINTS) {
            sampled.add(cleaned[(i * step).toInt().coerceIn(0, cleaned.lastIndex)])
        }
        // 重采样可能把末点丢掉，强制补上真实终点
        if (sampled.last() !== cleaned.last()) sampled[sampled.lastIndex] = cleaned.last()
        return sampled
    }

    private fun polylineLength(points: List<Pt>): Float {
        var len = 0f
        for (i in 1 until points.size) {
            len += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return len
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
        // 播放与录制互斥：除了移除浮窗，还必须停掉录制残留的回放队列。
        // 否则这个 worker 会继续在 scope 上派发手势，与播放协程抢占系统手势通道，
        // 导致播放的动作连续被拒（表现为"运行脚本又不行了"）。
        clearReplayQueue()
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
                        val result = performActionDetailed(action)
                        val ok = result == DISPATCH_COMPLETED
                        if (!ok) {
                            consecutiveFailures++
                            android.util.Log.w(
                                TAG,
                                "动作 #$index 执行失败（result=$result，连续 $consecutiveFailures 次）"
                            )
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                android.util.Log.e(TAG, "连续失败过多，停止播放")
                                // 只有日志的话，用户只能看到"脚本停了"却不知道原因方向。
                                // 系统级拦截（锁屏密码界面、FLAG_SECURE 安全页面、安全键盘）
                                // 是最常见也最无法绕过的原因，必须明确提示出来。
                                runCatching {
                                    android.widget.Toast.makeText(
                                        this@ClickAccessibilityService,
                                        getString(R.string.gesture_blocked_hint, consecutiveFailures),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
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
    /**
     * 录制阶段的回放队列。HarmonyOS 等系统对"同时存在多个注入手势"很敏感，
     * 快速连点时会大量返回 REJECTED，表现为"点了没反应"。
     * 所有录制回放串行执行，保证同一时刻只有一个手势在跑。
     */
    private var replayChannel: kotlinx.coroutines.channels.Channel<ReplayTask>? = null
    private var replayWorker: Job? = null

    private class ReplayTask(
        val action: Action,
        val onStart: () -> Unit,
        val onDone: (Int) -> Unit
    )

    /**
     * 把一次回放排入队列。
     *
     * [onStart] 在该条手势真正开始注入前触发，[onDone] 在其结束后触发。
     * 浮层用它们「逐条」开关窗口的不可触摸标志——而不是整条队列一起关。
     * 若整条队列一起关，连点 20 下时画布会连续几秒收不到触摸，
     * 这期间的点击既不会被记录也到不了下层 App，就是用户说的"点不动"。
     */
    fun replayGesture(
        action: Action,
        onStart: () -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) {
        ensureReplayWorker()
        val ch = replayChannel ?: return
        val result = ch.trySend(ReplayTask(action, onStart, onDone))
        if (result.isFailure) {
            android.util.Log.w(TAG, "回放队列已满或已关闭，直接执行")
            scope.launch {
                onStart()
                val r = runCatching { performActionDetailed(action) }
                    .getOrDefault(DISPATCH_RESULT_FAILED)
                onDone(r)
            }
        }
    }

    private fun ensureReplayWorker() {
        if (replayWorker?.isActive == true) return
        val ch = kotlinx.coroutines.channels.Channel<ReplayTask>(capacity = 64)
        replayChannel = ch
        replayWorker = scope.launch {
            for (task in ch) {
                runCatching { task.onStart() }
                // 留一点时间让 WindowManager 生效"不可触摸"标志，
                // 避免注入的手势抢在标志生效前又落回浮层
                delay(REPLAY_START_DELAY_MS)
                val result = runCatching { performActionDetailed(task.action) }
                    .getOrDefault(DISPATCH_RESULT_FAILED)
                runCatching { task.onDone(result) }
                // 不再额外冷却：performActionDetailed 内部已有互斥锁，
                // 被拒时也会自己退避重试。追加固定冷却只会让队列越积越长，
                // 连点时画布长时间不可触摸，点击被整片丢掉。
            }
        }
    }

    /** 停止录制时清空待回放的任务，避免浮层关闭后还在注入手势 */
    fun clearReplayQueue() {
        replayChannel?.close()
        replayChannel = null
        replayWorker?.cancel()
        replayWorker = null
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        running.set(false)
        hideFloatButton()
    }

}
