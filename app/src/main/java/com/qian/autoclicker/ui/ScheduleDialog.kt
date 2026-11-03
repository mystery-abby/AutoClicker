package com.qian.autoclicker.ui

import android.app.TimePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qian.autoclicker.R
import com.qian.autoclicker.data.RepeatMode
import com.qian.autoclicker.data.Schedule
import com.qian.autoclicker.data.Script
import com.qian.autoclicker.databinding.DialogScheduleBinding
import com.qian.autoclicker.schedule.Scheduler
import com.qian.autoclicker.util.Format
import java.util.Calendar

/**
 * 新建 / 编辑定时任务的对话框。传 existing 时进入编辑模式。
 */
object ScheduleDialog {

    fun show(
        ctx: Context,
        scripts: List<Script>,
        presetScriptId: String?,
        existing: Schedule? = null,
        onSave: (Schedule) -> Unit
    ) {
        if (scripts.isEmpty()) return
        val binding = DialogScheduleBinding.inflate(LayoutInflater.from(ctx))

        val names = scripts.map { it.name }
        binding.scriptSpinner.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, names
        )
        val presetIndex = scripts.indexOfFirst {
            it.id == (existing?.scriptId ?: presetScriptId)
        }
        if (presetIndex >= 0) binding.scriptSpinner.setSelection(presetIndex)

        var mode = existing?.mode ?: RepeatMode.ONCE
        var hour = 8
        var minute = 0
        if (existing != null && existing.mode != RepeatMode.INTERVAL) {
            // DAILY/WEEKDAYS 存的是当天毫秒偏移，ONCE 存的是绝对时刻
            val cal = Calendar.getInstance()
            cal.timeInMillis = if (existing.mode == RepeatMode.ONCE) {
                existing.triggerAt
            } else {
                val base = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                base.timeInMillis + existing.triggerAt
            }
            hour = cal.get(Calendar.HOUR_OF_DAY)
            minute = cal.get(Calendar.MINUTE)
        }
        binding.modeGroup.check(
            when (mode) {
                RepeatMode.DAILY -> R.id.modeDaily
                RepeatMode.WEEKDAYS -> R.id.modeWeekdays
                RepeatMode.INTERVAL -> R.id.modeInterval
                RepeatMode.ONCE -> R.id.modeOnce
            }
        )
        // 编辑模式不会再收到 check 回调（已经是选中态），这里手动同步一次显隐
        binding.timeRow.visibility =
            if (mode == RepeatMode.INTERVAL) android.view.View.GONE else android.view.View.VISIBLE
        binding.intervalRow.visibility =
            if (mode == RepeatMode.INTERVAL) android.view.View.VISIBLE else android.view.View.GONE
        if (existing != null && existing.mode == RepeatMode.INTERVAL) {
            binding.intervalInput.setText(
                (existing.intervalMs / 60_000L).coerceAtLeast(1L).toString()
            )
        }
        // 随机延迟回填（拆分成分 + 秒）
        val randomMax = if (existing == null) {
            Schedule.DEFAULT_RANDOM_DELAY_MS
        } else Schedule.effectiveRandomDelayMs(existing)
        binding.randomMinuteInput.setText((randomMax / 60_000L).toString())
        binding.randomSecondInput.setText(((randomMax % 60_000L) / 1000L).toString())

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.modeDaily -> RepeatMode.DAILY
                R.id.modeWeekdays -> RepeatMode.WEEKDAYS
                R.id.modeInterval -> RepeatMode.INTERVAL
                else -> RepeatMode.ONCE
            }
            binding.timeRow.visibility =
                if (mode == RepeatMode.INTERVAL) android.view.View.GONE else android.view.View.VISIBLE
            binding.intervalRow.visibility =
                if (mode == RepeatMode.INTERVAL) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.pickTimeButton.text = String.format("%02d:%02d", hour, minute)
        binding.pickTimeButton.setOnClickListener {
            TimePickerDialog(ctx, { _, h, m ->
                hour = h
                minute = m
                binding.pickTimeButton.text = String.format("%02d:%02d", h, m)
            }, hour, minute, true).show()
        }

        if (!Scheduler.canScheduleExact(ctx)) {
            binding.exactAlarmHint.visibility = android.view.View.VISIBLE
            binding.exactAlarmHint.setOnClickListener {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${ctx.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.add_schedule else R.string.edit_schedule_title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val index = binding.scriptSpinner.selectedItemPosition
                    .coerceIn(0, scripts.lastIndex)
                val script = scripts[index]
                val intervalMinutes = binding.intervalInput.text
                    ?.toString()?.toLongOrNull()?.coerceAtLeast(1L) ?: 30L
                // 随机延迟：分钟 0..30，秒 0..59，总长不超过 30 分钟
                val randMin = binding.randomMinuteInput.text
                    ?.toString()?.toLongOrNull()?.coerceIn(0L, 30L) ?: 15L
                val randSec = binding.randomSecondInput.text
                    ?.toString()?.toLongOrNull()?.coerceIn(0L, 59L) ?: 0L
                val randomMaxMs = (randMin * 60L + randSec) * 1000L
                    .coerceIn(0L, Schedule.MAX_RANDOM_DELAY_MS)

                val schedule = when (mode) {
                    RepeatMode.ONCE -> Schedule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        scriptId = script.id,
                        enabled = existing?.enabled ?: true,
                        mode = RepeatMode.ONCE,
                        triggerAt = nextOccurrence(hour, minute)
                    )
                    RepeatMode.DAILY -> Schedule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        scriptId = script.id,
                        enabled = existing?.enabled ?: true,
                        mode = RepeatMode.DAILY,
                        triggerAt = Format.ofDayOffset(hour, minute)
                    )
                    RepeatMode.WEEKDAYS -> Schedule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        scriptId = script.id,
                        enabled = existing?.enabled ?: true,
                        mode = RepeatMode.WEEKDAYS,
                        triggerAt = Format.ofDayOffset(hour, minute)
                    )
                    RepeatMode.INTERVAL -> Schedule(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        scriptId = script.id,
                        enabled = existing?.enabled ?: true,
                        mode = RepeatMode.INTERVAL,
                        triggerAt = System.currentTimeMillis() + intervalMinutes * 60_000L,
                        intervalMs = intervalMinutes * 60_000L
                    )
                }
                schedule.randomDelayMaxMs = randomMaxMs
                onSave(schedule)
            }
            .show()
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }
}
