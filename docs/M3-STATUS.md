# M3 完成报告 · 数据层与调度引擎

| 项 | 值 |
|---|---|
| 里程碑 | **M3 · 数据层与调度引擎** |
| 状态 | 见 §2 验证证据（单测 / 仪测 / 模拟器实测三部分） |
| 相关文档 | [PRD](PRD.md) §4 / §5.4 / §5.7 / §FR-4.5 · [TECH-STACK](TECH-STACK.md) §4.2 · [DEVELOPMENT-PLAN](DEVELOPMENT-PLAN.md) §M3 · [M2 报告](M2-STATUS.md) |

---

## 1. 交付物

| # | 任务 | 产出 |
|---|---|---|
| M3.1 | **Room 接入** | KSP 插件（根 `build.gradle` 钉 `ksp_version`）· `data/db/Entities.kt`（`alarm_groups` / `alarms` / `settings`）· `AlarmDao.kt` · `AppDatabase.kt`（WAL、类型转换器、**schema 导出到 `app/schemas/` 并提交**） |
| M3.2 | **首次启动种子数据** | `data/BuiltInGroups.kt` —— 未分组(1) / 工作日(2) / 节假日(3) + 默认设置行；`seedIfEmpty` 幂等，且在「有分组但缺设置行」的升级场景下补写 |
| M3.3 | **Repository** | `data/AlarmRepository.kt` + `data/Mappers.kt`（领域↔实体唯一转换点）· 跨表不变量走 Room 事务（PRD FR-1.4） |
| M3.4 | `AlarmScheduler` | `alarm/AlarmScheduler.kt` —— 主触发 `setAlarmClock` / 冗余前沿 `setExactAndAllowWhileIdle`(准点前 5s) / `pauseUntil` 恢复触发；requestCode 由 `alarm.id` 派生 |
| M3.5 | `AlarmReceiver` | `alarm/AlarmReceiver.kt` —— 调用 M2 的 `RingTimeValidator` 做二次校验，退出分支全部重排下一次 |
| M3.6 | **系统事件 Receiver** | `alarm/SystemEventReceiver.kt` —— `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` / `MY_PACKAGE_REPLACED` → 全量重算 |
| M3.7 | **暂停恢复触发** | 上面 `AlarmScheduler` 的第三种触发：`pauseUntil` 时刻注册一个 `setExactAndAllowWhileIdle`，到点重排 |
| — | 进程装配 | `AlarmHubApp.kt` —— 数据库 / Repository / 节假日日历 / Scheduler 的手写容器；冷启动即播种 + 全量注册 |
| — | Manifest | 权限（`USE_EXACT_ALARM` 等 8 项，**仍然没有 `INTERNET`**）、两个 Receiver、`.AlarmHubApp` 作为 Application |
| — | M3 专用测试通道 | `debug/DebugReceiver.kt` + 签名级权限 —— **M6 删除**，见 §4 |
| — | 验收脚本 | `tools/m3-acceptance.ps1` —— 把 §3 的五个场景变成可重跑的命令 |

---

## 2. 验证证据

### 2.1 M2 单测没有回归

```
$ cd android; gradle :app:testDebugUnitTest --console=plain
BUILD SUCCESSFUL     (102 tests, 0 failures)
```

### 2.2 数据层仪测（真机/模拟器上跑，真 SQLite）

```
$ gradle :app:assembleDebugAndroidTest
$ adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

$ adb shell am instrument -w -e class com.alarmhub.app.data.AlarmRepositoryTest \
      com.alarmhub.app.test/androidx.test.runner.AndroidJUnitRunner
OK (17 tests)

$ adb shell am instrument -w -e class com.alarmhub.app.alarm.AlarmSchedulerTest ...
OK (6 tests)

$ adb shell am instrument -w -e class com.alarmhub.app.ShellSmokeTest ...
OK (3 tests)
```

