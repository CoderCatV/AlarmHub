# 真机点检清单（小米 14 / HyperOS）

> 用途：把 [M5](M5-STATUS.md) §5 的「待真机验证」和 [M7](DEVELOPMENT-PLAN.md) §M7.2 的 ROM 相关项，
> 变成一份**照着做、照着判**的清单。每一条都写了「命令 / 通过长什么样 / 失败长什么样 / 记什么」。
>
> 判读原则（血泪换来的，见 [STATUS](STATUS.md) §3.4）：**不要解析 adb 或 logcat 的文本做判断。**
> 状态读应用自己写的文件（`run-as`），日志读**时间窗口**（`logcat -T <设备epoch>.000`），
> 模式取自日志原文并注意词序。凡是「没匹配到」都先怀疑模式，再怀疑产品。
>
> **两条补充（真机首次试用后加的）**：
>
> 1. **靠手势才存在的交互，必须用手势验收。** 点按（`input tap`）证不了拖动的行为。
>    时间滚轮就是例子：用点击验它是"能选中、数据也对"，而用 `input swipe` 一拖就发现它在
>    24 小时制下过不了 12 点（[STATUS](STATUS.md) §1.1 #14）。
> 2. **"输入空间里我一次都没碰过的那一段"才是重点。** 同一个函数跑了多少次用例不重要，
>    重要的是取值范围有没有被走遍 —— 上面那个 bug 只在下标 ≥ 12 时出现，而历次验证选的小时从没超过 11。

---

## ⚠️ 先读：这三条命令在**真机（HyperOS）上不工作**

2026-10-02 在小米 14 / HyperOS 3（`23127PN0CC`、`OS3.0`、`V816`）上实测：

| 本想用来做什么 | 真机上的结果 | 替代做法 |
|---|---|---|
| `adb shell am broadcast … DebugReceiver`（本文档 §A 的所有命令） | **广播被丢弃**，结果文件不生成。日志里没有任何记录 | 调试接收器要求**签名级** `com.alarmhub.app.permission.DEBUG`，而 `adb shell` 不持有它。（模拟器上能跑是因为那台 adb 是 root）。**真机上改用手机屏幕：体检页自己会把状态和兜底提示显示出来** |
| `adb shell run-as com.alarmhub.app sqlite3 …` 查库 | `run-as: exec failed for sqlite3: Permission denied` | 拉文件：`adb exec-out run-as com.alarmhub.app cat databases/alarmhub.db > a.db`（**`-wal` 也要一起拉**，否则看不到最新提交），在电脑上用 sqlite 打开 |
| `adb shell input tap / input swipe` 驱动手机界面 | `SecurityException: … requires the INJECT_EVENTS permission` | **手机上只能用手点**。模拟器不受影响（`input swipe` 在模拟器上照常可用） |

**所以真机验收的正确姿势是**：能看屏幕的都看屏幕（体检页、主界面、响铃页），
需要日志/数据库的用上面的替代命令，界面操作由你用手指完成。
本文档 §A.2 那套「一键跑七项跳转」的命令**只适用于模拟器**；真机请按 §A.2-真机 走。

### 一个在真机上很有用、且不需要应用配合的技巧

MIUI / HyperOS 把「自启动 / 后台弹出界面 / 锁屏显示」实现成 **AppOps 操作**。真机上可以直接读它们的取值：

```powershell
# 某一台应用的全部 op（含 MIUI 私有的，显示成 MIUIOP(<编号>)）
adb shell "cmd appops get com.alarmhub.app"
```

HyperOS 3 上这三个开关的编号（**靠实验定下来的**，见 [M5-STATUS](M5-STATUS.md) §5.3）：

| 编号 | 开关 | 关 → 开 |
|---|---|---|
| `MIUIOP(10008)` | 自启动 | ignore → allow |
| `MIUIOP(10021)` | 后台弹出界面 | ignore → allow |
| `MIUIOP(10020)` | 锁屏显示 | ignore → allow |

**怎么用它做判读**：在手机上拨一下开关，再跑一次这条命令看编号有没有跟着变。
`allow` = 已授权（已用「禁止自启动」列表里的四个应用核对过，它们的默认是 `ignore`，所以 `allow` 不是误报）。

**怎么用它定位「探针为什么读不到」**：如果体检页某一行显示「无法自动检测」，
先跑这条命令看这个 ROM 用的是**编号**还是**字符串名字**——这决定了要改的是探针的哪一半。

