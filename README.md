# 自动点击器 (AutoClicker)

Android 手势自动化工具：**录制脚本 → 回放 → 定时执行**。使用无障碍服务派发手势，无需 Root。

## 功能

- **脚本录制**：屏幕上直接操作，点击 / 长按 / 滑动自动记录成动作序列（全屏浮层 + 手势回放，录制过程中目标界面照常响应）
- **脚本编辑**：动作列表可逐条删除、修改每个动作的间隔时间；可设置循环次数（0 = 无限循环）
- **立即回放**：从列表或编辑器一键运行，前台服务 + 通知栏实时显示进度
- **悬浮停止按钮**：运行期间屏幕右侧浮出红色圆形按钮，显示 `当前/总数` 进度，点一下即停；可拖到任意位置并**记住上次位置**；**长按可临时隐藏 3 秒**，让出被挡住的点击区域
- **定时执行**：支持三种模式
  - 仅一次（指定日期时刻）
  - 每天（指定时刻）
  - 工作日（每周一至周五的同一时刻，自动跳过周六日）
  - 按间隔（每 N 分钟）
- **开机自恢复**：重启或应用更新后自动重新登记全部定时任务
- **本地存储**：脚本与定时任务以 JSON 保存在 SharedPreferences，无网络权限

## 环境

- 编译：JDK 17+，Android SDK Platform 34，Build-Tools 34.0.0
- 运行：Android 8.0 (API 26) 及以上
- 已在 `local.properties` 中配置 `sdk.dir`

## 构建

```bash
cd E:/my-project/AutoClicker

# 调试包
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk

# 正式包（使用仓库内 release.keystore 签名）
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

签名信息见 `keystore.properties`，密钥库为项目根目录的 `release.keystore`
（alias `autoclicker`，口令 `autoclicker123`）。**公开发布前请替换为你自己的密钥库。**

## 首次使用

1. 安装 APK 后打开应用，首页顶部会出现黄色提示条
2. 点「去开启」，在 **设置 → 无障碍 → 已下载的服务** 中打开「自动点击器」
3. 回到应用，提示条消失即表示就绪
4. Android 13+ 首次运行会请求通知权限（用于显示运行状态）
5. Android 12+ 如需准点执行定时任务，请允许「闹钟和提醒」权限（定时对话框内会提示并可跳转）

## 使用流程

**录制**

首页 → 新建脚本（或已有脚本点「录制」）→ 应用退到后台并弹出录制浮层
→ 在任意 App 上操作 → 点浮层上的「完成」→ 自动保存并回到编辑器

录制浮层顶部的控制条可拖动，避免遮挡要点击的区域。长按超过系统长按阈值记为长按动作，
有明显位移记为滑动，其余记为点击。每个动作之间的实际间隔会被记录下来。

**编辑**

编辑器里可修改脚本名、循环次数；每个动作右侧的「间隔 xxx ms」可单独调整，
删除按钮移除单个动作。间隔指的是**该动作执行完后等待多久再执行下一个**。

**立即运行**

列表里点 ▶ 或编辑器里点「运行」。运行期间通知栏显示 `第 n / 总数`，点通知里的「停止」即可中断。

**定时执行**

列表里点「添加定时」或切到「定时任务」标签页 → ➕：
选择脚本、选择重复方式、设置时间或间隔 → 保存。
定时任务列表中可用开关随时启停，删除任务不会删除脚本。

## 实现要点

| 关注点 | 方案 |
| --- | --- |
| 手势派发 | `AccessibilityService.dispatchGesture()`（`GestureDescription`） |
| 录制浮层 | `TYPE_ACCESSIBILITY_OVERLAY` 悬浮窗（无需「显示在其他应用上层」权限） |
| 录制时界面仍可操作 | 浮层拦截手势 → 生成 `Action` → 立即用 `dispatchGesture` 回放给下层 App |
| 播放保活 | 前台服务（`specialUse` 类型）+ 低优先级常驻通知 |
| 精确定时 | `AlarmManager.setExactAndAllowWhileIdle()`，无权限时降级为 `setAndAllowWhileIdle()` |
| 重复任务 | 每次触发后重新排下一次（DAILY 按当天毫秒偏移重算，INTERVAL 按间隔顺延） |
| 开机恢复 | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 广播中重新登记全部闹钟 |
| 数据存储 | SharedPreferences + Gson，脚本与定时任务分别序列化 |

主要代码位置：

```
app/src/main/java/com/qian/autoclicker/
├── data/          Models.kt（Action/Script/Schedule）· Repository.kt（JSON 持久化）
├── service/       ClickAccessibilityService.kt（手势派发与播放）
│                  RecorderOverlay.kt（录制浮层）
│                  PlaybackService.kt（前台服务与通知）
├── schedule/      Scheduler.kt（AlarmManager 调度）· AlarmReceiver.kt · BootReceiver.kt
└── ui/            MainActivity.kt · ScriptEditorActivity.kt · 各 Adapter 与对话框
```

## 已知限制

- **坐标是绝对像素**：脚本在分辨率或屏幕方向不同的设备上回放会偏移。录制与回放请使用同一台设备、同一屏幕方向。
- **部分系统界面无法操作**：出于安全限制，无障碍手势无法点击系统权限弹窗、锁屏密码键盘等界面。
- **国产 ROM 保活**：小米 / 华为 / OPPO / vivo 等系统可能清理后台进程，需在系统设置里为本应用关闭电池优化、允许自启动，定时任务才可靠。
- **录制时的手势延迟**：录制的每个手势都要经过浮层转发再回放，实际操作会有轻微延迟，属正常现象。
- **间隔上限**：单个动作间隔建议不超过 60 秒；手势本身持续时长受 `GestureDescription` 限制（最长约 60 秒）。