17 + 6 + 3 = **26 个仪测**。前 23 个是 M3 新增的，覆盖：种子数据与幂等、字段全量往返（含中文标签）、五种 `repeatType` 的枚举转换、FR-1.4 的「删分组不删闹钟」以及支撑它的外键 RESTRICT、分组计数、排序持久化、可调度集合的过滤、`last_trigger_at` 的存取、批量转换（含「重复闹钟不得被转成响铃后删除」与幂等）、过期标记、暂停/恢复的互斥、设置的部分更新、**导出的 schema JSON 与实体一致**，以及 requestCode 在 500 个闹钟 id × 3 种触发 = 1500 个码上两两不重复。最后 3 个是 M0 留下的冒烟测试（WebView 外壳 + 无 `INTERNET` 权限），用来确认 M3 改动 Manifest 与 Application 之后没有回归。

### 2.3 模拟器端到端实测（`tools/m3-acceptance.ps1`）

完整输出：**[`.shots/m3-acceptance-full.txt`](../.shots/m3-acceptance-full.txt)**。五个场景全部通过：

```
===== summary =====
1 on-time trigger                   PASS  PRE=True MAIN=True rescheduled=True dumpsys=True alarmClock=True
2 paused group stays silent         PASS  silentExit=GROUP_PAUSED rang=False replayedStaleTrigger=True
3 permanently disabled stays silent PASS  silentExit=GROUP_PERMANENTLY_DISABLED rang=False
4 pause expiry auto-resumes         PASS  deferredPastPause=True pauseTrigger=True reArmedAfterExpiry=True
5 system events re-register         PASS  bootBroadcast=True timeSetBroadcast=True registeredAfterBoot=True registeredAfterTimeSet=True
5/5 scenarios passed
```

各场景在断言什么，以及**刻意不**断言什么：

| # | 场景 | 断言 |
|---|---|---|
| 1 | 准点触发 | 冗余前沿先校验通过；主触发准点校验通过；之后重排下一次；`dumpsys alarm` 里确实存在本应用的注册，且用的是 `setAlarmClock` |
| 2 | 暂停后不触发（AC-3） | **重放暂停之前注册的那个触发**，得到 `SILENT EXIT reason=GROUP_PAUSED`，且全程没有 `RING VALIDATED` |
| 3 | 永久停用后不触发 | 同上，得到 `reason=GROUP_PERMANENTLY_DISABLED` |
| 4 | 暂停到期自动恢复（AC-4） | 应用暂停后注册被推到暂停之外；恢复时刻的 `PAUSE` 触发**自己**到点；之后闹钟被**重新武装到未来** |
| 5 | 系统事件重注册（FR-4.5） | `BOOT_COMPLETED` / `TIME_SET` 广播后确实重新注册，且注册时刻有效 |

**场景 2/3 为什么必须"重放"触发**：组一旦被暂停/停用，正确的调度器会立刻把闹钟重排到更晚的一天，于是暂停期间**根本不会有触发到达**，二次校验也就无从观察。而现实中危险的那一个触发，恰恰是状态变更**之前**就已经注册、仍然活在 `AlarmManager` 里的那个（取消注册存在竞态，PendingIntent 也可能熬过重启）。所以这两个场景显式重放它 —— 这正是 AC-5 要覆盖的竞态。

**场景 4 为什么断言"重新武装"而不是"紧接着就响"**：这不是偷懒，而是算术。PRD §5.3 把暂停在操作瞬间换算成绝对恢复时刻，而恢复时刻**永远是某天 00:00**；因此任何「覆盖了某次响铃」的暂停，其恢复时刻必然晚于那次响铃，那次响铃按规矩被跳过，下一次要等到约 24 小时之后。用亚分钟级暂停（测试必须这么造）永远构造不出"到期后紧接着响"。要真正看到那一声，需要跨午夜的、按天计的暂停，或者拨设备时钟——前者要等一天，后者会破坏其他时间相关验证，两者都留到 M7 的真机联调。**断言「恢复触发到点 + 闹钟被重新武装到未来」才是这个场景诚实且可核验的契约。**

