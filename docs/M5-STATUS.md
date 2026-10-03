# M5 完成报告 · 权限体检与 HyperOS 适配

| 项 | 值 |
|---|---|
| 里程碑 | **M5 · 权限体检与 HyperOS 适配** |
| 状态 | 模拟器部分完成并留证；**真机（小米 14 / HyperOS 3）部分已于 2026-10-02 补齐，见 §5** |
| 相关文档 | [PRD](PRD.md) §FR-5 / §FR-4.4.9 · [DEVELOPMENT-PLAN](DEVELOPMENT-PLAN.md) §M5 · [M4 报告](M4-STATUS.md) §4.3 / §6 · [M6 报告](M6-STATUS.md) · [真机点检清单](MACHINE-CHECKLIST.md) |

> **真机结论一句话**：HyperOS 3 上，「自启动」一步直达，「后台弹出界面 / 锁屏显示」会落到本应用的权限页、
> 需用户**再点一次「其他权限」**；候选组件名**一个都不用改**。三条 adb 通道在真机上失效，已记入点检清单。

---

## 1. 交付物

| # | 任务 | 产出 |
|---|---|---|
| M5.1 | **权限检测** | `permissions/PermissionModels.kt`（`PermissionKey` 的线上名字 + `PermissionStatus`）· `permissions/PermissionInspector.kt`（七项判定的唯一实现；返回三态：已满足 / 未开启 / 本机不适用） |
| M5.2 | **MIUI 跳转 + 兜底** | `permissions/PermissionSettingsLauncher.kt` —— 每项一串**有序**候选 Intent，逐个 `try/catch`；全部失败退到本应用系统详情页（PRD FR-5.3） |
| M5.3 | **主界面警示条** | `web/src/components/PermissionBanner.vue` + `state/store.ts` 的 `criticalMissingPermissions`（只针对 PRD FR-5.4 点名的精确闹钟与通知；不可关闭） |
| M5.4 | **首次启动引导** | `web/src/components/PermissionGuide.vue` + `store.ts` 的 `markPermissionCheckDone`（走 `settings.permission_check_done`，与契约既有字段一致） |
| M5.5 | **文案（FR-5.6）** | 引导页里的「结束运行后任何第三方闹钟都无法唤醒」段落；顺带修掉体检页里一处把 `**强调**` 当 Markdown 写进 HTML 的旧文案（§4.2） |
| — | **通知的运行时申请** | 桥里用 Capacitor 的 `@CapacitorPlugin(permissions=…)` + `@PermissionCallback`：API 33+ 先弹系统对话框，用完了才退到设置页 |
| — | **权限探针的共享状态** | `store.ts` 的 `permissions` / `missingPermissions` / `loadPermissionStatus`：警示条与体检页读**同一份**判定，并在应用回到前台时重探一次 |
| — | **Manifest** | 新增 `SYSTEM_ALERT_WINDOW`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`<queries>`（MIUI 包可见性，§4.4） |
| — | **诊断通道（仅 debug）** | `DebugReceiver` 新增 `cmd permissions`（七项判定 + 设备身份）与 `cmd permissionjump`（跑真实候选序列，报告每条候选是否 resolves）——真机点检靠它，见 [§6 清单](MACHINE-CHECKLIST.md) |

---

## 2. 验证证据（模拟器）

设备：`Google sdk_gphone64_x86_64`，**sdk=36**，`miui=false`。全部命令与输出都是实测。

### 2.1 七项检测的准确性（`cmd permissions`）

```
COMMAND> adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n com.alarmhub.app/.debug.DebugReceiver --es cmd permissions
COMMAND> adb shell run-as com.alarmhub.app cat files/debug-result.txt
cmd=permissions
device manufacturer=Google brand=google model=sdk_gphone64_x86_64 sdk=36 miui=false
key=exactAlarm granted=true applicable=true label=精确闹钟
key=notifications granted=true applicable=true label=通知
key=fullScreenIntent granted=true applicable=true label=全屏通知
key=autostart granted=true applicable=false label=自启动
key=batteryUnrestricted granted=false applicable=true label=电池无限制
key=backgroundPopup granted=false applicable=true label=后台弹出界面
key=lockScreenDisplay granted=true applicable=false label=锁屏显示
applicableGranted=3/5
```

（`notifications` 是授予之后读的；全新安装时是 `granted=false`，见 §2.3。）

