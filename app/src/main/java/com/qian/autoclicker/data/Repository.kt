package com.qian.autoclicker.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * 简单的 JSON 文件持久化，脚本与定时任务分开存储。
 */
object Repository {

    private const val PREFS = "autoclicker"
    private const val KEY_SCRIPTS = "scripts"
    private const val KEY_SCHEDULES = "schedules"
    private const val KEY_HISTORY = "schedule_history"

    /** 每个定时任务最多保留的执行记录条数 */
    const val MAX_HISTORY = 10

    private val gson: Gson = GsonBuilder().serializeNulls().create()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val MAX_DELAY_MS = 10_000L

    fun loadScripts(ctx: Context): MutableList<Script> {
        val raw = prefs(ctx).getString(KEY_SCRIPTS, null) ?: return mutableListOf()
        val scripts = runCatching {
            val type = object : TypeToken<MutableList<Script>>() {}.type
            gson.fromJson<MutableList<Script>>(raw, type)
        }.getOrNull() ?: return mutableListOf()
        // 旧版本录制时把"打开浮层后犹豫的时间"算进了第一个动作的间隔，
        // 导致回放先干等几十秒。读取时修一次并写回，老脚本也能立刻正常。
        if (sanitize(scripts)) saveScripts(ctx, scripts)
        return scripts
    }

    private fun sanitize(scripts: MutableList<Script>): Boolean {
        var changed = false
        scripts.forEach { script ->
            script.actions.forEachIndexed { index, action ->
                val fixed = when {
                    index == 0 && action.delayMs != 0L -> 0L
                    action.delayMs > MAX_DELAY_MS -> MAX_DELAY_MS
                    else -> action.delayMs
                }
                if (fixed != action.delayMs) {
                    script.actions[index] = action.copy(delayMs = fixed)
                    changed = true
                }
            }
        }
        return changed
    }

    fun saveScripts(ctx: Context, scripts: List<Script>) {
        prefs(ctx).edit().putString(KEY_SCRIPTS, gson.toJson(scripts)).apply()
    }

    fun loadSchedules(ctx: Context): MutableList<Schedule> {
        val raw = prefs(ctx).getString(KEY_SCHEDULES, null) ?: return mutableListOf()
        val list = runCatching {
            val type = object : TypeToken<MutableList<Schedule>>() {}.type
            gson.fromJson<MutableList<Schedule>>(raw, type)
        }.getOrNull() ?: return mutableListOf()
        // 旧数据没有 randomDelayMaxMs 字段，反序列化为 null；归一化为默认 15 分钟并写回，
        // 避免每次读取都要到处判空。
        var changed = false
        list.forEach { schedule ->
            if (schedule.randomDelayMaxMs == null) {
                schedule.randomDelayMaxMs = Schedule.DEFAULT_RANDOM_DELAY_MS
                changed = true
            }
        }
        if (changed) saveSchedules(ctx, list)
        return list
    }

    fun saveSchedules(ctx: Context, schedules: List<Schedule>) {
        prefs(ctx).edit().putString(KEY_SCHEDULES, gson.toJson(schedules)).apply()
    }

    fun findScript(ctx: Context, id: String): Script? =
        loadScripts(ctx).firstOrNull { it.id == id }

    fun upsertScript(ctx: Context, script: Script) {
        val list = loadScripts(ctx)
        val idx = list.indexOfFirst { it.id == script.id }
        if (idx >= 0) list[idx] = script else list.add(script)
        saveScripts(ctx, list)
    }

    fun deleteScript(ctx: Context, id: String) {
        val list = loadScripts(ctx)
        list.removeAll { it.id == id }
        saveScripts(ctx, list)
        val schedules = loadSchedules(ctx)
        schedules.removeAll { it.scriptId == id }
        saveSchedules(ctx, schedules)
    }

    // ---------- 定时任务执行记录 ----------

    /** 结构：scheduleId -> 记录列表（按时间从旧到新） */
    private fun loadHistoryMap(ctx: Context): MutableMap<String, MutableList<ExecRecord>> {
        val raw = prefs(ctx).getString(KEY_HISTORY, null) ?: return mutableMapOf()
        return runCatching {
            val type = object : TypeToken<MutableMap<String, MutableList<ExecRecord>>>() {}.type
            gson.fromJson<MutableMap<String, MutableList<ExecRecord>>>(raw, type)
        }.getOrNull() ?: mutableMapOf()
    }

    /** 读取某任务的执行记录，最新的在前 */
    fun loadHistory(ctx: Context, scheduleId: String): List<ExecRecord> =
        loadHistoryMap(ctx)[scheduleId].orEmpty().asReversed().toList()

    /** 追加一条执行记录；超过 [MAX_HISTORY] 条时丢弃最早的 */
    fun appendHistory(ctx: Context, scheduleId: String, record: ExecRecord) {
        val map = loadHistoryMap(ctx)
        val list = map.getOrPut(scheduleId) { mutableListOf() }
        list.add(record)
        while (list.size > MAX_HISTORY) list.removeAt(0)
        prefs(ctx).edit().putString(KEY_HISTORY, gson.toJson(map)).apply()
    }

    /** 删除某任务的记录（删除任务时调用） */
    fun clearHistory(ctx: Context, scheduleId: String) {
        val map = loadHistoryMap(ctx)
        if (map.remove(scheduleId) != null) {
            prefs(ctx).edit().putString(KEY_HISTORY, gson.toJson(map)).apply()
        }
    }
}