**注册真实性**：除应用自己记的 `last_trigger_at`，还用系统侧的 `dumpsys alarm` 独立核对。实测片段：

```
RTC_WAKEUP #3: Alarm{314d78c type 0 origWhen 1790906460000 com.alarmhub.app}
  tag=*walarm*:com.alarmhub.app.action.ALARM_TRIGGER
  type=RTC_WAKEUP origWhen=2026-10-02 02:01:00.000 window=0 exactAllowReason=policy_permission
  Alarm clock:
    triggerTime=2026-10-02 02:01:00.000
    showIntent=PendingIntent{... startActivity}
  operation=PendingIntent{... broadcastIntent}
```

即主触发确实是 `setAlarmClock`（带 `Alarm clock` 段与 showIntent），冗余前沿是普通精确闹钟且**没有** `Alarm clock` 段 —— 与 TECH-STACK §4.2 的设计完全一致（否则状态栏图标会显示早 5 秒的错误时间）。

---

## 3. 过程中发现的问题

### 3.1 一个**真实缺陷**：`pauseUntil` 到点的恢复触发被当成了响铃触发（已修）

`AlarmReceiver` 原来对所有触发都走 PRD §5.4 的二次校验。但 `pauseUntil` 到点时：

- 这一刻的闹钟**要么仍在暂停**、要么它的下一次响铃已经排到了更晚；
- 于是「计算出的本次响铃时间 != 本次触发时间」这条**必然命中**；
- 校验结果落到 `RingDecision.Ring`（因为重算出的时间是未来某个时刻，和触发时刻不同，而我的分支把这种情况判成了「可以响」）。

后果：**暂停到期的那一刻会被当成一次响铃**。实测日志里留下了确凿证据：

```
02:12:12.588  AlarmHub/Receiver: alarm=20 kind=PAUSE RING VALIDATED at 1790993520000 (the ring flow itself lands at M4)
```

修复：把 `TriggerKind.PAUSE` 在进入校验**之前**分流出去——它的唯一职责是「暂停结束了，现在重算」，而不是判断该不该响。修完的日志是：

```
alarm=20 PAUSE trigger at 1790907132588 — recomputing now that the pause has ended
```

这个缺陷不是靠读代码看出来的：`PAUSE RING VALIDATED` 这行看起来完全正常，只有把「**什么算响**」写进断言（`kind=MAIN RING VALIDATED`）才发现 PAUSE 也被算了进去。M4 接入真实响铃流程时，这个分流的价值会立刻体现——否则每次暂停到期都会让手机响一声。

### 3.2 我的验收脚本本身不可靠 —— 五个假失败（本次最大的时间开销）

这部分值得完整记下来，因为**产品每一次都是对的，是验证工具在说谎**：

| 现象 | 真正原因 |
|---|---|
| 场景 2/3 报「没触发但也没记录静默退出」 | 上一轮场景留下的旧 PendingIntent 在下一场景触发，日志里出现**另一个 alarmId** 的 `ALARM_GONE`；正则只按关键字匹配，抓到了别人的行 |
| 场景 2 报「暂停中仍注册了下一天」 | 读到的是**上一版 APK** 的日志（改完没重装） |
| 场景 1 的 PRE 没到 | 注入时刻距分钟边界只剩 3 秒，而 PRE 是「准点前 5 秒」，该时刻**已经过去**，调度器正确地拒绝注册它 |
| 场景 4 报「到期后仍未注册」 | `adb` 返回 **CRLF**，夹在字段之间的 `\r` 让 `alarm id=20 registeredAtMillis=...` 这类多词模式全部静默失配 |
| 场景 5 报「注入后没注册」 | logcat 把长消息**折行**到下一行，字段被换行拆开；轮询时还会读到**上一条命令**的残留标记 |

