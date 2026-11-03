package com.qian.autoclicker.data

import java.util.UUID

/** 轨迹上的一个采样点（屏幕绝对像素） */
data class Pt(val x: Float, val y: Float)

/**
 * 一个动作。坐标为屏幕绝对像素。
 */
data class Action(
    val type: Type,
    val x1: Int,
    val y1: Int,
    val x2: Int = x1,
    val y2: Int = y1,
    /** 手势自身持续时长（长按/滑动），毫秒 */
    val durationMs: Long = 0L,
    /** 本动作执行完到下一个动作之间的等待，毫秒 */
    val delayMs: Long = 500L,
    /**
     * 滑动的完整轨迹采样点（含起点与终点）。
     * 只记录起终点会把曲线拉成直线，所以需要保留中间路径。
     * 老数据没有这个字段时为空，回放时退化为直线。
     */
    val path: List<Pt> = emptyList()
) {
    enum class Type { TAP, LONG_PRESS, SWIPE }

    /** 取用于绘制/回放的轨迹：优先完整路径，老数据退化为起终点 */
    fun effectivePath(): List<Pt> = when {
        path.size >= 2 -> path
        type == Type.SWIPE -> listOf(
            Pt(x1.toFloat(), y1.toFloat()),
            Pt(x2.toFloat(), y2.toFloat())
        )
        else -> listOf(Pt(x1.toFloat(), y1.toFloat()))
    }
}

data class Script(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var actions: MutableList<Action> = mutableListOf(),
    /** 0 表示无限循环 */
    var repeatCount: Int = 1
)

enum class RepeatMode {
    ONCE,
    DAILY,
    /** 每周一至周五的同一时刻 */
    WEEKDAYS,
    INTERVAL
}

data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    var scriptId: String,
    var enabled: Boolean = true,
    var mode: RepeatMode = RepeatMode.ONCE,
    /** ONCE/DAILY 用：一天中的毫秒偏移; INTERVAL 用：首次触发的 epoch 毫秒 */
    var triggerAt: Long = 0L,
    /** INTERVAL 模式的间隔，毫秒 */
    var intervalMs: Long = 0L,
    /**
     * 到点后再随机等待的上限（毫秒），0 表示准点执行。
     * 实际等待值在 [0, 该值] 内按时/分/秒随机抽取。
     * 旧版本数据没有这个字段，Gson 反序列化后为 null，读取时归一化为默认 15 分钟。
     */
    var randomDelayMaxMs: Long? = DEFAULT_RANDOM_DELAY_MS
) {
    companion object {
        /** 随机延迟默认 15 分钟 */
        const val DEFAULT_RANDOM_DELAY_MS = 15 * 60_000L

        /** 随机延迟上限 30 分钟 */
        const val MAX_RANDOM_DELAY_MS = 30 * 60_000L

        /** 取生效的随机延迟（null 视为默认值） */
        fun effectiveRandomDelayMs(schedule: Schedule): Long =
            (schedule.randomDelayMaxMs ?: DEFAULT_RANDOM_DELAY_MS)
                .coerceIn(0L, MAX_RANDOM_DELAY_MS)
    }
}

/**
 * 一次定时任务的执行记录。
 */
data class ExecRecord(
    /** 实际开始执行的时间点（含随机延迟后的真实时刻） */
    val at: Long = 0L,
    /** 计划触发时间点（闹钟原定时刻，不含随机延迟） */
    val plannedAt: Long = 0L,
    /** 本次随机等待的时长，毫秒 */
    val randomDelayMs: Long = 0L,
    /** 执行耗时，毫秒 */
    val durationMs: Long = 0L,
    val success: Boolean = false,
    /** 结果说明：完成 / 被停止 / 无障碍未开启 等 */
    val message: String = ""
)