对照 DEVELOPMENT-PLAN M5 的验证要求「在模拟器上『自启动』等项应正确显示为**不适用**或已满足」：

| 项 | 模拟器上的判定 | 依据 |
|---|---|---|
| 精确闹钟 | ✅ 已满足 | `USE_EXACT_ALARM` 装机即授予，`canScheduleExactAlarms()` 返回 true |
| 通知 | 随实际授予状态变化 | `NotificationManagerCompat.areNotificationsEnabled()` |
| 全屏通知 | ✅ 已满足 | API 34+ `canUseFullScreenIntent()`；闹钟类应用自动授予 |
| 自启动 | **不适用** | 非 MIUI ⇒ 本机没有这个开关（`applicable=false`，页面渲染「— / 不适用」） |
| 电池无限制 | ✕ 未开启 | `isIgnoringBatteryOptimizations()` 返回 false |
| 后台弹出界面 | ✕ 未开启 | 非 MIUI ⇒ 映射到 `Settings.canDrawOverlays()`（§3.2 的取舍） |
| 锁屏显示 | **不适用** | 非 MIUI ⇒ 本机没有这个开关 |

### 2.2 一键跳转：四个序列都真的落在系统页面上

```
COMMAND> adb shell am broadcast … --es cmd permissionjump --es key <key>
COMMAND> adb shell run-as com.alarmhub.app cat files/debug-result.txt
COMMAND> adb shell dumpsys activity activities | Select-String topResumedActivity
```

| key | 输出 | 实际落在 |
|---|---|---|
| `backgroundPopup` | `opened=true fallback=false candidates=2` / `resolves=true android.settings.action.MANAGE_OVERLAY_PERMISSION` | `com.android.settings/.spa.SpaActivity`（本应用的「显示在其他应用上层」页） |
| `autostart` | `opened=true fallback=false candidates=1` / `resolves=true android.settings.APPLICATION_DETAILS_SETTINGS` | `com.android.settings/.spa.SpaActivity`（非 MIUI 上「应用详情页」就是该项的正当目标，不是兜底） |
| `batteryUnrestricted` | `opened=true fallback=false candidates=3` / `resolves=true android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`（**直接弹本应用的对话框**，不是列表页） |
| `exactAlarm` | `opened=true fallback=false candidates=2` / `resolves=true android.settings.REQUEST_SCHEDULE_EXACT_ALARM` | Settings 的精确闹钟页 |

### 2.3 通知：系统对话框 → 行状态自己刷新

全新安装后的第一次体检（`cmd permissions` 里 `notifications granted=false`），点击「通知」这一行：

| 步骤 | 证据 |
|---|---|
| 弹出系统对话框 | `.shots/m5-notification-prompt.png`；同一时刻 `adb shell dumpsys package com.alarmhub.app` 里 `POST_NOTIFICATIONS: granted=false` |
| 点 Allow 之后 | `POST_NOTIFICATIONS: granted=true`，UI 的圆点从 ✕ 变 ✓，汇总从 `3 项待处理 / 2 / 5 项已就绪` 变成 `2 项待处理 / 3 / 5 项已就绪` |

这一步同时证明了「promise 在对话框被回答之后才 settle」这个设计是成立的：体检页在 `await` 之后立刻重探，读到的就是新状态，不需要用户手动点「刷新」。

### 2.4 兜底（PRD FR-5.3）可用

让候选序列全部失效（`--ei breakAll 1`，debug 通道专用开关），走的就是 HyperOS 上「所有候选 Intent 都失败」的那条路：

```
COMMAND> adb shell am broadcast … --es cmd permissionjump --es key backgroundPopup --ei breakAll 1
key=backgroundPopup opened=false fallback=true candidates=0
fallbackTarget=android.settings.APPLICATION_DETAILS_SETTINGS
topResumedActivity=com.android.settings/.spa.SpaActivity
```

截图 `.shots/m5-fallback-app-details.png` 就是那个页面（App info）。**注意 `opened=false`** —— 这正是 §4.1 那个真 bug 修好后的样子。

### 2.5 警示条与首次启动引导（FR-5.4 / FR-5.5）

全新安装、未授予通知时启动：

- `.shots/m5-first-run.png`：主界面顶部常驻警示条「通知 还没开 / 去设置 ›」，同时首次引导以底部卡片出现，列出三项待处理（通知、电池无限制、后台弹出界面）+ FR-5.6 文案 + 「以后再说 / 去体检」。
- 点「去体检」后：

