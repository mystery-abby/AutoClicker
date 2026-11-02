package com.qian.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.qian.autoclicker.R
import com.qian.autoclicker.data.Action
import com.qian.autoclicker.data.Pt
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.databinding.OverlayCanvasBinding
import com.qian.autoclicker.databinding.OverlayControlsBinding
import kotlin.math.hypot

/**
 * 录制浮层，由 [AccessibilityService] 创建以使用 TYPE_ACCESSIBILITY_OVERLAY。
 *
 * 全屏画布接管用户手势：记录成 [Action] 后立刻通过无障碍手势 API 回放给下层 App，
 * 因此录制过程中目标界面仍然正常响应。
 * 手势坐标始终使用屏幕绝对坐标（rawX / rawY），与回放时 dispatchGesture 的坐标系一致。
 */
class RecorderOverlay(
    private val service: AccessibilityService,
    private val scriptId: String,
    private val replace: Boolean
) {

    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    /**
     * Service 的 Context 不带 App 主题，直接 inflate 含 MaterialButton / ?attr 的布局会抛
     * InflateException。必须包一层 ContextThemeWrapper 指定应用主题。
     */
    private val themedContext = androidx.appcompat.view.ContextThemeWrapper(
        service, R.style.Theme_AutoClicker
    )
    private val canvasBinding = OverlayCanvasBinding.inflate(
        LayoutInflater.from(themedContext)
    )
    private val controlsBinding = OverlayControlsBinding.inflate(
        LayoutInflater.from(themedContext)
    )
    private var canvasView: View? = null
    private var controlsView: View? = null
    private var finished = false

    private val actions = mutableListOf<Action>()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastActionTime = System.currentTimeMillis()
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    /** 录制时两个动作之间允许记录的最大间隔，超出按上限处理 */
    private val MAX_RECORD_DELAY_MS = 10_000L

    /** 当前这一笔手势的完整轨迹采样点 */
    private val livePoints = mutableListOf<Pt>()

    fun show() {
        addCanvas()
        addControls()
    }

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

    private fun addCanvas() {
        val params = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        canvasBinding.overlayView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
        wm.addView(canvasBinding.root, params)
        canvasView = canvasBinding.root
    }

    private fun addControls() {
        val params = baseParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }
        controlsBinding.undoButton.setOnClickListener { undo() }
        controlsBinding.finishButton.setOnClickListener { finish() }
        controlsBinding.closeButton.setOnClickListener { finish() }
        makeDraggable(controlsBinding.root, params)
        wm.addView(controlsBinding.root, params)
        controlsView = controlsBinding.root
        refresh()
    }

    /** 控制条可拖动，避免挡住要点的区域。 */
    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        params.x = startX + dx
                        params.y = startY + dy
                        wm.updateViewLayout(view, params)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun handleTouch(event: MotionEvent) {
        // 回放期间忽略一切触摸：万一"不可触摸"标志还没生效，注入的手势会落到这里。
        // 不拦截的话就会"记录 → 回放 → 再记录"，形成无限循环。
        if (suppressTouch) {
            // 若 DOWN 被丢弃，后续 MOVE/UP 也不能处理，否则会用陈旧坐标生成错误动作
            touchAccepted = false
            return
        }

        val overlay = canvasBinding.overlayView
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchAccepted = true
                downX = event.rawX
                downY = event.rawY
                downTime = System.currentTimeMillis()
                livePoints.clear()
                // 用历史点补上 DOWN 之前的一小段，避免快速起笔丢点
                for (i in 0 until event.historySize) {
                    livePoints.add(Pt(event.getHistoricalX(i), event.getHistoricalY(i)))
                }
                livePoints.add(Pt(downX, downY))
                overlay.livePath = livePoints
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchAccepted) return
                // 一次 MOVE 事件可能夹带多个历史点，逐点采集才能还原曲线
                for (i in 0 until event.historySize) {
                    livePoints.add(Pt(event.getHistoricalX(i), event.getHistoricalY(i)))
                }
                livePoints.add(Pt(event.rawX, event.rawY))
                // 引用不变，只请求重绘，避免滑动时每帧拷贝整条轨迹
                overlay.redraw()
            }
            MotionEvent.ACTION_UP -> {
                if (!touchAccepted) return
                touchAccepted = false
                val upX = event.rawX
                val upY = event.rawY
                val now = System.currentTimeMillis()
                val held = (now - downTime).coerceAtLeast(1L)
                // 第一个动作的"间隔"没有意义：它等于用户打开浮层后犹豫了多久，
                // 直接照搬会导致回放时脚本先干等几十秒。首个动作固定为 0。
                val delay = if (actions.isEmpty()) {
                    0L
                } else {
                    // 上限 10 秒：用户录制中途停顿过久不应变成回放时的长等待
                    (downTime - lastActionTime).coerceIn(0L, MAX_RECORD_DELAY_MS)
                }
                livePoints.add(Pt(upX, upY))
                // 用"离起点的最大偏移"判断是否滑动：
                // 只看首尾距离会漏掉画圈（终点回到起点附近），只看点数会把抖动误判成滑动
                val maxOffset = livePoints.maxOf { hypot(it.x - downX, it.y - downY) }
                val moved = maxOffset > touchSlop
                val action = when {
                    moved -> Action(
                        Action.Type.SWIPE,
                        downX.toInt(), downY.toInt(),
                        upX.toInt(), upY.toInt(),
                        durationMs = held,
                        delayMs = delay,
                        path = simplify(livePoints)
                    )
                    held >= longPressTimeout -> Action(
                        Action.Type.LONG_PRESS,
                        downX.toInt(), downY.toInt(),
                        durationMs = held,
                        delayMs = delay
                    )
                    else -> Action(
                        Action.Type.TAP,
                        upX.toInt(), upY.toInt(),
                        durationMs = 50L,
                        delayMs = delay
                    )
                }
                actions.add(action)
                lastActionTime = now
                livePoints.clear()
                overlay.livePath = null
                refresh()
                // 画布拦下了手势，补一次回放让下层 App 响应。
                // 回放期间必须让画布不可触摸，否则注入的手势会再次落到浮层上，
                // 导致"记录 → 回放 → 再记录"的无限循环。
                replayAndPassThrough(action)
            }
            MotionEvent.ACTION_CANCEL -> {
                touchAccepted = false
                livePoints.clear()
                overlay.livePath = null
            }
        }
    }

    /**
     * 回放手势并把触摸穿透到下层窗口。
     *
     * dispatchGesture 注入的手势只会被最上层的**可触摸**窗口消费。画布是全屏的，
     * 若回放时它仍可触摸，注入的手势会再次触发 handleTouch，形成无限循环
     * （表现为计数疯涨、目标 App 完全点不动）。
     *
     * 做法：回放前给画布加上 FLAG_NOT_TOUCHABLE，让手势直达下层 App；回放结束再恢复。
     */
    private val pendingReplays = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var suppressTouch = false

    /** 当前这组触摸是否被正常接收（DOWN 未被回放期丢弃） */
    private var touchAccepted = false

    private fun replayAndPassThrough(action: Action) {
        val view = canvasView
        val service = ClickAccessibilityService.instance
        if (view == null || service == null) return

        pendingReplays.incrementAndGet()
        suppressTouch = true
        setCanvasTouchable(false)
        service.replayGesture(action) {
            // 只有最后一个回放结束才恢复，避免快速连点时提前放开
            if (pendingReplays.decrementAndGet() == 0) {
                view.post {
                    if (!finished) {
                        setCanvasTouchable(true)
                        suppressTouch = false
                    }
                }
            }
        }
    }

    /**
     * 抽稀轨迹点。一次滑动可能产生几百个采样点，全部存下来会让脚本体积膨胀、
     * 回放时构建 Path 也慢。用道格拉斯-普克算法在保证形状的前提下压缩。
     */
    private fun simplify(points: List<Pt>, tolerance: Float = 2.5f): List<Pt> {
        if (points.size <= 2) return points.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        douglasPeucker(points, 0, points.lastIndex, tolerance, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun douglasPeucker(
        points: List<Pt>, start: Int, end: Int, tolerance: Float, keep: BooleanArray
    ) {
        if (end <= start + 1) return
        val a = points[start]
        val b = points[end]
        var maxDist = 0f
        var index = -1
        for (i in start + 1 until end) {
            val d = perpendicularDistance(points[i], a, b)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        if (maxDist > tolerance && index != -1) {
            keep[index] = true
            douglasPeucker(points, start, index, tolerance, keep)
            douglasPeucker(points, index, end, tolerance, keep)
        }
    }

    private fun perpendicularDistance(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (dx == 0f && dy == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }

    private fun setCanvasTouchable(touchable: Boolean) {
        val view = canvasView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val newFlags = if (touchable) params.flags and flag.inv() else params.flags or flag
        if (newFlags == params.flags) return
        params.flags = newFlags
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun undo() {
        if (actions.isNotEmpty()) actions.removeAt(actions.lastIndex)
        refresh()
    }

    private fun refresh() {
        val overlay = canvasBinding.overlayView
        overlay.actions = actions.toList()
        controlsBinding.countText.text =
            themedContext.getString(R.string.record_count, actions.size)
    }

    fun finish() {
        if (finished) return
        finished = true
        setCanvasTouchable(true)
        Repository.findScript(service, scriptId)?.let { script ->
            if (replace) script.actions.clear()
            script.actions.addAll(actions)
            Repository.upsertScript(service, script)
        }
        remove()
        (service as? ClickAccessibilityService)?.onRecorderClosed()
    }

    fun remove() {
        canvasView?.let { runCatching { wm.removeView(it) } }
        controlsView?.let { runCatching { wm.removeView(it) } }
        canvasView = null
        controlsView = null
    }
}