结论：**不要再解析 adb 输出**。现在 `debug/DebugReceiver` 把结果原子地写到 `files/debug-result.txt`，脚本用 `run-as` 读文件、按 `key=value` 取值；只有「某个触发是否发生过」这类断言读 logcat，并把所有行**用空格拼接**以消除折行（应用不会在一条日志里换行）。重写后一次跑通 5/5。

这条教训比 M3 的代码本身更值钱：**验收脚本也会说谎，所以每条断言都要能对着原始日志核对**。上面五个"发现"里至少三个，只要当时多看一眼 `adb logcat` 的原始输出就能立刻排除，我却先入为主地当成产品缺陷去改产品代码。

### 3.3 一个需要写进文档的语义边界：**短于一天的暂停会跨过当天的响铃**

这是 PRD §5.3 的算术结果，不是实现缺陷，但很容易被当成 bug（我自己就先后误判了两次）：

- 暂停在**操作瞬间**就被换算成绝对恢复时刻 `pauseUntil`；
- 恢复时刻**永远是某天 00:00**（PRD §5.3 步骤 3：`cursor + 1 天的 00:00:00.000`）；
- 所以只要暂停覆盖了某次响铃，恢复时刻必然晚于那次响铃 → 那次响铃被跳过 → 下一次要等到第二天同一时刻。

实测（原始日志）：

```
03:16:15  alarm=54 next=2026-10-02T03:17Z          ← 注册在今天 03:17 响
03:16:19  alarm=54 floor=2026-10-02T03:17:20Z next=2026-10-03T03:17Z   ← 暂停 62s 覆盖了它
03:17:21  alarm=54 PAUSE trigger — recomputing now that the pause has ended
03:17:21  scheduled alarm=54 at=2026-10-03T03:17Z  ← 重新武装到明天，而不是丢失
```

**对真实用户不构成问题**：UI 只提供 1 / 2 / 3 / 7 天与自定义恢复日期（PRD FR-2.2），恢复时刻总是某个 00:00，而按天计的暂停本来就该跳过当天剩余与后续整天——正是用户按下"暂停"时想要的。这个边界只有用一个**亚分钟级**暂停才够得着，而那是测试为了缩短等待编造出来的构造。

M6 需要注意的一点：暂停面板按 PRD FR-2.4 必须显示「恢复时刻」与「下次响铃」。对于「恢复时刻早于今天这次响铃」的短暂停，面板应当把「下次响铃」显示成明天的时刻，而不是今天被跳过的那次——`PauseResolver` 返回的 `nextRingAt` 已经是对的，UI 直接照用即可。

### 3.4 一个真实的设计约束（不是 bug，但值得记录）

**Android 会冻结缓存进程，精确闹钟的投递可能被推迟数秒。** 实测中同一个闹钟：

- 进程存活时（应用刚被操作过）：`02:01:00.007` 触发，**偏差 7 毫秒**；
- 进程处于 cached 且被冻结时：`02:01:04.317` 触发，**偏差约 4.3 秒**。

这不影响「暂停了到底响不响」的正确性（二次校验与时间无关），但会影响 PRD §6 的「准点误差 < 2 秒」。这正是 M4 必须用**前台 Service 持有 MediaPlayer**（TECH-STACK §4.4）的原因之一，也是 M5 权限体检要求「电池无限制」的原因。验收脚本因此改成**轮询**而不是固定睡眠。

### 3.5 `LOCKED_BOOT_COMPLETED` 的既有缺口（已声明，未实现）

Manifest 里声明了 `LOCKED_BOOT_COMPLETED`，但应用**不是 direct-boot aware**（没有 `directBootAware="true"`，数据也不在 device-protected storage）。也就是说：设备重启后、用户首次解锁前，闹钟不会恢复。PRD 没有要求这一条，所以按「声明出来让缺口可见」处理，而不是假装支持。若要支持，需要把数据库迁到 device-protected storage 并给 Receiver 加 `directBootAware`。