```
COMMAND> adb shell run-as com.alarmhub.app sqlite3 databases/alarmhub.db 'select permission_check_done from settings;'
1
```

即引导的「已处理过」状态是**真落库**的（这一条同时是 [M6 报告](M6-STATUS.md) 里「界面操作真实落库」的第一个证据）。

- `.shots/m5-permissions.png`：体检页七项全渲染，`2` 项显示「不适用」且不可点击，`3 项待处理 / 2 / 5 项已就绪`。

---

## 3. 产品取舍（按 PRD 现有倾向自行决定）

1. **检测哪几项** = PRD FR-5.1 原话的 6 项（精确闹钟、通知、自启动、省电策略、后台弹出界面、锁屏显示）**加** `fullScreenIntent`（体检页的顺序把它插在「通知」之后）。加它的理由不是偏好而是 M4 的实测结论：锁屏自动全屏走的正是 `fullScreenIntent`（[M4-STATUS](M4-STATUS.md) §4.3）。契约里 `PermissionKey` 本来就有这七个值，所以**没有改契约**。
2. **`backgroundPopup` 在非 MIUI 上映射到 `SYSTEM_ALERT_WINDOW`**，而不是判成「不适用」。理由是 M4-STATUS §4.3 与 STATUS §4 都记着：悬浮窗权限是解锁状态下让响铃页自己弹出来的**唯一**杠杆；判成「不适用」等于把这个可行动作藏起来。代价是 Manifest 里多了一条 `SYSTEM_ALERT_WINDOW` 声明（自用项目，不上架）。
3. **警示条只报精确闹钟与通知**（PRD FR-5.4 点名的两项）。其余五项缺失只在体检页里显示——主界面常驻条一旦报五项，用户会把它当装饰。
4. **首次启动引导只在「确实有可处理项」时出现**，否则静默放过。指向一个全绿的体检页只会教用户以后直接划掉。**「以后再说」与「去体检」都把 `permission_check_done` 置真**：这个标志的含义是「已经引导过了」，不是「权限都齐了」；权限是否齐由警示条独立跟踪。
5. **不提供「静音」铃声选项**（`EXTRA_RINGTONE_SHOW_SILENT=false`）。响铃链路把选中的 URI 当「播这个」，`silent:` 会 prepare 失败并静默回落到系统默认闹钟声——提供一个会让手机最响的「静音」选项比不提供更糟。
6. **通知项在 API 33+ 先弹系统对话框**，被永久拒绝后才退到设置页；其余各项直接走设置页。
7. **MIUI 私有项无法自动检测时的呈现**：报 ✕（未开启）并在描述里明写「本机无法自动检测这一项，请自己确认它已经打开」，而不是猜成「已就绪」。§4.3 解释了这个选择的代价。

---

## 4. 过程中发现并处理的问题

### 4.1 **跳转兜底的真 bug**：`opened` 永远为 true，FR-5.3 的提示永远不会出现（已修）

`open()` 的实现是「逐个试候选，都不行就跳应用详情页」，返回值直接用了最后那个 `tryStart(...)` 的结果。于是**当兜底页成功打开时，返回值是 true**，桥把它报成 `{opened: true, fallback: false}`，体检页那行「没能直接打开…已跳到本应用的系统详情页」永远不显示。

这正是 FR-5.3 存在的意义所在的那条路径：**在 HyperOS 上候选全部失效时，用户会以为跳转成功了。** 发现方式不是读代码，而是 §2.4 那次强制走兜底的实测 —— 输出是 `candidates=0` 却 `opened=true`，两者自相矛盾。

修法：`openFirstOf` 只在**候选**命中时返回 true，用兜底页时无论它是否打开都返回 false。

### 4.2 一条写着 Markdown 的 HTML 文案（已修）

体检页底部的 FR-5.6 文案原文是 `那么**任何**第三方闹钟都无法再被唤醒`。`.vue` 模板里 `**` 不会被解析成强调，用户看到的就是两个星号。M1c 之后没人回看这段文案，所以一直没人发现。已改成 `<strong>` 并补了配色。

### 4.3 MIUI 那三项本来就没有可靠检测接口（设计约束，不是 bug）

自启动 / 后台弹出界面 / 锁屏显示在 MIUI 上是 **AppOps 操作**而不是权限，名字没有公开文档，且在不同 HyperOS 版本之间换过拼写。`PermissionInspector` 的做法是：

