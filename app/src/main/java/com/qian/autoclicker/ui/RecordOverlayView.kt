package com.qian.autoclicker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.qian.autoclicker.data.Action
import com.qian.autoclicker.data.Pt

/**
 * 录制浮层的绘制层：回显已录制动作，并实时绘制当前手指轨迹。
 * 触摸事件由持有它的 Service 处理。
 */
class RecordOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var actions: List<Action> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 正在绘制的那一笔轨迹（手指尚未抬起）。
     * 直接持有可变列表引用，避免每帧全量拷贝造成卡顿；外部只在主线程改动它。
     */
    var livePath: MutableList<Pt>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 只触发重绘，不改变数据引用 */
    fun redraw() = invalidate()

    private val tapFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#664F6BED")
    }

    private val tapStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF4F6BED")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#CC22B573")
    }

    private val swipeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF22B573")
    }

    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        actions.forEachIndexed { index, action ->
            when (action.type) {
                Action.Type.TAP, Action.Type.LONG_PRESS -> {
                    canvas.drawCircle(action.x1.toFloat(), action.y1.toFloat(), 26f, tapFill)
                    canvas.drawCircle(action.x1.toFloat(), action.y1.toFloat(), 26f, tapStroke)
                    val label = if (action.type == Action.Type.LONG_PRESS) "L" else "${index + 1}"
                    canvas.drawText(label, action.x1.toFloat(), action.y1 + 10f, labelPaint)
                }
                Action.Type.SWIPE -> {
                    drawPolyline(canvas, action.effectivePath(), swipePaint)
                }
            }
        }

        // 正在画的这一笔
        livePath?.let { points ->
            if (points.size >= 2) {
                drawPolyline(canvas, points, swipePaint)
            } else if (points.size == 1) {
                canvas.drawCircle(points[0].x, points[0].y, 26f, tapFill)
            }
        }
    }

    private fun drawPolyline(canvas: Canvas, points: List<Pt>, paint: Paint) {
        if (points.size < 2) return
        path.reset()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, paint)
        // 起点/终点用小圆点标出，方便看清轨迹方向
        canvas.drawCircle(points.first().x, points.first().y, 7f, swipeDotPaint)
        canvas.drawCircle(points.last().x, points.last().y, 7f, swipeDotPaint)
    }
}
