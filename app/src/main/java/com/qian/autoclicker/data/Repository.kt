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
        return runCatching {
            val type = object : TypeToken<MutableList<Schedule>>() {}.type
            gson.fromJson<MutableList<Schedule>>(raw, type)
        }.getOrNull() ?: mutableListOf()
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
}