---

## 0. 一次性准备

| # | 做什么 | 命令 / 操作 |
|---|---|---|
| 0.1 | 手机开 USB 调试，插上电脑，允许这台电脑调试 | 手机上「开发者选项 → USB 调试」 |
| 0.2 | 确认连接（**判读**：型号必须是 `xiaomi`，不是 `emulator-5554`） | `adb devices -l` |
| 0.3 | 装本次交付的 debug APK | `adb install -r android\app\build\outputs\apk\debug\app-debug.apk` |
| 0.4 | 清一次日志，便于后面按时间窗口读 | `adb logcat -c` |
| 0.5 | 启动应用 | `adb shell am start -n com.alarmhub.app/.MainActivity` |
| 0.6 | 放行通知（否则响铃时看不到通知，也会影响 AC-11 的判读） | 体检页点「通知」→ 允许 |

> 0.3 的 APK 是 **debug** 构建。debug 构建才有 `DebugReceiver` 这条诊断通道；
> release 构建里它**完全不存在**（[M3-STATUS](M3-STATUS.md) §4 已验证）。

**记录模板**（每条点检结论都按这个回传，比截图好核对）：

```
项:        （AC 编号 + 名称）
设备:      （adb shell getprop ro.product.model / ro.build.version.incremental）
命令:      （原样贴）
原始输出:  （原样贴，不要摘要）
结论:      通过 / 未通过 / 无法验证
备注:      （不符预期的地方，以及当时的截图文件名）
```

---

## A. M5 · 权限体检的检测与跳转（AC-14）

### A.1 七项检测是否准确

```powershell
# 先看一眼设备身份与七项判定（一次拿全）
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND `
    -n com.alarmhub.app/.debug.DebugReceiver --es cmd permissions
