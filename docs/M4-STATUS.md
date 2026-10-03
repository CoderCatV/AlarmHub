# M4 完成报告 · 响铃链路（原生）

| 项 | 值 |
|---|---|
| 里程碑 | **M4 · 响铃链路（原生）** |
| 相关文档 | [PRD](PRD.md) §FR-4.3 / §5.6 / §5.7 · [TECH-STACK](TECH-STACK.md) §4.4 / §4.5 · [M3 报告](M3-STATUS.md) |

---

## 1. 交付物

| # | 任务 | 产出 |
|---|---|---|
| M4.1 | `RingForegroundService` | `ring/RingForegroundService.kt` —— `mediaPlayback` 前台服务持有播放器，`startForeground` 失败也不停止发声 |
| M4.2 | **渐强与震动** | `ring/media/AlarmAudioPlayer.kt` —— 手动 `setVolume` 递增（平方曲线）、`VibrationEffect` 波形震动，二者独立 |
| M4.3 | **超时自动停止** | 服务内定时器 → `RingController.timeOut()` |
| M4.4 | `RingActivity` | `ring/RingActivity.kt` + `res/layout/activity_ring.xml` —— 原生 View、`showWhenLocked` + `turnScreenOn` + keyguard 消解、贪睡主按钮、音量键行为 |
| M4.5 | **响铃通知** | `ring/RingNotifications.kt` —— 独立高优先级渠道、`CATEGORY_ALARM`、`fullScreenIntent`、通知内「关闭」 |
| M4.6 | **贪睡调度** | `AlarmScheduler.scheduleSnooze` + `TriggerKind.SNOOZE`；计数持久化在 `alarms.snooze_count` |
| M4.7 | **响铃结束流程** | `ring/RingController.kt` —— 五条结束路径收敛到一处，按 PRD §5.6 收尾 |
| — | 通知栏动作 | `ring/RingActionReceiver.kt`（关闭 / 贪睡，无需打开页面） |
| — | DB v2 | `snooze_count` 列 + `MIGRATION_1_2`（**真迁移，不是破坏性重建**）+ 提交的 `2.json` |
| — | 测试通道收敛 | `DebugReceiver` 与 `permission.DEBUG` 移入 `src/debug/`，release 构建里**完全不存在** |

---

## 2. 验证证据

### 2.1 单测与仪测

```
JVM 单测          102 tests, 0 failures
仪测               28 tests = 17 数据层 + 6 调度 + 2 迁移 + 3 冒烟, 0 failures
```

迁移测试是本次新增里最要紧的一条：`migratingFrom1To2KeepsAlarmsAndDefaultsTheSnoozeCounter`
用**逐字照抄 v1 schema 的裸 SQL** 写一个 v1 库、塞进一个闹钟，再用**真实的 `AppDatabase.build`**
打开，断言分组与闹钟都还在、`last_trigger_at` 保住了、`snooze_count` 取到默认值 0。数据库是用户闹钟的
唯一副本，这条路径不允许"大概没事"。

### 2.2 模拟器实测（截图见 §3）

