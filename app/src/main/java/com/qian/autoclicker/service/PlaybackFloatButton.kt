package com.qian.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import com.qian.autoclicker.R
import com.qian.autoclicker.databinding.OverlayStopButtonBinding
import kotlin.math.abs

/**
 * 脚本运行期间悬浮在屏幕上的停止按钮。
 *
 * 用 TYPE_ACCESSIBILITY_OVERLAY，无需"显示在其他应用上层"权限。
 * 按钮本身可拖动到任意位置，点一下即停止播放。
 *
 * 注意：这个浮窗会拦截触摸，因此脚本执行到按钮所在区域时无法点击到下层 App。
 * 默认放在屏幕右侧中部偏下，用户如果发现挡住了目标区域可以拖走。
 */
class PlaybackFloatButton(
    private val service: AccessibilityService,
    private val onStop: () -> Unit
) {

    private val wm =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(service, R.style.Theme_AutoClicker)
    private val binding = OverlayStopButtonBinding.inflate(
        LayoutInflater.from(themedContext)
    )
    private var attached = false
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (attached) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = service.resources.displayMetrics
            val saved = loadPosition()
            if (saved != null) {
                // 恢复上次的位置。屏幕尺寸可能变了（旋转/换分辨率），越界时回退到默认位置。
                // 留一点余量，避免按钮整体被推出可视区。
                x = saved.first
                y = saved.second
                if (x >= dm.widthPixels - 24 * dm.density ||
                    y >= dm.heightPixels - 48 * dm.density ||
                    x < 0 || y < 0
                ) {
                    x = defaultX(dm.widthPixels, dm.density)
                    y = defaultY(dm.heightPixels)
                }
            } else {
                // 默认放在屏幕右侧边缘、偏下方，尽量避开常见操作区
                x = defaultX(dm.widthPixels, dm.density)
                y = defaultY(dm.heightPixels)
            }
        }
        binding.stopCircle.setOnClickListener {
            onStop()
        }
        // 长按临时隐藏，避免按钮压住脚本要点击的位置
        binding.stopCircle.setOnLongClickListener {
            temporarilyHide()
            true
        }
        makeDraggable(binding.root, p)
        runCatching { wm.addView(binding.root, p) }
            .onSuccess {
                attached = true
                params = p
                // 等布局完成后再校正边界，否则拿到的宽高是 0
                binding.root.post { clampToScreen(p) }
            }
            .onFailure { android.util.Log.e(TAG, "添加停止浮窗失败", it) }
    }

    /** 更新进度文字，例如 "3/12" */
    fun updateProgress(index: Int, total: Int) {
        if (!attached) return
        binding.progressText.text = "${index + 1}/$total"
    }

    fun remove() {
        if (!attached) return
        attached = false
        runCatching { wm.removeView(binding.root) }
        params = null
    }

    /**
     * 长按后隐藏几秒，让出它占用的屏幕区域。
     * 脚本运行中如果某一步正好要点击这里，可以用这个办法临时让开。
     *
     * 注意：只设 View.GONE 是不够的——窗口仍然存在并会拦截触摸，
     * 必须同时加上 FLAG_NOT_TOUCHABLE 才能真正让手势穿透到下层 App。
     */
    private fun temporarilyHide() {
        val view = binding.root
        val p = params ?: return
        view.visibility = View.GONE
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(view, p) }

        view.postDelayed({
            if (!attached) return@postDelayed
            view.visibility = View.VISIBLE
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(view, p) }
        }, HIDE_DURATION_MS)
    }

    /** 拖动：区分"拖动"和"点击"，位移超过阈值才算拖动 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View, p: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        val slop = android.view.ViewConfiguration.get(service).scaledTouchSlop

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // 用户真正拖动过才记住位置
                        clampToScreen(p, persist = true)
                        true
                    } else {
                        // 未拖动，交给 OnClickListener 处理
                        false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * 把按钮拉回屏幕内，避免被拖出边界后点不到。
     * @param persist 为 true 时把结果写入存储（只在用户拖动结束时传 true，
     *                首次显示时的边界校正不保存，以免把默认位置当成"用户选择"记下来）
     */
    private fun clampToScreen(p: WindowManager.LayoutParams, persist: Boolean = false) {
        val view = binding.root
        val dm = service.resources.displayMetrics
        val width = if (view.width > 0) view.width else (60 * dm.density).toInt()
        val height = if (view.height > 0) view.height else (70 * dm.density).toInt()
        p.x = p.x.coerceIn(0, (dm.widthPixels - width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (dm.heightPixels - height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(view, p) }
        if (persist) savePosition(p.x, p.y)
    }

    private fun defaultX(screenWidth: Int, density: Float): Int =
        (screenWidth - 72 * density).toInt().coerceAtLeast(0)

    private fun defaultY(screenHeight: Int): Int = (screenHeight * 0.62f).toInt()

    private fun prefs() = service.applicationContext
        .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun loadPosition(): Pair<Int, Int>? {
        val p = prefs()
        if (!p.contains(KEY_X) || !p.contains(KEY_Y)) return null
        return p.getInt(KEY_X, 0) to p.getInt(KEY_Y, 0)
    }

    private fun savePosition(x: Int, y: Int) {
        prefs().edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    companion object {
        private const val TAG = "PlaybackFloatButton"

        /** 长按隐藏的时长 */
        private const val HIDE_DURATION_MS = 3_000L

        private const val PREFS = "autoclicker"
        private const val KEY_X = "float_button_x"
        private const val KEY_Y = "float_button_y"
    }
}