adb shell run-as com.alarmhub.app cat files/debug-result.txt
```

**通过的样子**（`device` 行必须是 `miui=true`，否则后面 MIUI 相关的判读全都不适用）：

```
device manufacturer=Xiaomi brand=Xiaomi model=… sdk=… miui=true
key=exactAlarm granted=true applicable=true
key=notifications granted=? applicable=true
key=fullScreenIntent granted=true applicable=true
key=autostart granted=? applicable=true          ← 关键：非 MIUI 才是 false
key=batteryUnrestricted granted=? applicable=true
key=backgroundPopup granted=? applicable=true
key=lockScreenDisplay granted=? applicable=true
```

**要点**：在小米上 `autostart` / `backgroundPopup` / `lockScreenDisplay` 三项的 `applicable` 必须是 **true**。
如果是 `false`，说明 `PermissionInspector.isMiui()` 没认出来（品牌串不是 xiaomi/redmi/poco），记下来。

**`granted` 与手机设置对照**：把三项的实际开关状态与 `granted` 一一对照。

| 情况 | 结论 |
|---|---|
| 开关开着、`granted=true` | ✅ 探针有效 |
| 开关关着、`granted=false` | ✅ 探针有效 |
| **开关开着但 `granted=false`**，且描述里有「无法自动检测」 | ⚠️ 这个 HyperOS 版本不认我们的 AppOps 名字 —— **这是最需要回报的一种结果** |
| 开关关着但 `granted=true` | ❌ 探针在说谎，必须修 |

> 「无法自动检测」是什么：MIUI 的这三项是私有 AppOps，名字没有公开文档。探针会依次试几个拼写，
> 一个都不被系统认识时，就报「未开启」并在描述里写明无法自动检测（[M5-STATUS](M5-STATUS.md) §4.3）。
> 请把 `files/debug-result.txt` 的完整内容回报，另外再补一条：**每一项在系统设置里到底是开是关**。

### A.2 逐项「一键跳转」是否命中（这是 M5 唯一留空的一项）

> **真机请用 A.2-真机（用手指点）**；下面这套命令在 HyperOS 上会被签名权限挡住（见文首警告），
> 它只在模拟器上可用。

对七项逐条跑下面这条命令。它会**真的拉起**系统页面，并在结果文件里报告每条候选 Intent 能否被解析。

```powershell
foreach ($k in 'exactAlarm','notifications','fullScreenIntent','autostart','batteryUnrestricted','backgroundPopup','lockScreenDisplay') {
    Write-Host "===== $k ====="
    adb shell am broadcast -a com.alarmhub.app.debug.COMMAND `
        -n com.alarmhub.app/.debug.DebugReceiver --es cmd permissionjump --es key $k
    Start-Sleep -Seconds 2
    adb shell run-as com.alarmhub.app cat files/debug-result.txt
    adb shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 1
    adb shell input keyevent 4
    Start-Sleep -Seconds 1
}
```

### A.2-真机 用手指点（已验证可行，2026-10-02 小米 14 / HyperOS 3）

打开 **设置 → 权限体检**，逐行点「去设置 ›」，然后按下表核对。
**判据是"落在哪个页面"，不是"有没有报错"** —— 跳转失败时应用会在体检页底部显示一条黄色提示。

**已经在真机上验过的结果（可以直接对照）**：

| 行 | HyperOS 3 上应该出现 | 结论 |
|---|---|---|
| **自启动** | 直接打开「**自启动管理**」页，闹钟坞在「允许 N 个应用自启动」列表里，右侧自带开关 | ✅ 一步直达 |
| **后台弹出界面** | 打开标题为「**闹钟坞**」的页面，里面只有一个「**其他权限**」入口 | ✅ 到位；**还要再点一次「其他权限」**才能看到开关（那一页第三方打不开，是 MIUI 的设计） |
| **锁屏显示** | 同上，同一个页面 | ✅ 同上 |
| 精确闹钟 / 通知 / 全屏通知 | 系统设置里的对应页面 | 公开接口，模拟器已验 |
| 电池无限制 | 优先弹「**是否允许闹钟坞忽略电池优化**」对话框 | 直连对话框优先，列表页兜底 |

点进「其他权限」后，页面上应该有这些条目（真机实测到的）：

```
设置相关 / 获取设备动作与方向 / 获取应用列表 / 媒体音量控制 / 桌面快捷方式
锁屏显示 / 后台弹出界面 / 显示悬浮窗 / 链式启动管理
```

**同时请看一眼体检页上这三行是不是"可以点"的**（显示 ✓ 或 ✕，不是灰色「不适用」）：
「自启动」「后台弹出界面」「锁屏显示」。若显示「不适用」，说明品牌识别没生效，把那一屏截给我。

**判读表**（`opened=true` 表示命中；落点看 `topResumedActivity`）：

| key | 期望打开的页面 | 通过的样子 |
|---|---|---|
| `exactAlarm` | 本应用的「闹钟和提醒」/精确闹钟页 | `opened=true`，落在 `com.android.settings` 或 `com.miui.securitycenter` |
| `notifications` | 本应用的通知设置页 | 同上 |
| `fullScreenIntent` | 「全屏通知」页（HyperOS 上可能没有独立页，退到通知页也算命中） | `opened=true` |
| `autostart` | **自启动管理页** | `opened=true`，落点是 `com.miui.securitycenter/…autoStart…` |
| `batteryUnrestricted` | 省电策略 / 电池无限制（能直接弹本应用的对话框最好） | `opened=true` |
| `backgroundPopup` | **其他权限 → 后台弹出界面**（或本应用的应用权限管理页） | `opened=true` |
| `lockScreenDisplay` | 本应用的应用权限管理页（锁屏显示在其中） | `opened=true` |

**通过标准**：`autostart` 与 `backgroundPopup` 至少各命中一次真正的 MIUI 页面。
其余项命中系统设置页即可（它们本来就是公开接口，模拟器已验过）。

**失败的样子与含义**：

| 现象 | 含义 | 怎么办 |
|---|---|---|
| `opened=false fallback=true`，落在 App info | 所有候选都没打开，走了 FR-5.3 兜底 | **功能上是对的**（兜底本该如此），但说明 HyperOS 改了组件名。把结果文件里 `resolves=false …` 那几行贴回来，我按实际名字补候选 |
| `resolves=false` 但你在手机上确实能找到那个页面 | 包可见性没生效，或页面在另一个包里 | 贴回 `resolves=false` 的行；同时告诉我页面在「设置 → 哪个入口」 |
| 命令报 `ERROR unknown permission key` | key 拼错了 | 用 A.1 里的七个 `key=` 值 |

**另外请单独做一次「兜底页可用」的人工确认**：在上面某个 `opened=false` 的情况下，
App info 页面上应该能看到「权限」「通知」「电池」这些入口。截图留存。

### A.3 主界面警示条与首次引导（FR-5.4 / FR-5.5）

```powershell
adb shell pm clear com.alarmhub.app          # 清数据 = 回到全新安装状态
adb shell am start -n com.alarmhub.app/.MainActivity
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png .shots\phone-first-run.png
```

**通过的样子**：首次启动会出现底部引导卡片；主界面顶部常驻警示条（精确闹钟 / 通知缺失时）。

```powershell
# 引导的「已处理过」标志必须真落库
adb shell run-as com.alarmhub.app sqlite3 databases/alarmhub.db 'select permission_check_done from settings;'
# → 1（点过「以后再说」或「去体检」之后）
```

再单独确认一次 **AC-6 的前半句**：引导里那段 FR-5.6 文案里，**「任何」两个字应该是加粗的**，不是 `**任何**` 两个星号。

### A.4 建议顺手做的两个小确认

| # | 操作 | 通过的样子 |
|---|---|---|
| A.4.1 | 体检页点某一项的「去设置」→ 用系统返回键回到应用 | 该行状态**自己刷新**（不需要手点「刷新」）。不刷新也不影响正确性，但请回报 |
| A.4.2 | 把某一项在系统里关掉，回到应用 | 警示条重新出现（若关的是精确闹钟或通知） |

---

## B. M7 · 真机要验的项

> 除了 A 节（AC-14），下面这些 AC 也必须在小米 14 上走一遍。可在模拟器完成的部分只作参考。

### B.1 AC-12：应用被系统回收后仍能响

这是 HyperOS 上最要紧的一条，也是模拟器**原理上测不出来**的一条。

```powershell
adb shell input keyevent KEYCODE_HOME
adb shell am kill com.alarmhub.app           # 模拟被系统回收（不是 force-stop！）
adb shell pidof com.alarmhub.app             # 应该没有输出
# 建一个 2 分钟后的闹钟，然后等
adb logcat -c
# …等到点…
adb logcat -d -s "AlarmHub/Receiver:I" "AlarmHub/Audio:I" "AlarmHub/Service:I"
```

| 判读 | 标准 |
|---|---|
| ✅ 通过 | 到点听到声音；日志有 `ring started for alarm=N` 与 `ringing from <uri>`；状态栏出现闹钟通知 |
| ❌ 未通过 | 到点只有通知没有声音，或完全没有反应 |
| 注意 | **不要用「最近任务里划掉」当作这一条**——那是 R1，任何第三方应用都唤不醒，属于已知无解（FR-5.6 已告知）。这一条测的是「被系统回收」。 |

### B.2 AC-9：重启后自动重新注册

> **先读这一条，否则会像我一样得出错误结论：HyperOS 上 `BOOT_COMPLETED` 是延迟投递的 —— 实测约 60 秒
> 应用才被拉起。** 重启后 8 秒去看注册，看到的必然是空的；那不是失败，是还没到。
> （另：**不要**在这一步之前 `am force-stop` 本应用 —— 处于 stopped 的应用收不到任何广播，包括开机。）

```powershell
adb reboot
# ⚠️ 等手机起来、解锁，然后**再等 90 秒** —— 别在 10 秒时就下结论
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n com.alarmhub.app/.debug.DebugReceiver --es cmd registered
adb shell run-as com.alarmhub.app cat files/debug-result.txt
adb shell dumpsys alarm | Select-String "com.alarmhub.app" -Context 0,3
```

| 判读 | 标准 |
|---|---|
| ✅ 通过 | **90 秒内**（实测约 60 秒）`registeredAtMillis` 都非 0 且在未来；`dumpsys alarm` 里能看到本应用的注册，且主触发带 `Alarm clock:` 段 |
| ❌ 未通过 | 90 秒后仍然 `registeredAtMillis=0`，或 `dumpsys` 里一条都没有 |
| 已知平台缺口 | 那 60 秒窗口内到期的闹钟**会漏掉**（平台延迟投递，不是我们的 bug，也修不了）。要复现就在窗口内建一条到点的闹钟 —— 漏掉是**预期** |
| 变体 | HyperOS 上**先别解锁**（重启后直接放着）也看一遍：M3 已声明本应用不是 direct-boot aware，解锁前不恢复是**已知缺口**，不是新 bug（[M3-STATUS](M3-STATUS.md) §3.5） |

### B.3 AC-10 / R7：改系统时间、改时区后重算正确

```powershell
# 记录改动前
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n com.alarmhub.app/.debug.DebugReceiver --es cmd registered
adb shell run-as com.alarmhub.app cat files/debug-result.txt
# 在手机上手动把时间往前拨 1 小时（或改时区）
# 再读一次
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n com.alarmhub.app/.debug.DebugReceiver --es cmd registered
adb shell run-as com.alarmhub.app cat files/debug-result.txt
```

| 判读 | 标准 |
|---|---|
| ✅ 通过 | 两次的 `registeredAt` 按新时间重算过（例如 `07:00` 的闹钟在新时区里对应的 epoch 变了），且 App 没有崩 |
| 记录 | 同时贴 `adb shell getprop persist.sys.timezone` 与 `adb shell date` |

### B.4 AC-11 / AC-13：锁屏全屏响铃 + 静音/勿扰下仍响

```powershell
# 静音模式
adb shell cmd audio set-ringer-mode SILENT
adb shell settings get global mode_ringer      # → 0
# 勿扰
adb shell settings put global zen_mode 1
# 锁屏
adb shell input keyevent KEYCODE_SLEEP
# 建一个 2 分钟后的闹钟，等它响
```

| 判读 | 标准 |
|---|---|
| ✅ 通过（AC-13） | 静音/勿扰下**有声音**；`adb shell dumpsys audio \| Select-String "STREAM_ALARM"` 里 `Muted: false` |
| ✅ 通过（AC-11） | **锁屏**状态下屏幕点亮并直接显示响铃页（`adb shell dumpsys activity activities \| Select-String topResumedActivity` → `com.alarmhub.app/.ring.RingActivity`） |
| ❌ 已知平台约束 | **解锁**状态下页面不会自己弹出来（API 36 的后台启动限制），只会有高优先级通知——这是 [M4-STATUS](M4-STATUS.md) §4.3 的实测结论，不算这批的失败项 |
| 额外一测 | 在体检页把「后台弹出界面」（或「显示在其他应用上层」）打开，**再测一次解锁状态**：如果页面能自己弹出来，就把这条杠杆的结论回给我（M5-STATUS §5 第三行） |

**测完务必还原**（M4 就是这么把模拟器留在静音上的）：

```powershell
adb shell cmd audio set-ringer-mode NORMAL
adb shell settings put global zen_mode 0
```

### B.5 AC-3 / AC-4：暂停期内不响、到期自动恢复

这两条在模拟器上只能验「排程被推迟 + PAUSE 触发到点重排」（`tools/m3-acceptance.ps1` 场景 2/4），
**「真的不响 / 真的自己恢复」需要把手机时间拨过去**：

```powershell
# 1) 建一个 2 分钟后响的闹钟（属于「未分组」）
# 2) 把「未分组」暂停 1 天 —— 暂停面板会显示「自动恢复 明天 00:00」
# 3) 记录数据库
adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db `
  "select id,name,pause_until,paused_at from alarm_groups;"
adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db `
  "select id,hour,minute,last_trigger_at from alarms;"

# 4) 等过了原定响铃时刻 —— 应该一声不响
# 5) 把手机时间拨到 pause_until 之后 —— 应该自动重新武装，不再需要任何手动操作
adb shell dumpsys alarm | Select-String "com.alarmhub.app" -Context 0,3
```

| 判读 | 标准 |
|---|---|
| ✅ AC-3 | `pause_until` 是一个**当天 00:00**（epoch 换算出来），`last_trigger_at` 被推到了暂停之后的一天；原定时刻一声不响 |
| ✅ AC-4 | 拨过 `pause_until` 后，`last_trigger_at` 变成未来某个时刻，且这个变化**不需要打开应用**（它是 PAUSE 触发自己做的） |

> 语义边界（不是 bug，先知道再看）：暂停在按下的一瞬间就被换算成绝对恢复时刻，而恢复时刻**永远是某天 00:00**，
> 所以任何「覆盖了某次响铃」的暂停，其后的第一次响铃必然晚约 24 小时（[M3-STATUS](M3-STATUS.md) §3.3）。

### B.6 AC-5：暂停与触发几乎同时发生

```powershell
$peer = 'com.alarmhub.app/.debug.DebugReceiver'
# 先注入一个 1 分钟后响的闹钟，让它注册好
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd inject --ei at 1 --es type DAILY
# 读回 alarmId 与注册时刻
adb shell run-as com.alarmhub.app cat files/debug-result.txt
# 紧接着暂停它所在的组（用 debug 通道制造竞态）
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd pause --el group <groupId> --el days 1
# 然后【重放】暂停之前就已经注册在那个时刻的触发
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd trigger --el alarm <alarmId> --el at <注册时刻 millis>
adb shell run-as com.alarmhub.app cat files/debug-result.txt
adb logcat -d | Select-String "GROUP_PAUSED"
```

| 判读 | 标准 |
|---|---|
| ✅ 通过 | 日志里出现 `SILENT EXIT reason=GROUP_PAUSED`，且**没有** `RING VALIDATED` |
| 为什么必须「重放」 | 组一旦暂停，正确的调度器会立刻把闹钟重排到更晚的一天，暂停期间根本不会有触发到达。现实中危险的那个触发，恰恰是状态变更**之前**就注册、仍然活在 `AlarmManager` 里的那个 |

### B.7 AC-2：节假日与调休

```powershell
# 建两个闹钟：一个「法定工作日」，一个「法定节假日」
# 把手机日期拨到国庆调休的那个周六 → 工作日闹钟应响
# 把手机日期拨到法定假日里的某个工作日 → 工作日闹钟不应响
adb shell dumpsys alarm | Select-String "com.alarmhub.app" -Context 0,3
```

| 判读 | 标准 |
|---|---|
| 记录 | 每次拨时间都贴 `adb shell date` 与 `dumpsys alarm` 的对应片段 |
| 数据覆盖 | 内置节假日数据只到 **2026 年**（`assets/holidays.json`）。拨到 2027 年时，「法定工作日」会降级成周一~周五，设置页应提示——这是 FR-6.3 的预期行为 |

### B.8 只能靠耳朵和手的项

| # | 项 | 怎么做 | 记录什么 |
|---|---|---|---|
| B.8.1 | **震动** | 建一个开了震动的闹钟，到点 | 感受到震动 / 没有。模拟器无法证实马达 |
| B.8.2 | **渐强** | 把渐强设为 30 秒，到点听 | 是从静音平滑升到最大，还是最后一秒突然变响（代码用的是平方曲线） |
| B.8.3 | **锁屏显示**（MIUI 项） | 关掉「锁屏显示」，锁屏响铃 | 锁屏上看不到响铃界面；打开后能看到 |
| B.8.4 | **AC-16 主题** | 设置里切换深色/浅色，逐页看 | 主列表 / 编辑页 / 设置 / 体检 / 响铃页各截一张图 |
| B.8.5 | **AC-6 / AC-7 / AC-8** | ① 开总开关后新建一次性闹钟，看「响铃后删除」是否默认勾选、能否单独取消；② 让它响一次并关闭，看它是否从列表消失；③ 设置页「一键处理」后核对数量 | 截图为证；③ 可对照 `adb shell run-as com.alarmhub.app sqlite3 databases/alarmhub.db "select id,repeat_type,delete_after_ring from alarms;"` |

### B.9 只要一条命令的项

```powershell
# AC-15：不申请 INTERNET
adb shell dumpsys package com.alarmhub.app | Select-String "INTERNET"     # 应该没有任何输出
```

```powershell
# AC-17：200 个闹钟的滚动流畅度（也可以先在模拟器上做）
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n com.alarmhub.app/.debug.DebugReceiver --es cmd inject --ei at 5
# 反复几次并用 UI 新建，或用界面导入一批，然后手指滚动列表
```

---

## C. 现场恢复

点检结束后把这几项还原，免得影响下一次：

```powershell
adb shell cmd audio set-ringer-mode NORMAL
adb shell settings put global zen_mode 0
# 时间/时区如果手工改过，改回「自动」
adb shell settings put global auto_time 1
adb shell settings put global auto_time_zone 1
# 清掉调试期间造的数据
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n com.alarmhub.app/.debug.DebugReceiver --es cmd wipe
```

---

## D. 回传时最有用的一句话

如果只能回一句，请回这一句：

> **「A.2 的 `autostart` 和 `backgroundPopup` 是 `opened=true` 还是 `fallback=true`，落点页面是什么？」**

这一条决定了 M5 里唯一留空的验证项是「通过」还是「需要改候选组件名」，
而它只有你的小米 14 能回答。