| 场景 | 实测 |
|---|---|
| 前台响铃 | `audioPlaying=true`，从 `content://settings/system/alarm_alert` 播放，音量 7；`dumpsys activity services` 显示 `isForeground=true`、`types=0x2`（mediaPlayback）、通知 `category=alarm` |
| **锁屏响铃** | 屏幕 `Asleep` → `Awake`，`topResumedActivity=RingActivity`，音频同时开始（两次独立复现） |
| **静音 + 勿扰下仍响** | `mode_ringer=0` 时 `audioPlaying=true`；`dumpsys audio` 里 `STREAM_ALARM Muted: false`，只有 `STREAM_NOTIFICATION` 被静音。DND（`zen_mode=1`）与静音（`zen_mode=0`）两种状态都测过 |
| **解锁时不自动弹页** | 见 §4.3：平台拒绝后台启动 Activity，用户看到的是高优先级通知（`.shots/m4-unlocked-headsup.png`）。**这是实测行为，不是未实现的项** |
| 贪睡 → 次数用尽 | 计数 1/2 → 2/2 → 第三次 `snoozeAccepted=false`，日志 `has no snoozes left; ignoring 贪睡` |
| 超时自动停止 | `auto-stop armed for 1 min` → `auto-stop elapsed after 1 min`（实测间隔 **60.004 s**）→ `ring ended: TIMED_OUT`，服务销毁 |
| 响铃后删除（FR-3.6） | `dismiss` 后 `stillExists=false`，DB 行数 0 |
| 未勾选 → 过期（FR-3.7） | `dismiss` 后 `stillExists=true expired=true`，DB 行 `15\|ONCE\|1`，`last_trigger_at` 被清空（不再调度） |

**准点精度**（M3 已测，M4 未回归）：注册 `01:51:00.000`、投递 `01:51:00.007`，误差 7 ms。

---

## 3. 截图

| 文件 | 内容 |
|---|---|
| `.shots/m4-ring-page.png` | 响铃页：大字时间、日期、标签、分组色点、贪睡剩余、贪睡/关闭按钮、音量键提示 |
| `.shots/m4-ring-lockscreen.png` | **锁屏**状态下由通知的 `fullScreenIntent` 自动拉到前台的全屏响铃页（屏幕从 Asleep 被唤醒） |
| `.shots/m4-ring-foreground.png` | **锁屏**路径再次复现（换成 5 分钟超时的闹钟）：同上，并显示「5 分钟后自动停止」 |
| `.shots/m4-unlocked-headsup.png` | **解锁**状态下的实际行为：设备处于静音，页面**没有**自动弹出，而是应用的高优先级通知（`category=alarm`，含「关闭」按钮）——见 §4.3 |

> 说明：`m4-unlocked-headsup.png` 与另外两张锁屏截图放在一起看，本身就是 §4.3 那个平台约束的证据。
> 早期我还存过一张标为"前台响铃"的截图，后来发现它其实是**桌面**（自动拉起页面还没修好时的产物），
> 已删除并重新采集——留一张名不副实的证据比没有证据更糟。

---

## 4. 过程中发现并处理的问题

### 4.1 `RingController` 的结束路径必须收敛（设计要点，已实现）

M4 有**五条**结束路径：关闭、贪睡、超时、响铃期间闹钟被别处删掉/关掉、服务被销毁。每一条对
**库里的那条记录**该做什么都不一样（一次性+响铃后删除要删；其余一次性要标过期；重复的要重排；
贪睡什么都不该改；被别处删掉则什么都不做）。散着写迟早会删错闹钟或标错过期，所以全部收敛到
`RingController.end(reason)` 一处，并用 `AtomicBoolean` 保证只生效一次（超时与手动关闭会撞车）。

> **更正（M7 真机实测）**：上面「五条结束路径」这句话，~~五条~~ **实际只有四条被接上**。
> 「响铃期间闹钟被别处删掉/关掉」那一条对应的入口 `RingController.cancelFromOutside()` **从头到尾没有
> 任何调用者** —— 逻辑写得对（`end(CANCELLED)` 会停声音并且不动数据库），但触发它的线从来没接。
> 后果是：用户从列表里删掉正在响的那条闹钟之后，**声音继续响、页面够不到、列表里那条也没了**，
> 彻底没有任何开关能碰到它（用户原话：「一直在响，也找不到关闭按钮」）。
>
> 现在接在插件的 `write {}` 里（所有写操作的唯一收口）：写完重算之后顺带核对
> `RingController.cancelIfNoLongerWanted()`。**这与 §4.9 记的 `ringInProgress` 是同一类错误** ——
> 「文档说这条路径能用」和「这条路径真的被调用」是两件事，而只有后者能在用户手里生效。
> 详见 [STATUS](STATUS.md) §1.1 #20。

