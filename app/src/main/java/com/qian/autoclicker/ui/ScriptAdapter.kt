package com.qian.autoclicker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qian.autoclicker.R
import com.qian.autoclicker.data.Script
import com.qian.autoclicker.databinding.ItemScriptBinding

class ScriptAdapter(
    private val onPlay: (Script) -> Unit,
    private val onRecord: (Script) -> Unit,
    private val onEdit: (Script) -> Unit,
    private val onSchedule: (Script) -> Unit,
    private val onDelete: (Script) -> Unit
) : ListAdapter<Script, ScriptAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Script>() {
            override fun areItemsTheSame(a: Script, b: Script) = a.id == b.id
            override fun areContentsTheSame(a: Script, b: Script) =
                a.name == b.name &&
                    a.actions.size == b.actions.size &&
                    a.repeatCount == b.repeatCount &&
                    a.actions == b.actions
        }
    }

    inner class VH(val binding: ItemScriptBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemScriptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val script = getItem(position)
        val ctx = holder.itemView.context
        val b = holder.binding
        b.nameText.text = script.name
        val repeat = if (script.repeatCount == 0) "无限循环" else "循环 ${script.repeatCount} 次"
        b.metaText.text = ctx.getString(R.string.meta_script, script.actions.size, repeat)
        b.playButton.setOnClickListener { onPlay(script) }
        b.recordButton.setOnClickListener { onRecord(script) }
        b.editButton.setOnClickListener { onEdit(script) }
        b.scheduleButton.setOnClickListener { onSchedule(script) }
        b.deleteButton.setOnClickListener { onDelete(script) }
        holder.itemView.setOnClickListener { onEdit(script) }
    }
}