1. 非 MIUI ⇒ `applicable=false`，页面显示「不适用」，不进这条路径；
2. MIUI ⇒ 依次试 `android:auto_start` / `auto_start` / `miui:auto_start` 等拼写（`unsafeCheckOpNoThrow`，某个 ROM 不认识的名字会抛 `IllegalArgumentException`，据此换下一个）；
3. **一个都没被这个 ROM 认识** ⇒ 报 ✕ 并在描述后附「（本机无法自动检测这一项，请自己确认它已经打开）」。

这是刻意选的失败方向：**宁可误报「未开启」，也不要误报「已开启」**——后者会让用户在真机上以为万事俱备，而闹钟该响的时候不响。

代价是：真机上如果三项都探不到，体检页会长期显示 2~3 项待处理，并且永远清不掉。真正的解法是加一个「我已手动开启」的覆盖开关，那需要往**冻结的契约**里加字段或方法，因此留给 M7 一并决定（见 §7 待用户确认第 5 条）。

### 4.4 包可见性：不声明 `<queries>`，MIUI 跳转在真机上会静默失败

API 30 起应用看不到、也拉不起未声明的其它包。HyperOS 的「自启动」「应用权限管理」都在 `com.miui.securitycenter` 里，而且是以**显式组件**启动的。没有 `<queries>` 时，`startActivity` 会抛 `ActivityNotFoundException`——而它的表象和「MIUI 改了页面名字」一模一样，是一个会让人往错方向查半天的坑。Manifest 里已声明该包与两条 `miui.intent.action.*`。

（顺带：检测「是不是 MIUI」用的是 `Build.MANUFACTURER` / `Build.BRAND`，**没有**用「`com.miui.securitycenter` 存不存在」——后者会因为同一个包可见性规则而恒为 false，等于把检测逻辑变成 Manifest 的副本。）

### 4.5 上一次会话留下的模拟器状态

M4 为了测「静音下仍响铃」把铃声模式改成了 SILENT（STATUS §3.3）。本次开始时已还原：

```
adb shell settings put global mode_ringer 2      # 2 = NORMAL
```

另：M6 的「到点真的响」验证拨过设备时钟，**已还原**（`settings put global auto_time 1`，实测回到 `2026-10-02 05:45:07 GMT` 并触发 `TIME_SET` 重算）。

---

## 5. 真机验证（2026-10-02 补齐）

用户把小米 14 插上电脑之后，**这一节原先留空的那一项已经验完了**。

设备（`adb shell getprop` 实录）：`Xiaomi` / `23127PN0CC`（小米 14）/ Android **16** / SDK 36 /
**`ro.mi.os.version.name=OS3.0`、`ro.miui.ui.version.name=V816`** —— 也就是 **HyperOS 3**。

### 5.1 三个页面名到底叫什么（`aapt2 dump xmltree` 读 HyperOS 3 的 `com.miui.securitycenter`）

| 我候选表里的组件 | 在 HyperOS 3 上存在吗 | `exported` | 额外权限要求 | 结论 |
|---|---|---|---|---|
| `com.miui.permcenter.autostart.AutoStartManagementActivity`（自启动管理） | **存在** | **true** | 无 | ✅ 命中 |
| `com.miui.permcenter.permissions.PermissionsEditorActivity`（应用权限管理） | **存在** | **true** | 无 | ✅ 命中 |
| `com.miui.powercenter.PowerSettings`（省电策略） | **存在** | **true** | 无 | ✅ 候选有效 |
| `com.miui.permcenter.settings.OtherPermissionsActivity`（**后台弹出界面 / 锁屏显示 的实际所在页**） | 存在 | true | **`com.miui.securitycenter.permission.SYSTEM_PERMISSION_DECLARE`（签名级，第三方拿不到）** | ❌ **刻意不加进候选表** |

也就是说：**M5 的候选 Intent 序列在 HyperOS 3 上是正确的**，一个组件名都不用改。

### 5.2 「跳转命中」——用同一套 intent 在真机上实测

```
COMMAND> adb -s b9026932 shell am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity
Starting: Intent { cmp=com.miui.securitycenter/…AutoStartManagementActivity }
topResumedActivity=com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity
```

截图 [`.shots/real-autostart-page.png`](../.shots/real-autostart-page.png)：**「自启动管理」页真的打开了，闹钟坞就在「允许 5 个应用自启动」列表里，开关是开的。**

