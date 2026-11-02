package com.qian.autoclicker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qian.autoclicker.R
import com.qian.autoclicker.data.Action
import com.qian.autoclicker.databinding.ItemActionBinding

class ActionAdapter(
    private val onEditDelay: (Int, Action) -> Unit,
    private val onDelete: (Int, Action) -> Unit
) : ListAdapter<Action, ActionAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Action>() {
            override fun areItemsTheSame(a: Action, b: Action) = a === b
            override fun areContentsTheSame(a: Action, b: Action) = a == b
        }
    }

    inner class VH(val binding: ItemActionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemActionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val action = getItem(position)
        val ctx = holder.itemView.context
        val b = holder.binding
        b.indexText.text = "${position + 1}"
        b.typeText.text = when (action.type) {
            Action.Type.TAP -> ctx.getString(R.string.action_tap)
            Action.Type.LONG_PRESS -> ctx.getString(R.string.action_long_press)
            Action.Type.SWIPE -> ctx.getString(R.string.action_swipe)
        }
        b.detailText.text = when (action.type) {
            Action.Type.SWIPE -> {
                val base = ctx.getString(
                    R.string.action_detail_swipe, action.x1, action.y1, action.x2, action.y2
                )
                // 有完整轨迹时补上点数，便于确认曲线被记录下来了
                if (action.path.size > 2) {
                    ctx.getString(R.string.action_detail_swipe_path, base, action.path.size)
                } else {
                    base
                }
            }
            else -> ctx.getString(R.string.action_detail_tap, action.x1, action.y1) +
                if (action.type == Action.Type.LONG_PRESS) " · ${action.durationMs}ms" else ""
        }
        b.delayButton.text = ctx.getString(R.string.delay_label, action.delayMs)
        b.delayButton.setOnClickListener { onEditDelay(holder.bindingAdapterPosition, action) }
        b.deleteButton.setOnClickListener { onDelete(holder.bindingAdapterPosition, action) }
    }
}
