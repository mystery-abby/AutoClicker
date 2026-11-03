package com.qian.autoclicker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qian.autoclicker.R
import com.qian.autoclicker.data.Action
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.data.Script
import com.qian.autoclicker.databinding.ActivityScriptEditorBinding
import com.qian.autoclicker.service.ClickAccessibilityService
import com.qian.autoclicker.service.PlaybackService
import com.qian.autoclicker.util.AccessibilityUtils
import com.qian.autoclicker.util.AppUi
import kotlinx.coroutines.launch

class ScriptEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SCRIPT_ID = "script_id"
        fun intent(ctx: Context, scriptId: String) =
            Intent(ctx, ScriptEditorActivity::class.java).putExtra(EXTRA_SCRIPT_ID, scriptId)
    }

    private lateinit var binding: ActivityScriptEditorBinding
    private lateinit var adapter: ActionAdapter
    private lateinit var script: Script

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_SCRIPT_ID) ?: run { finish(); return }
        script = Repository.findScript(this, id) ?: run { finish(); return }

        adapter = ActionAdapter(
            onEditDelay = ::editDelay,
            onDelete = { position, _ -> deleteAction(position) }
        )
        binding.actionList.layoutManager = LinearLayoutManager(this)
        binding.actionList.adapter = adapter

        binding.nameInput.setText(script.name)
        binding.repeatInput.setText(script.repeatCount.toString())
        binding.backButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }
        binding.recordButton.setOnClickListener { startRecording() }
        binding.playButton.setOnClickListener { play() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // 录制浮层可能已写入新动作
        Repository.findScript(this, script.id)?.let {
            script = it
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        AppUi.attach(this)
    }

    override fun onStop() {
        AppUi.detach(this)
        super.onStop()
    }

    private fun render() {
        adapter.submitList(script.actions.toList())
        val empty = script.actions.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.actionList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun save() {
        script.name = binding.nameInput.text?.toString()?.trim()
            .orEmpty().ifBlank { script.name }
        script.repeatCount = binding.repeatInput.text?.toString()?.toIntOrNull()
            ?.coerceAtLeast(0) ?: 1
        Repository.upsertScript(this, script)
        Toast.makeText(this, R.string.script_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startRecording() {
        if (!AccessibilityUtils.isEnabled(this)) {
            Toast.makeText(this, R.string.enable_accessibility_first, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            if (!AccessibilityUtils.awaitConnected(this@ScriptEditorActivity)) {
                Toast.makeText(
                    this@ScriptEditorActivity,
                    R.string.accessibility_not_ready,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val service = ClickAccessibilityService.instance ?: return@launch
            val error = service.startRecorder(script.id, replace = false)
            if (error == null) {
                moveTaskToBack(true)
            } else {
                Toast.makeText(
                    this@ScriptEditorActivity,
                    getString(R.string.record_start_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun play() {
        if (!AccessibilityUtils.isEnabled(this)) {
            Toast.makeText(this, R.string.enable_accessibility_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (script.actions.isEmpty()) {
            Toast.makeText(this, R.string.script_empty_cannot_play, Toast.LENGTH_SHORT).show()
            return
        }
        if (ClickAccessibilityService.instance?.isRunning == true) {
            Toast.makeText(this, R.string.script_running, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            if (!AccessibilityUtils.awaitConnected(this@ScriptEditorActivity)) {
                Toast.makeText(
                    this@ScriptEditorActivity,
                    R.string.accessibility_not_ready,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val started = PlaybackService.start(this@ScriptEditorActivity, script.id)
            if (started) {
                Toast.makeText(
                    this@ScriptEditorActivity,
                    getString(R.string.playing, script.name),
                    Toast.LENGTH_SHORT
                ).show()
                // 同主界面：退到后台，避免脚本点击落到本应用自己的控件上
                moveTaskToBack(true)
            } else {
                Toast.makeText(
                    this@ScriptEditorActivity,
                    R.string.playback_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun editDelay(position: Int, action: Action) {
        if (position == RecyclerView.NO_POSITION) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(action.delayMs.toString())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delay_dialog_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val delay = input.text.toString().toLongOrNull()?.coerceAtLeast(0L) ?: return@setPositiveButton
                script.actions[position] = action.copy(delayMs = delay)
                Repository.upsertScript(this, script)
                render()
            }
            .show()
    }

    private fun deleteAction(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        // 正在播放时修改动作集会让播放用的是旧快照，先停掉再改
        if (ClickAccessibilityService.instance?.isRunning == true) {
            PlaybackService.stop(this)
        }
        script.actions.removeAt(position)
        Repository.upsertScript(this, script)
        render()
    }
}
