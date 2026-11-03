package com.qian.autoclicker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qian.autoclicker.R
import com.qian.autoclicker.data.RepeatMode
import com.qian.autoclicker.data.Schedule
import com.qian.autoclicker.databinding.ItemScheduleBinding
import com.qian.autoclicker.schedule.Scheduler
import com.qian.autoclicker.util.Format

class ScheduleAdapter(
    private val scriptName: (String) -> String,
    private val onToggle: (Schedule, Boolean) -> Unit,
    private val onEdit: (Schedule) -> Unit,
    private val onHistory: (Schedule) -> Unit,
    private val onDelete: (Schedule) -> Unit
) : ListAdapter<Schedule, ScheduleAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Schedule>() {
            override fun areItemsTheSame(a: Schedule, b: Schedule) = a.id == b.id
            override fun areContentsTheSame(a: Schedule, b: Schedule) = a == b
        }
    }

    inner class VH(val binding: ItemScheduleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val schedule = getItem(position)
        val ctx = holder.itemView.context
        val b = holder.binding
        val name = scriptName(schedule.scriptId)

        b.timeText.text = when (schedule.mode) {
            RepeatMode.ONCE -> "一次 · " + Format.dateTime(schedule.triggerAt)
            RepeatMode.DAILY -> "每天 · " + Format.timeOfDay(schedule.triggerAt)
            RepeatMode.WEEKDAYS -> ctx.getString(
                R.string.schedule_weekdays, Format.timeOfDay(schedule.triggerAt)
            )
            RepeatMode.INTERVAL -> "每 ${schedule.intervalMs / 60_000} 分钟"
        }
        val next = Scheduler.nextTriggerAt(schedule)
        val nextText = if (schedule.enabled && next != null) {
            "下次 " + Format.dateTime(next)
        } else if (!schedule.enabled) "已停用" else "已过期"
        val meta = StringBuilder(ctx.getString(R.string.meta_schedule_script, name))
            .append(" · ").append(nextText)
        val randomMax = Schedule.effectiveRandomDelayMs(schedule)
        if (randomMax > 0) {
            meta.append(" · ").append(
                ctx.getString(R.string.random_delay_summary, formatSpan(randomMax))
            )
        }
        b.metaText.text = meta

        b.enabledSwitch.setOnCheckedChangeListener(null)
        b.enabledSwitch.isChecked = schedule.enabled
        b.enabledSwitch.setOnCheckedChangeListener { _, checked -> onToggle(schedule, checked) }
        b.historyButton.setOnClickListener { onHistory(schedule) }
        b.editButton.setOnClickListener { onEdit(schedule) }
        b.deleteButton.setOnClickListener { onDelete(schedule) }
    }

    /** 600000 -> "10分钟"，90000 -> "1分30秒" */
    private fun formatSpan(ms: Long): String {
        val total = ms / 1000L
        return when {
            total % 60L == 0L -> "${total / 60}分钟"
            total >= 60L -> "${total / 60}分${(total % 60).toString().padStart(2, '0')}秒"
            else -> "${total}秒"
        }
    }
}