```
COMMAND> adb -s b9026932 shell am start -a miui.intent.action.APP_PERM_EDITOR \
           -n com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity \
           --es extra_pkgname com.alarmhub.app --es package_name com.alarmhub.app
topResumedActivity=…/PermissionsEditorActivity
```

截图 [`.shots/real-permission-editor.png`](../.shots/real-permission-editor.png)：**标题就是「闹钟坞」** —— 说明
`extra_pkgname` 这个 extras 契约也是对的，页面正确落到了本应用上。

再点进「其他权限」之后（[`.shots/real-other-permissions.png`](../.shots/real-other-permissions.png)），
`uiautomator dump` 读到的条目里确认有：

```
锁屏显示
后台弹出界面
显示悬浮窗
链式启动管理
```

**所以「跳转命中」的结论是：**
- **自启动**：一步直达 ✅
- **后台弹出界面 / 锁屏显示**：一步到「闹钟坞的应用权限管理页」，**用户还要再点一次「其他权限」**才能看到开关。
  这是 MIUI 的权限设计（那一页要求第三方拿不到的签名级权限），不是缺陷 —— 正是 PRD FR-5.3 说的「图文指路」。
  **为此改了文案**：这两行的描述现在明写「点『去设置』会打开本应用的权限页，还要再点一次『其他权限』」。

### 5.3 MIUI 私有项探针：**已在真机上跑通**（这是本节最重要的一条）

#### 一开始是坏的，而且是**假红灯**

新版本首次在真机启动后，体检页显示的是：

```
2 项待处理 / 5 / 7 项已就绪
✓ 精确闹钟  ✓ 通知  ✓ 全屏通知
✕ 自启动      ← 但「自启动管理」页里闹钟坞的开关明明是【开着的】
✓ 电池无限制  ✓ 后台弹出界面
✕ 锁屏显示
```

日志给出了原因：

```
I AlarmHub/Permissions: no MIUI op candidate resolved: android:auto_start=?, auto_start=?, miui:auto_start=?
I AlarmHub/Permissions: no MIUI op candidate resolved: android:background_start_activity=?, …
I AlarmHub/Permissions: no MIUI op candidate resolved: android:lockscreen_display=?, …
```

`=?` 表示 `IllegalArgumentException`（这个 ROM 不认识这个名字）。**九个候选名字在 HyperOS 3 上一个都不存在。**

#### 真相：HyperOS 3 把这些 op 改成了**只有编号、没有名字**

```
COMMAND> adb shell "cmd appops get com.alarmhub.app"
MIUIOP(10008): allow; time=+52s311ms ago
MIUIOP(10017): allow      MIUIOP(10018): ignore
MIUIOP(10020): allow      MIUIOP(10021): allow
… MIUIOP(10022) (10033) (10045) (10048) (10049) (10053)
```

而且 `cmd appops get <包> 'MIUIOP(10008)'` 报 `Unknown operation string` —— 这些 op **没有可查询的字符串名字**，只能按编号访问。

#### 编号是靠**实验**定下来的，不是猜的

我在电脑上每 2 秒轮询一次 `cmd appops get com.alarmhub.app`，请用户按顺序拨了三下开关。记录（时间戳原文）：

```
16:46:22  MIUIOP(10008): allow → ignore      ← ① 关「自启动」
16:47:04  MIUIOP(10021): allow → ignore      ← ② 关「后台弹出界面」
16:47:10  MIUIOP(10020): allow → ignore      ← ③ 关「锁屏显示」
16:47:22  MIUIOP(10021): ignore → allow      ← 开回来
16:47:25  MIUIOP(10020): ignore → allow
16:47:31  MIUIOP(10008): allow               ← 开回来
```

| 编号 | 开关 |
|---|---|
| `10008` | **自启动** |
| `10021` | **后台弹出界面** |
| `10020` | **锁屏显示** |
| `10053` | 链式启动管理（跟自启动联动；契约里没有这一行） |

顺带的旁证：**16:49:02 那个闹钟响的时候，`10020` 和 `10021` 第一次出现 `time=` 时间戳** —— 响铃时 MIUI 确实去检查了"后台弹出界面"和"锁屏显示"。

#### 还验证了 `allow` 真的表示"已授权"

这一点必须确认，否则探针可能只是永远返回 `allow` 的假绿。拿四台**在「禁止自启动」列表里**的应用做对照：

