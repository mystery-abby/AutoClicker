package com.qian.autoclicker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.qian.autoclicker.R
import com.qian.autoclicker.data.Repository
import com.qian.autoclicker.data.Schedule
import com.qian.autoclicker.data.Script
import com.qian.autoclicker.databinding.ActivityMainBinding
import com.qian.autoclicker.schedule.Scheduler
import com.qian.autoclicker.service.ClickAccessibilityService
import com.qian.autoclicker.service.KeepAliveService
import com.qian.autoclicker.service.PlaybackService
import com.qian.autoclicker.util.AccessibilityUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scriptAdapter: ScriptAdapter
    private lateinit var scheduleAdapter: ScheduleAdapter
    private var currentTab = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scriptAdapter = ScriptAdapter(
            onPlay = ::playScript,
            onRecord = ::startRecording,
            onEdit = { script -> openEditor(script.id) },
            onSchedule = { script -> addScheduleFor(script.id) },
            onDelete = ::confirmDeleteScript
        )
        scheduleAdapter = ScheduleAdapter(
            scriptName = { id -> Repository.findScript(this, id)?.name ?: "(已删除)" },
            onToggle = ::toggleSchedule,
            onEdit = ::editSchedule,
            onDelete = ::deleteSchedule
        )

        binding.scriptList.layoutManager = LinearLayoutManager(this)
        binding.scriptList.adapter = scriptAdapter
        binding.scheduleList.layoutManager = LinearLayoutManager(this)
        binding.scheduleList.adapter = scheduleAdapter

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.addFab.setOnClickListener {
            if (currentTab == 0) createScript() else addScheduleFor(null)
        }
        binding.openSettingsButton.setOnClickListener { openAccessibilitySettings() }
        binding.batteryButton.setOnClickListener { showBatteryGuide() }
        // 长按标题查看无障碍诊断信息
        binding.statusText.setOnLongClickListener {
            showDiagnostics()
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Scheduler.scheduleAll(this)
    }

    override fun onResume() {
        super.onResume()
        reload()
        syncKeepAlive()
    }

    /** 无障碍开启时启用守护前台服务，降低被系统回收后"自动关闭"的概率 */
    private fun syncKeepAlive() {
        if (AccessibilityUtils.isEnabled(this)) {
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
    }

    private fun isAccessibilityEnabled(): Boolean = AccessibilityUtils.isEnabled(this)

    private fun showBatteryGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(R.string.battery_dialog_message)
            .setPositiveButton(R.string.battery_ignore) { _, _ ->
                AccessibilityUtils.requestIgnoreBatteryOptimizations(this)
            }
            .setNeutralButton(R.string.battery_app_details) { _, _ ->
                AccessibilityUtils.openAppDetails(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDiagnostics() {
        val text = AccessibilityUtils.diagnose(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnose)
            .setMessage(text)
            .setPositiveButton(R.string.save) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("accessibility", text)
                )
                toast(getString(R.string.diagnose_copied))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun render() {
        val enabled = isAccessibilityEnabled()
        binding.permissionCard.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.statusText.text =
            getString(if (enabled) R.string.accessibility_on else R.string.accessibility_off_title)

        // 无障碍已开启、但还没加入电池白名单时，提示用户去设置，否则容易被系统杀掉
        val needBatteryGuide = enabled && !AccessibilityUtils.isIgnoringBatteryOptimizations(this)
        binding.batteryCard.visibility = if (needBatteryGuide) View.VISIBLE else View.GONE

        val scripts = Repository.loadScripts(this)
        val schedules = Repository.loadSchedules(this)
        val showScripts = currentTab == 0
        binding.scriptList.visibility = if (showScripts) View.VISIBLE else View.GONE
        binding.scheduleList.visibility = if (showScripts) View.GONE else View.VISIBLE
        scriptAdapter.submitList(scripts.toList())
        scheduleAdapter.submitList(schedules.toList())

        binding.addFab.setText(if (showScripts) R.string.new_script else R.string.add_schedule)
        val isEmpty = if (showScripts) scripts.isEmpty() else schedules.isEmpty()
        binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.emptyText.setText(if (showScripts) R.string.empty_scripts else R.string.empty_schedules)
        if (isEmpty) {
            binding.emptyText.bringToFront()
        }
    }

    private fun reload() {
        render()
        if (currentTab == 1) Scheduler.scheduleAll(this)
    }

    private fun createScript() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.script_name)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_script)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                    .ifBlank { "脚本 ${Repository.loadScripts(this).size + 1}" }
                val script = Script(name = name)
                Repository.upsertScript(this, script)
                openEditor(script.id)
            }
            .show()
    }

    private fun openEditor(scriptId: String) {
        startActivity(ScriptEditorActivity.intent(this, scriptId))
    }

    private fun startRecording(script: Script) {
        if (!AccessibilityUtils.isEnabled(this)) {
            toast(getString(R.string.enable_accessibility_first))
            openAccessibilitySettings()
            return
        }
        lifecycleScope.launch {
            // 服务已启用但可能还没绑定完成，等一小会儿
            if (!AccessibilityUtils.awaitConnected(this@MainActivity)) {
                toast(getString(R.string.accessibility_not_ready))
                return@launch
            }
            val service = ClickAccessibilityService.instance ?: return@launch
            // 追加录制，避免误覆盖已有动作；要重录可先在编辑器里删除
            val error = service.startRecorder(script.id, replace = false)
            if (error == null) {
                moveTaskToBack(true)
            } else {
                toast(getString(R.string.record_start_failed, error))
            }
        }
    }

    private fun playScript(script: Script) {
        if (!AccessibilityUtils.isEnabled(this)) {
            toast(getString(R.string.enable_accessibility_first))
            openAccessibilitySettings()
            return
        }
        if (script.actions.isEmpty()) {
            toast(getString(R.string.script_empty_cannot_play))
            return
        }
        if (ClickAccessibilityService.instance?.isRunning == true) {
            toast(getString(R.string.script_running))
            return
        }
        lifecycleScope.launch {
            if (!AccessibilityUtils.awaitConnected(this@MainActivity)) {
                toast(getString(R.string.accessibility_not_ready))
                return@launch
            }
            val started = PlaybackService.start(this@MainActivity, script.id)
            if (started) {
                toast(getString(R.string.playing, script.name))
                // 必须退到后台：否则脚本会直接回放在本应用的脚本列表上，
                // 点击坐标落到删除/运行等按钮上，造成"点运行却弹出别的脚本的删除框"
                moveTaskToBack(true)
            } else {
                toast(getString(R.string.playback_failed))
            }
        }
    }

    /** 防止脚本连续点击导致删除确认框堆叠 */
    private var deleteDialogShowing = false

    private fun confirmDeleteScript(script: Script) {
        if (deleteDialogShowing) return
        deleteDialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, script.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                Repository.deleteScript(this, script.id)
                reload()
            }
            .setOnDismissListener { deleteDialogShowing = false }
            .show()
    }

    private fun addScheduleFor(scriptId: String?) {
        val scripts = Repository.loadScripts(this)
        if (scripts.isEmpty()) {
            toast(getString(R.string.empty_scripts))
            return
        }
        ScheduleDialog.show(this, scripts, scriptId) { schedule ->
            val list = Repository.loadSchedules(this)
            val index = list.indexOfFirst { it.id == schedule.id }
            if (index >= 0) list[index] = schedule else list.add(schedule)
            Repository.saveSchedules(this, list)
            Scheduler.schedule(this, schedule)
            toast(getString(R.string.schedule_saved))
            binding.tabLayout.getTabAt(1)?.select()
            // 已经在"定时任务"页时 select() 只触发 onTabReselected，不会走 render()，
            // 列表看起来像没刷新。这里显式重载一次。
            reload()
        }
    }

    private fun editSchedule(schedule: Schedule) {
        val scripts = Repository.loadScripts(this)
        if (scripts.isEmpty()) {
            toast(getString(R.string.empty_scripts))
            return
        }
        ScheduleDialog.show(this, scripts, schedule.scriptId, existing = schedule) { updated ->
            val list = Repository.loadSchedules(this)
            val index = list.indexOfFirst { it.id == updated.id }
            if (index >= 0) list[index] = updated else list.add(updated)
            Repository.saveSchedules(this, list)
            // 先取消旧闹钟再按新配置重排，否则改过的触发时间可能不生效
            Scheduler.cancel(this, schedule)
            Scheduler.schedule(this, updated)
            toast(getString(R.string.schedule_saved))
            reload()
        }
    }

    private fun toggleSchedule(schedule: Schedule, enabled: Boolean) {
        val list = Repository.loadSchedules(this)
        val target = list.firstOrNull { it.id == schedule.id } ?: return
        target.enabled = enabled
        Repository.saveSchedules(this, list)
        if (enabled) Scheduler.schedule(this, target) else Scheduler.cancel(this, target)
        reload()
    }

    private fun deleteSchedule(schedule: Schedule) {
        Scheduler.cancel(this, schedule)
        val list = Repository.loadSchedules(this)
        list.removeAll { it.id == schedule.id }
        Repository.saveSchedules(this, list)
        reload()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

}
