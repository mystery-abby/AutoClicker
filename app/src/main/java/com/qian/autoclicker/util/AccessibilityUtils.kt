package com.qian.autoclicker.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.qian.autoclicker.service.ClickAccessibilityService
import kotlinx.coroutines.delay

/**
 * 无障碍服务状态判断。
 *
 * 只看 [ClickAccessibilityService.instance] 是不可靠的：
 * 1. 服务已启用但进程刚重启时，静态实例要等系统回调 onServiceConnected，存在几百毫秒空窗；
 * 2. 部分 ROM 在开启后不会立即启动服务进程，必须由系统事件或显式触发才会绑定。
 *
 * 因此这里以系统记录为准，实例只作为"已连接"的加速判断。
 */
object AccessibilityUtils {

    fun isConnected(): Boolean = ClickAccessibilityService.instance != null

    private fun target(ctx: Context) =
        ComponentName(ctx.packageName, ClickAccessibilityService::class.java.name)

    /** 系统是否认为该无障碍服务已启用（不要求已经连接上） */
    fun isEnabled(ctx: Context): Boolean {
        if (isConnected()) return true

        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null && am.isEnabled) {
            val expected = target(ctx)
            val running = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            if (running.any { componentOf(it) == expected }) return true
        }
        return isEnabledInSecureSettings(ctx)
    }

    private fun componentOf(info: AccessibilityServiceInfo): ComponentName? {
        val si = info.resolveInfo?.serviceInfo ?: return null
        return ComponentName(si.packageName, si.name)
    }

    /**
     * 回退检查：部分 ROM 把组件写成短类名（pkg/.service.Xxx），
     * 直接比较字符串会误判，必须用 ComponentName.unflattenFromString 解析。
     */
    private fun isEnabledInSecureSettings(ctx: Context): Boolean {
        val on = Settings.Secure.getInt(
            ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        if (on != 1) return false
        val raw = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = target(ctx)
        return raw.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    /**
     * 等待系统完成服务绑定。若服务始终没被拉起（部分 ROM 懒启动），
     * 会重新查询一次 AccessibilityManager，促使系统绑定已启用的服务。
     */
    suspend fun awaitConnected(
        ctx: Context,
        timeoutMs: Long = 3000L,
        stepMs: Long = 100L
    ): Boolean {
        if (isConnected()) return true
        var waited = 0L
        var nudged = false
        while (waited < timeoutMs) {
            delay(stepMs)
            waited += stepMs
            if (isConnected()) return true
            if (!nudged && waited >= 800L) {
                nudged = true
                nudgeBind(ctx)
            }
        }
        return isConnected()
    }

    private fun nudgeBind(ctx: Context) {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return
        runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }
    }

    /**
     * 诊断信息：把系统里各个来源的真实状态全部打印出来，用于定位"系统显示已开启但应用认为未开启"。
     * 正常情况下不需要用到，出问题时让用户把这段内容发回来即可。
     */
    fun diagnose(ctx: Context): String {
        val expected = target(ctx)
        val sb = StringBuilder()

        sb.appendLine("应用版本：" + appVersion(ctx))
        sb.appendLine("期望组件：${expected.flattenToString()}")
        sb.appendLine()

        sb.appendLine("① 服务实例已连接：" + isConnected())

        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        sb.appendLine("② AccessibilityManager.isEnabled：" + (am?.isEnabled ?: "null"))
        val list = runCatching {
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrNull()
        sb.appendLine("③ 已启用服务列表（${list?.size ?: 0} 项）：")
        if (list.isNullOrEmpty()) {
            sb.appendLine("   （空）")
        } else {
            list.forEach { info ->
                val c = componentOf(info)
                val hit = if (c == expected) "  ← 本应用" else ""
                sb.appendLine("   ${c?.flattenToString()}$hit")
            }
        }

        val on = Settings.Secure.getInt(
            ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, -1
        )
        sb.appendLine()
        sb.appendLine("④ Settings.ACCESSIBILITY_ENABLED：" + on)
        val raw = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        sb.appendLine("⑤ Settings.ENABLED_ACCESSIBILITY_SERVICES：")
        if (raw.isNullOrEmpty()) {
            sb.appendLine("   （空）")
        } else {
            raw.split(':').forEach { entry ->
                val c = ComponentName.unflattenFromString(entry)
                val hit = if (c == expected) "  ← 本应用" else ""
                sb.appendLine("   $entry$hit")
            }
        }

        sb.appendLine()
        sb.appendLine("⑥ 最终判定 isEnabled：" + isEnabled(ctx))
        return sb.toString()
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("未知")

    /** 是否已忽略电池优化（白色名单的关键一项） */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
            .getOrDefault(false)
    }

    /**
     * 打开系统的"忽略电池优化"授权页。
     * 各 ROM 该页面的入口不一致，依次尝试多个 Action，全部失败再退回应用详情页。
     */
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        val pkg = ctx.packageName
        val candidates = listOf(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:$pkg")),
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$pkg"))
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { ctx.startActivity(intent) }.isSuccess
            if (ok) return
        }
    }

    /** 打开本应用详情页，用户可在此找到"自启动 / 后台运行"等 ROM 专属开关 */
    fun openAppDetails(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