| 应用 | `10008` | `10020` | `10021` |
|---|---|---|---|
| 钉钉 | ignore | ignore | allow |
| 阿里云 | ignore | ignore | ignore |
| 通义 | ignore | ignore | ignore |
| 阿里巴巴 | ignore | ignore | ignore |

**默认是 `ignore`（拒绝），不是 `allow`** —— 所以绿勾不是误报。（钉钉的 `10021=allow` 也合理：聊天类应用本来就需要后台弹出。）

#### 实现：按编号查，而 SDK 只公开了字符串版本 → 走反射

`javap android.app.AppOpsManager`（compileSdk 36）里只有 `unsafeCheckOpNoThrow(String, int, String)` 这一族；
按编号的 `unsafeCheckOpNoThrow(int, …)` / `checkOpNoThrow(int, …)` 是 **hidden**。所以实现是
**反射优先（按编号）→ 字符串兜底（老 MIUI）**，并把用了哪条路径打进日志。

真机实测结果：**`checkOpNoThrow`（API 19 时代公开、后来被隐藏的那个）在灰名单里，反射可用**：

```
I AlarmHub/Permissions: numeric AppOps route via checkOpNoThrow     （三项各一次）
```

#### 修好之后（同一台手机，CDP 读到的页面原文）

```
全部正常 / 7 / 7 项已就绪
✓ 精确闹钟 ✓ 通知 ✓ 全屏通知 ✓ 自启动 ✓ 电池无限制 ✓ 后台弹出界面 ✓ 锁屏显示
```

七项**全部判定出来**，没有一行落进"无法自动检测"。

#### 这一条修正了我 M5 里的一个判断