### 4.2 调试通道会把"非前台服务启动"问题伪装成产品缺陷

用 adb 广播驱动 `ringnow` 时，服务起不来：

```
ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to
    mAllowStartForeground false
```

原因是 **adb 发的普通广播不带任何后台启动豁免**，而生产路径（`AlarmManager` 投递精确闹钟）带。
所以响铃相关的验收**必须走真实触发**，否则测的是调试通道而不是产品。`tools/m3-acceptance.ps1`
本来就是这么做的，M4 的验收也照此办理。

### 4.3 一个真实的平台约束：自己的页面拉不起来（已查清，已按证据记录）

解锁状态下、应用没有可见窗口时，**无论直接 `startActivity` 还是自己发 `PendingIntent`，系统都会拒绝**：

```
# 直接 startActivity
Background activity launch blocked! ... callingUidProcState: FOREGROUND_SERVICE
balAllowedByPiCreator: BSP.ALLOW_BAL ; autoOptInReason: notPendingIntent

# 自己发 PendingIntent
Background activity launch blocked! ... isPendingIntent: true
balAllowedByPiSender: BSP.ALLOW_BAL ; resultIfPiSenderAllowsBal: BAL_BLOCK
realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED
```

**"前台服务"在 API 36 上不是豁免条件。** 唯一被放行的路径是**系统**去发通知的 `fullScreenIntent`
（那时发送者是 SystemUI）——这也正是**锁屏场景能成功**的原因。

因此行为是：**锁屏时自动全屏弹出（已验证）；解锁时靠声音 + 高优先级通知（通知里带「关闭」按钮）**。
这与 TECH-STACK §4.4 的取舍一致——发声不依赖页面被拉起，所以最差情况是"响了，点通知才见页面"。
代码里 `RingActivity.show()` 仍然保留这一次尝试，因为它对授予了 `SYSTEM_ALERT_WINDOW` 的设备/OEM
有效，而 M5 的权限体检本来就会引导用户去开这个权限。

### 4.4 通知卡会盖住响铃页顶部（已改布局）

响铃通知是 `ongoing` 的，它的 heads-up 卡片在整段响铃期间都占着屏幕顶部约 430 px。原先把大字时间
放在页面顶部，结果**时间被完全盖住**。改成：外层容器承载系统栏 inset，内部用带权重的 Space 把
时间/标签/按钮分开分布，时间落到了通知卡下方（实测 y 从 147 移到 428）。

### 4.5 `stop()` 用 `startService` 会把服务"启动"起来（真缺陷，已修）

`RingForegroundService.stop()` 原先发一个 `ACTION_STOP` intent 给服务。对一个**没在运行**的服务，
`startService` 会把它**创建**出来，`onStartCommand` 于是为一个"已经被结束的响铃"再跑一遍，又调
`stop()` —— 每次关闭都复现一次。改成 `context.stopService(...)`，并让"结束响铃"这件事只经由
`RingController`。实测修复后该类日志出现次数为 0，服务也确实消失。

### 4.6 `--ei` 是 Int，`getLongExtra` 会静默返回默认值（调试工具缺陷，已修）

`DebugReceiver.longExtra` 用 `getLongExtra` 读 adb 的 `--ei`（Int）extra，**拿到的是默认值**。表现是
`--ei snoozeMax 2` 却报告 `snoozeMax=3`。更糟的是 `--ei snoozeMinutes 1` 看起来"生效了"，其实没有
——算出来的时间戳恰好落在 1 分钟后。改成按实际类型取值（`Long`/`Int`/`Short`/`Byte`/`String`）。

### 4.7 `room-testing` 的传递依赖在本工程无法版本自洽（已改测试策略）

