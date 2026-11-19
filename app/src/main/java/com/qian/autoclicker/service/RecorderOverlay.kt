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
import android.widget.FrameLayout
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
    private var canvasParams: WindowManager.LayoutParams? = null
    private var canvasHidden = false
    private var finished = false

    /** 当前这组触摸是否被正常接收（DOWN 未被丢弃） */
    private var touchAccepted = false

    private val actions = mutableListOf<Action>()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastActionTime = System.currentTimeMillis()
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    /** 录制时两个动作之间允许记录的最大间隔，超出按上限处理 */
    private val MAX_RECORD_DELAY_MS = 10_000L

    /** 连续注入失败计数，用于控制条上的提示文案 */
    private var injectFailures = 0

    /** 当前这一笔手势的完整轨迹采样点 */
    private val livePoints = mutableListOf<Pt>()

    fun show() {
        addCanvas()
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
        // 画布全屏覆盖：底部内容也要能点、能录。
        //
        // 「回到主界面」不再依赖系统手势区：上滑手势被画布捕获后识别为 GO_HOME 动作，
        // 回放时用 performGlobalAction(GLOBAL_ACTION_HOME) 真正回桌面——这样它既能录下来，
        // 又能在回放时生效（注入手势无法触发系统导航，必须走全局动作）。
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
        // 控制条作为画布窗口的同级子 View 加入，且位于画布之上（在 XML/添加顺序中靠后）。
        // 这样：控制条区域内的触摸会命中按钮（撤销/完成/关闭），不会被画布拦截；
        // 其余区域的触摸才落到画布被录制。两者同属一个 TYPE_ACCESSIBILITY_OVERLAY 窗口，
        // z 序由视图层级决定（稳定、可预期），彻底规避此前"控制条被画布盖住、点按钮被误录
        // 成动作"的跨窗口 add/re-add 竞态问题。
        val controlsLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 24
            topMargin = 160
        }
        canvasBinding.root.addView(controlsBinding.root, controlsLp)
        setupControls()
        wm.addView(canvasBinding.root, params)
        canvasView = canvasBinding.root
        canvasParams = params
    }

    /** 设置控制条按钮、拖动与注入提示（控制条已是画布窗口的子 View）。 */
    private fun setupControls() {
        controlsBinding.undoButton.setOnClickListener { undo() }
        controlsBinding.finishButton.setOnClickListener { finish() }
        controlsBinding.closeButton.setOnClickListener { finish() }
        makeDraggable(controlsBinding.root)
        // 注入失败时给出可见提示，否则用户只能看到"点了没反应"却无从判断
        onInjectResult = { result ->
            val text = controlsBinding.injectStatusText
            when (result) {
                ClickAccessibilityService.DISPATCH_RESULT_COMPLETED -> {
                    injectFailures = 0
                    text.visibility = View.GONE
                }
                ClickAccessibilityService.DISPATCH_RESULT_REJECTED -> {
                    injectFailures++
                    text.text = themedContext.getString(R.string.inject_busy)
                    text.visibility = View.VISIBLE
                }
                else -> {
                    injectFailures++
                    text.text = themedContext.getString(R.string.inject_failed, injectFailures)
                    text.visibility = View.VISIBLE
                }
            }
        }
        refresh()
    }

    /** 控制条可拖动，避免挡住要点的区域。拖动更新其在画布窗口内的边距。 */
    private fun makeDraggable(view: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        view.setOnTouchListener { _, event ->
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.leftMargin
                    startY = lp.topMargin
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    // 不消费 DOWN，让按钮仍有机会收到点击（未超过 slop 时视为点击）
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.leftMargin = (startX + dx).coerceAtLeast(0)
                        lp.topMargin = (startY + dy).coerceAtLeast(0)
                        view.layoutParams = lp
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    dragging = false
                    // 拖动过则消费事件（不触发按钮点击），否则放行让点击生效
                    wasDragging
                }
                else -> false
            }
        }
    }

    private fun handleTouch(event: MotionEvent) {
        // 注意：派发手势期间画布会被服务临时从窗口层级移除（见
        // ClickAccessibilityService.awaitGesture），因此注入的手势不可能再落到画布上，
        // 也就不会形成"记录→回放→再记录"的死循环，这里无需再 suppress 触摸。
        val overlay = canvasBinding.overlayView
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchAccepted = true
                // 历史采样点是 View 相对坐标，起终点用 rawX/rawY 屏幕绝对坐标。
                // 若浮层窗口原点与屏幕原点不重合（部分 ROM 会给窗口留出状态栏等系统
                // 边距），两者混用会让轨迹中段整体偏移：普通点击看不出差别，
                // 但画手势密码这类依赖精确落点的曲线回放必偏、验证失败。
                // 这里把历史点统一换算成屏幕绝对坐标。
                val offX = event.rawX - event.x
                val offY = event.rawY - event.y
                downX = event.rawX
                downY = event.rawY
                downTime = System.currentTimeMillis()
                livePoints.clear()
                // 用历史点补上 DOWN 之前的一小段，避免快速起笔丢点
                for (i in 0 until event.historySize) {
                    livePoints.add(Pt(event.getHistoricalX(i) + offX, event.getHistoricalY(i) + offY))
                }
                livePoints.add(Pt(downX, downY))
                overlay.livePath = livePoints
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchAccepted) return
                // 一次 MOVE 事件可能夹带多个历史点，逐点采集才能还原曲线
                val offX = event.rawX - event.x
                val offY = event.rawY - event.y
                for (i in 0 until event.historySize) {
                    livePoints.add(Pt(event.getHistoricalX(i) + offX, event.getHistoricalY(i) + offY))
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
                // 识别「回到主界面」：从屏幕底部手势区（下边缘约 64dp 内）起手、向上滑动
                // 超过屏幕高度 30%、且终点落在屏幕上半部分的基本垂直手势，判定为回桌面，
                // 而非普通滑动。回放时用 performGlobalAction(GLOBAL_ACTION_HOME) 真正回桌面
                // （注入手势无法触发系统导航，必须走全局动作）。
                //
                // 判定条件必须收紧：此前只要"起手在底部 + 向上 30%"就记为 GO_HOME，
                // 从列表底部往上拖动滚动的动作会被误判。回放时它执行的是回桌面，
                // 用户看到的现象就是"录下来的滑动轨迹运行时不生效、界面不滚动"。
                val dm = service.resources.displayMetrics
                val screenH = dm.heightPixels.toFloat()
                val goHomeZone = 64f * dm.density
                val dy = downY - upY
                val dx = kotlin.math.abs(downX - upX)
                // 锁屏/密码界面上的上滑是「划开锁屏」，绝不能记成回桌面：
                // 回放时 GLOBAL_ACTION_HOME 在锁屏上无效，整个解锁脚本会卡在第一步。
                val activePkg = runCatching { service.rootInActiveWindow?.packageName }.getOrNull()
                val onKeyguard = activePkg != null &&
                    (activePkg.contains("systemui", true) || activePkg.contains("keyguard", true))
                val isGoHome = !onKeyguard &&
                    moved &&
                    downY >= screenH - goHomeZone &&
                    dy > screenH * 0.3f &&
                    upY < screenH * 0.5f &&
                    dx < dy
                val action = when {
                    isGoHome -> Action(
                        Action.Type.GO_HOME,
                        downX.toInt(), downY.toInt(),
                        upX.toInt(), upY.toInt(),
                        durationMs = held,
                        delayMs = delay,
                        path = simplify(livePoints)
                    )
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
     * HarmonyOS 4.2.0 的坑：dispatchGesture 注入的手势若落在最上层的
     * TYPE_ACCESSIBILITY_OVERLAY（本录制画布）上，系统会把它当成"服务给自己窗口注入
     * 手势"的回环直接丢弃——于是录制时"计数但页面不动"。4.3.0 已修复该回环判定，
     * 4.2.0 必须由我们在派发瞬间把画布从窗口层级临时移除（见
     * ClickAccessibilityService.awaitGesture 中的 hideCanvasForDispatch/showCanvasAfterDispatch）。
     * 画布移除期间用户的新点击会直接落到下层 App（操作仍然有效），只是那一下不会被记录，
     * 比起"整段失效"是更好的折中。
     */
    private fun replayAndPassThrough(action: Action) {
        val service = ClickAccessibilityService.instance ?: return

        service.replayGesture(action) { result ->
            if (!finished) {
                // 注入彻底失败（被拒）时把这个动作从记录里剔除：
                // 用户看到"计数 +1"却什么也没点中，脚本回放时也会多出一次无效点击。
                // 按内容查找而不是用固定下标——期间用户可能已经点过"撤销"。
                if (result == ClickAccessibilityService.DISPATCH_RESULT_REJECTED) {
                    val idx = actions.indexOfFirst { it === action }
                    if (idx >= 0) {
                        actions.removeAt(idx)
                        refresh()
                    }
                }
            }
            onInjectResult?.invoke(result)
        }
    }

    /**
     * 派发手势前由无障碍服务调用：把全屏画布从窗口层级移除，
     * 让注入的手势直达下层 App（4.2.0 回环丢弃的关键规避点）。
     * 调用方负责在派发结束后调用 [showCanvasAfterDispatch] 恢复。
     */
    fun hideCanvasForDispatch() {
        val view = canvasView ?: return
        if (canvasHidden || finished) return
        runCatching { wm.removeView(view) }
        canvasHidden = true
    }

    /**
     * 派发手势后由无障碍服务调用：把画布重新加回窗口层级。
     * 若已结束（finish 会把画布清掉）则不恢复，避免把已销毁的浮层又显示出来。
     */
    fun showCanvasAfterDispatch() {
        if (!canvasHidden) return
        if (finished) {
            canvasHidden = false
            return
        }
        val view = canvasView ?: run { canvasHidden = false; return }
        val params = canvasParams ?: run { canvasHidden = false; return }
        // 画布窗口整体重新挂回：控制条作为窗口内的子 View 仍在画布之上（视图层级不变），
        // 因此无需、也不应再做跨窗口的 add/re-add —— 那正是此前「撤销/完成被误录」的根源。
        runCatching { wm.addView(view, params) }
        canvasHidden = false
    }

    /** 注入结果回调：1=成功 0=被取消/超时 -1=未派发（队列繁忙） */
    var onInjectResult: ((Int) -> Unit)? = null

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
        (service as? ClickAccessibilityService)?.clearReplayQueue()
        Repository.findScript(service, scriptId)?.let { script ->
            if (replace) script.actions.clear()
            script.actions.addAll(actions)
            Repository.upsertScript(service, script)
        }
        remove()
        (service as? ClickAccessibilityService)?.onRecorderClosed()
    }

    fun remove() {
        // 若画布当前因派发手势被临时移出窗口层级，它已不在窗口里，
        // 这里只清引用，避免对已移除的 view 再调 removeView 抛异常。
        // 控制条是画布窗口的子 View，随画布一起移除，无需单独处理。
        if (!canvasHidden) {
            canvasView?.let { runCatching { wm.removeView(it) } }
        }
        canvasView = null
        canvasHidden = false
    }
}