[M5-STATUS §4.3](#43-miui-那三项本来就没有可靠检测接口设计约束不是-bug) 原来的结论是「宁可误报『未开启』，也不要误报『已开启』」。
真机证明：**这个探针在 HyperOS 3 上 100% 失败**，于是那条"宁可"的红灯 100% 是假的 ——
它没有换来安全，只是让体检页永远显示「2 项待处理」，把用户的注意力训练到忽略这一页。
现在探针能用了，那条取舍自然作废；但如果**将来**遇到一个连编号都读不到的 ROM，正确的默认应该是
**不给出判定**（`granted` 报已就绪 + 文案注明无法检测），而不是报一个假红灯。原文保留在上面。

### 5.4 真机上**走不通**的三条通道（都记进 [点检清单](MACHINE-CHECKLIST.md) 了）

| 通道 | 现象 | 原因 |
|---|---|---|
| `run-as … sqlite3` | `run-as: exec failed for sqlite3: Permission denied` | HyperOS 不允许在应用沙箱里执行 `sqlite3`。查库改走应用自己的调试通道，或把 `.db` / `-wal` 一起 `exec-out` 拉下来 |
| `adb shell am broadcast … DebugReceiver` | 广播被丢弃，结果文件不生成 | 调试接收器声明了**签名级** `com.alarmhub.app.permission.DEBUG`，而 `adb shell` 不持有它。（**模拟器上能跑是因为我早先 `adb root` 过** —— 真机上 adb shell 不是 root） |
| `adb shell input tap/swipe` | `SecurityException: Injecting input events requires … INJECT_EVENTS permission` | HyperOS 收紧了输入注入。**手机上的触摸只能靠手指**，模拟器不受影响 |

> 第三条尤其关键：它意味着**真机上的界面验收只能靠用户手动操作**，脚本化的触摸注入在 HyperOS 上不可用。

### 5.5 仍然待真机验证的

| 项 | 状态 |
|---|---|
| ~~`PermissionInspector` 的 MIUI AppOps 探针在 HyperOS 3 上能否读到~~ | **已验证，见 §5.3** —— 按编号（反射 `checkOpNoThrow`）+ 字符串兜底，七项全部判定出来 |
| 授予 `SYSTEM_ALERT_WINDOW` 后解锁状态能否自动全屏 | **待验证**（M4-STATUS §4.3 的杠杆）。用户已授予悬浮窗，但没有实测过解锁状态下响铃页能否自动弹出 |
| 响铃相关的真机项（AC-12 / AC-13 / AC-9 / AC-10） | **待验证**，见 [点检清单](MACHINE-CHECKLIST.md) §B。已观测到一次真实响铃（16:49，用户手动建的闹钟，准点响并自行删除） |

### 5.6 真机上观测到的其它事实

- **一次真实的准点响铃**：用户 16:49 建的闹钟准点响了。响完后库里查不到那条 —— 因为它是一次性 + 响铃后删除，
  **自己删掉了**（PRD FR-3.6 在真机上生效）。旁证是同一时刻 MIUI 对 `10020`/`10021` 做了检查。
- **`Error injecting safe area CSS`**：真机上反复出现这条 Capacitor 的警告（注入 safe-area 变量时文档根还不存在）。
  `tokens.css` 里本来就有 `env(safe-area-inset-*)` 兜底，实测头部位置正常（在状态栏下方），因此不影响布局；
  但它是"布局在真机上为什么和模拟器不同"时第一个该看的东西。
- **用户的手机是 24 小时制**（`time_format='24'`），所以 §滚轮那个 12/24 混用的 bug 正好命中他。
  见 [手机反馈-滚轮与保存.md](手机反馈-滚轮与保存.md)。

---

## 6. 截图

| 文件 | 内容 |
|---|---|
| `.shots/m5-first-run.png` | 全新安装首启：主界面常驻警示条 + 首次引导卡片（PRD FR-5.4 / FR-5.5） |
| `.shots/m5-permissions.png` | 体检页：七项全渲染，两项「不适用」，`3 项待处理 / 2 / 5 项已就绪` |
| `.shots/m5-notification-prompt.png` | 点「通知」弹出的系统运行时权限对话框 |
| `.shots/m5-fallback-app-details.png` | 强制走兜底后落在本应用的系统详情页（PRD FR-5.3） |

---

## 7. 待用户确认

| # | 事项 | 我选的 | 备选 |
|---|---|---|---|
| 1 | 体检页检测哪几项 | PRD FR-5.1 的六项 + `fullScreenIntent` | 只做六项；或再加上「锁屏通知渠道可见性」 |
| 2 | 非 MIUI 上「后台弹出界面」怎么办 | 映射到 `SYSTEM_ALERT_WINDOW` 并申请该权限 | 判成「不适用」（等于把这个可行动作藏起来） |
| 3 | 警示条报哪几项 | 只报精确闹钟 + 通知（FR-5.4 原话） | 报全部适用项缺失 |
| 4 | 首次引导出现条件 | 仅「有可处理项」时出现一次；两个按钮都置 `permission_check_done` | 无条件首启一次；或每次启动都提示 |
| 5 | MIUI 探不到时怎么显示 | 报 ✕ + 「无法自动检测」文案 | 加「我已手动开启」覆盖开关（需动冻结契约，我没动） |
| 6 | 从主界面警示条进体检页后「返回」去哪 | **回到「设置」页**（沿用 M1c 的层级），不是回主界面 | 引入真正的页面栈，返回来源页；或从警示条进入时隐藏「返回」改为「关闭」 |
| 7 | 通知项在 API 33+ 先弹对话框 | 是 | 一律跳系统设置页 |
| 8 | 铃声选择器不给「静音」选项 | 是（§3.5） | 给「静音」并让响铃链路真正支持不发声 |
| 9 | 本次**没有新增测试用例** | 保持单测 102 / 仪测 28 不变，M5/M6 的验证全部走设备实测（用户要求「改完后要能复现同样的数字」） | 为 `PermissionInspector` 补仪测（需要真 PackageManager，JVM 单测测不了） |

第 9 条尤其想请你确认：我不想为了漂亮的测试数字而让「102 / 28」这两个可复现基线失效，但如果你的偏好是「新功能必须带新测试」，我可以补一组仪测并把数字更新成新的基线。

---

## 8. 遗留与下一里程碑

| 项 | 说明 |
|---|---|
| 跳转命中（真机） | 见 §5，M7.2 的 AC-14 |
| MIUI 三项的探针 | 见 §4.3 / §5；若真机上一个都不认，建议在 M7 加手动覆盖开关 |
| 页面栈 | 见 §7 第 6 条。`state/nav.ts` 是单路由，没有真正的历史栈；主界面成了体检页的第二个入口之后，返回目标就只能是硬编码的 |
| 警示条的位置 | 现在排在 hero 之下、分组列表之上（`.perm`）+ `.global` 暂停条之前。M7 打磨时和行高、FAB 遮挡一起看 |
| `SYSTEM_ALERT_WINDOW` 的效果 | 已在 Manifest 声明并可引导，但「授予后解锁状态能否自动全屏」未实测（M7.2） |