`MigrationTestHelper` 需要 `room-testing` → `room-migration` → `kotlinx-serialization-json:1.8.1`，
而本工程 app 运行时把 `kotlinx-serialization-core` 解析到另一版本（`savedstate 1.3.1` 等把它按
consistent resolution 钉住）。结果是 json 1.8.1 跑在旧 core 上：

```
AbstractMethodError: abstract method "KSerializer[] GeneratedSerializer.typeParametersSerializers()"
    on receiver ... FieldBundle$$serializer
```

我先把整个 serialization 家族 force 到 1.8.1，仍然失败（说明辅助库自身是按更旧/更新的契约编译的）。
**与其继续猜版本，不如去掉这个依赖**：迁移测试改用裸 SQL 构造 v1 库、用真实的 `AppDatabase.build`
打开——测的其实是**更贴近生产**的路径，且不再需要 Room 的辅助类。代价是失去了 Room 自动比对
"迁移后 schema 是否等于实体"这一项，所以测试里逐个字段手工断言。

### 4.8 我的测试假设错过一次（记录备查）

写"全新库应该是空的"这条断言时忘了：仪测跑在**应用自己的进程**里，`AlarmHubApp.onCreate` 早就对着
**同一个数据库文件**跑过播种了。断言"空"其实是在断言启动行为，不是 schema。改成断言 v2 列可读。

### 4.9 一段"文档说能用、实际从未生效"的死分支（已删）

M3 里给 `AlarmHubApp` 加了个 `ringInProgress` 标志，注释写着"M4 起由前台 Service 设置"，用来做
PRD §5.4 的「已在响铃中」判定。**M4 没有设它**，所以那条分支永远拿到 `false`——一段有注释、有类型、
有单元测试，却在生产里从未生效的代码。

处理方式是**删掉它**，并把判据改为 `RingController.isRinging`：它由响铃页真正观察的那份状态推导，
不存在"第二份副本忘记同步"的可能。同时把 M3 报告里那句已被证伪的说明划掉并注明更正。

这条值得单独记，因为它和 §4.5、§4.6 是同一类错误：**注释与代码不同步时，注释会让人以为功能已经在了**。

---

## 5. 与 PRD / TECH-STACK 的对齐

- **发声在前台服务、页面只负责 UI**（TECH-STACK §4.4）：`startForeground` 失败也继续发声，页面拉不起来也继续发声。
- **响铃页用 View 而不是 Compose**（TECH-STACK §4.5）：原生 XML + 自绘 drawable，无新依赖。
- **贪睡次数持久化**：放数据库而不是内存，因为响铃跨越进程被回收的窗口；放**领域模型**（而非像
  `last_trigger_at` 那样当调度记账），因为规则要读它来决定贪睡还能不能用。
- **DB 版本 1 → 2 走真迁移**：用户闹钟的唯一副本，不允许破坏性重建。
- **release 构建里没有测试通道**：不是靠权限挡住，而是靠 source set 根本不编译进去。

---

## 6. 遗留与下一里程碑

| 项 | 说明 |
|---|---|
| 解锁状态下的自动全屏 | 见 §4.3。M5 的权限体检要引导 `SYSTEM_ALERT_WINDOW` 与 `USE_FULL_SCREEN_INTENT`，这是唯一可能改善它的杠杆 |
| 真机（小米 14 / HyperOS） | M4 的验收全在模拟器上。HyperOS 的后台管控对"响不响"是决定性的，必须真机验证（AC-12），建议在 M5/M6 之后与 M7 一起做 |
| 震动 | 代码路径与日志已就位，但模拟器无法证实马达真的转了。真机验证项 |
| 渐强曲线 | 采用平方曲线（线性在最后一秒会突然变响）。听感需要在真机上确认 |
| `SYSTEM_ALERT_WINDOW` | 未申请。M5 决定是引导开启还是降级为"点通知看页面" |
| M6 桥接用的响铃状态 | `RingController.state` 是 `StateFlow`，M6 可以直接投影给 H5 的响铃相关查询（如果有） |