---

## 4. M3 专用测试通道（M6 必须删除）

`alarm/../../debug/DebugReceiver.kt` + Manifest 里的 `com.alarmhub.app.permission.DEBUG`（`protectionLevel="signature"`）。

存在理由：M3 的验收是「系统是否在正确的分钟唤醒应用」，这件事没法用 H5 的 mock 证明（它根本不碰 `AlarmManager`），而真正的桥要到 M6 才有。所以这个 Receiver 提供了四件事：注入一个测试闹钟 / 读回存储与注册状态 / 暂停与停用 / 清空测试数据。

两条约束：

1. **签名级权限**：只有用本项目密钥签名的应用能发这些广播，第三方应用无法借它改写闹钟数据；
2. **非 debug 构建直接拒绝**：`onReceive` 第一句就检查 `BuildConfig.DEBUG`。

M6 落地 `AlarmHubPlugin` 的真实契约后，删除该 Receiver、该权限与 Manifest 里的声明。

---

## 5. 与 PRD / TECH-STACK 的对齐

- **requestCode 由 `alarm.id` 派生**（PRD M3.4）：`((id and 0x1FFFFF) shl 3) + kind.ordinal`，三种触发各占一个码，互不冲撞。仪测对 500 个 id × 3 种触发 = 1500 个码断言**两两不同**，且都在 24 位以内。
- **`last_trigger_at` 是新增的列**（不在 PRD §4.2 的表里）。理由：PRD §5.4 最后一条要比较「计算出的本次响铃时间」与「本次触发时间」，而 `PendingIntent` 不携带触发时刻、接收器又总是晚若干毫秒执行，所以必须把注册时刻记下来；放在数据库而非内存里，是为了让它在进程被杀或设备重启后依然有效——而那恰恰是这条校验存在的场景。它是调度记账，不是用户数据，因此**没有**进入领域模型。
- **`settings` 放数据库而不是 SharedPreferences**：PRD §4.3 说「键值对存储」，但它是需要有出厂默认值的用户数据，且桥要以一个对象整体读写；单行表让它保持原子，也让「数据只有一个真源」这条不变量成立。
- **没有 `INTERNET`**（PRD §6 / AC-15）：Manifest 里依然没有，`ShellSmokeTest` 继续守着。

---

## 6. 遗留与下一里程碑

| 项 | 说明 |
|---|---|
| 响铃流程本体 | `AlarmReceiver` 校验通过后**只打日志**，这是刻意的：把 M3 的调度判定单独验完，再让 M4 接入声音。M4 要在响铃结束时重排下一次（PRD §5.6 的一次性闹钟在此之前不能被删） |
| ~~`ringInProgress`~~ | **M4 的更正**：这里原本写着「M4 起由前台 Service 设置」。实际上 M4 没设它，于是这条检查永远为 `false`，成了一段"文档说能用、实际从未生效"的死分支。M4 已把该字段删除，改由 `RingController.isRinging` 提供——它由响铃页真正观察的那份状态推导而来，不可能与事实脱节。记录在此，因为它正是那种最该避免的陈旧注释 |
| `onceDate` 回填 | PRD §5.2 要求保存时把空 `onceDate` 解析为「下一个该时分的日期」并写回。`AlarmRule.Once(null)` 现在诚实匹配不到任何一天；回填属于写入路径，留到 M6 的 `saveAlarm` 桥接层 |
| 暂停恢复的 UI 反射 | PAUSE 触发到点后会重排，但**暂停状态本身**（`pauseUntil` 已过）不需要清理——`NextRingCalculator` 用 `max(now, pauseUntil)` 天然忽略已过期的暂停。M6 的 `listAlarms` 需要把这种行显示成「已恢复」而不是「已暂停」 |
| 时区 | 模拟器是 GMT、内置节假日数据是中国法定节假日，两者并存没有问题（规则按设备本地日历判定）。用户的真机是 Asia/Shanghai |
