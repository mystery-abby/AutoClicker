package com.qian.autoclicker.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qian.autoclicker.R
import com.qian.autoclicker.data.ExecRecord
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.data.Schedule
import com.qian.autoclicker.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 定时任务的执行记录（最近 10 条，最新在前）。 */
object HistoryDialog {

    private val full = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun show(ctx: Context, schedule: Schedule) {
        val records = Repository.loadHistory(ctx, schedule.id)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }

        if (records.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.history_empty)
                setTextColor(color(ctx, R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(ctx, 16), 0, dp(ctx, 16))
            })
        } else {
            records.forEachIndexed { i, r -> root.addView(buildRow(ctx, r, i > 0)) }
        }

        val scroll = ScrollView(ctx).apply { addView(root) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.history_title)
            .setView(scroll)
            .setPositiveButton(R.string.cancel, null)
            .show()
    }

    private fun buildRow(ctx: Context, r: ExecRecord, withDivider: Boolean): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        }
        if (withDivider) {
            row.addView(android.view.View(ctx).apply {
                setBackgroundColor(color(ctx, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)
                )
            })
        }

        val ok = r.success
        row.addView(TextView(ctx).apply {
            text = (if (ok) ctx.getString(R.string.exec_result_ok) else ctx.getString(R.string.exec_result_fail)) +
                " · " + full.format(Date(r.at))
            setTextColor(color(ctx, if (ok) R.color.primary else R.color.danger))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })

        val details = buildString {
            append(ctx.getString(R.string.history_planned, full.format(Date(r.plannedAt))))
            if (r.randomDelayMs > 0) {
                append("  ")
                append(ctx.getString(R.string.history_delay, seconds(r.randomDelayMs)))
            }
            append("  ")
            append(ctx.getString(R.string.history_duration, seconds(r.durationMs)))
        }
        row.addView(TextView(ctx).apply {
            text = details
            setTextColor(color(ctx, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(ctx, 3), 0, 0)
        })

        if (r.message.isNotBlank()) {
            row.addView(TextView(ctx).apply {
                text = r.message
                setTextColor(color(ctx, R.color.text_secondary))
                textSize = 12f
                gravity = Gravity.START
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        return row
    }

    /** 毫秒转成易读的 "1分05秒" / "12秒" */
    private fun seconds(ms: Long): String {
        val total = ms / 1000L
        return if (total >= 60) "${total / 60}分${(total % 60).toString().padStart(2, '0')}秒"
        else "${total}秒"
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun color(ctx: Context, res: Int): Int =
        androidx.core.content.ContextCompat.getColor(ctx, res)
}
