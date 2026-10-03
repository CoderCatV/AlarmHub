# M6 完成报告 · 桥接真实数据

| 项 | 值 |
|---|---|
| 里程碑 | **M6 · 桥接真实数据** |
| 状态 | 完成。契约 **23 个方法**全部实现，模拟器上逐条实测留证（§2） |
| 相关文档 | [PRD](PRD.md) §4 / §5 / §8 · [DEVELOPMENT-PLAN](DEVELOPMENT-PLAN.md) §M6 · [M3 报告](M3-STATUS.md) §4/§6 · [M4 报告](M4-STATUS.md) · [M5 报告](M5-STATUS.md) |

---

## 1. 交付物

| # | 任务 | 产出 |
|---|---|---|
| M6.1 | **`AlarmHubPlugin.kt` 实现全部契约** | 23 个 `@PluginMethod`；`bridge/BridgeJson.kt` 是领域对象 ↔ JSON 的唯一转换点 |
| M6.2 | **注册插件** | M0 起就在 `MainActivity` 里（`registerPlugin` 必须在 `super.onCreate` 之前），本次未改 |
| M6.3 | **前端切真** | `web/src/bridge/index.ts`：容器内**默认走原生**，浏览器回落 mock；外加一个 `Proxy` 适配器解包三个数组方法（§3.1）。**契约文件与任何页面代码都没有动** |
| M6.4 | **保存即重算** | 插件里所有写操作统一经过 `write { … }`，写完立刻 `AlarmScheduler.recomputeAll()`（§4.1） |
| M6.5 | **浏览器降级** | `usingMock = FORCE_MOCK \|\| !isNativePlatform()`，`npm run web:dev` 里照旧跑 mock；`VITE_BRIDGE=mock` 可在容器内强制回 mock 以便审界面 |
| — | 节假日元信息 | `AlarmHubApp` 暴露 `holidayYears` / `holidaySource`（解析时抓一次），`getHolidayDataInfo` 用它们 + `coversYear(今年)` |
| — | 数据库可换文件名 | `AppDatabase.build(context, name = NAME)` —— 为了修 §3.4 那个**仪测删用户数据**的问题 |
| — | 仪测修正 | `MigrationTest` 改用 `migration-test.db`，并把「表是空的」这类断言改成「schema 可读」（§3.5） |

### 契约覆盖（23/23）

| 方法 | 怎么验的 | 证据 |
|---|---|---|
| `ping` | 设置页「运行环境」 | 版本 `1.0`、包名 `com.alarmhub.app` |
| `getHolidayDataInfo` | 直接调插件（**没有页面使用它**） | `{"years":[2025,2026],"source":"国务院办公厅关于…","degraded":false}` |
| `listGroups` | 分组管理页 | 未分组 内置 1 个闹钟 / 工作日 0 / 节假日 0 |
| `saveGroup` | 界面新建分组 | DB 出现 `id=4 sort_order=3 is_system=0` |
| `deleteGroup` | 界面两段确认删除 | DB 回到 3 个内置分组，列表同步 |
| `reorderGroups` | 直接调插件（页面的拖拽/键盘排序 M1 已验） | `sort_order` 4,1,2,3 → 0,1,2,3 |
| `listAlarms` | 主列表 | 行「07:00 · 已停用 · 单次 · 今天」 |
| `getAlarm` | 点行进编辑页 | 「编辑闹钟」/ 单次 / `2026-10-02` / 三个开关全开 —— 与 DB 行一致 |
| `saveAlarm` | 界面新建 + 保存；改震动后保存 | DB 行；`vibrate` 1 → 0 |
| `deleteAlarm` | 编辑页两段确认删除 | `select count(*) from alarms` → 0，列表回落空状态 |
| `setAlarmEnabled` | 点行内开关 | `enabled` 1 → 0 |
| `batchUpdateAlarms` | 直接调插件（**没有页面使用它**） | `{"affected":1}`，DB 的 `label` / `snooze_max_count` 同步变 |
| `previewPause` | 暂停面板 | 「将跳过 10月2日 / 自动恢复 明天 00:00 / 下次响铃 明天 07:00」 |
| `pause` | **真实触摸**点「暂停」 | §2.2 的完整证据 |
| `resume` | **真实触摸**点「恢复」 | `pause_until` 清空、`last_trigger_at` 回到当天 |
| `setPermanentDisabled` | 点分组整组开关 | `permanently_disabled=1`，行变「已停用」，主界面提示条出现 |
| `getSettings` | 设置页 | 默认暂停天数 / 贪睡时长 / 贪睡上限 / 主题 / 时间格式全部来自原生 |
| `updateSettings` | 直接调插件 | `default_delete_once_after_ring` 改回 1（顺便把我自己误触改掉的设置还原） |
| `countOnceAlarms` | 设置页提示 | 「共 1 个一次性闹钟，其中 0 个已标记」 |
| `bulkSetDeleteAfterRing` | 界面两段确认 | 「…其中 1 个已标记；刚才处理了 1 个」+ DB（§2.4） |
| `pickRingtone` | 编辑页点「铃声」行 | 系统选择器真的打开，标题「选择闹钟铃声」（`.shots/m6-ringtone-picker.png`） |
| `getPermissionStatus` | 体检页 | 七项（[M5-STATUS](M5-STATUS.md) §2.1） |
| `openPermissionSetting` | 体检页点「通知」 | 弹出系统运行时权限对话框（[M5-STATUS](M5-STATUS.md) §2.3） |

其中 `getHolidayDataInfo` 与 `batchUpdateAlarms` **没有任何页面调用**——不是没实现，是 M1 冻结契约时留了、后来页面没用上。见 §7 待确认第 1 条。

---

## 2. 验证证据（模拟器实测）

设备：`Google sdk_gphone64_x86_64`，sdk=36。以下命令与输出都是原样实测。

### 2.1 硬验收之一：界面操作真实落库

第一次落库的证据来自 M5 的首次引导（点「去体检」）：

```
COMMAND> node tools\cdp-probe.js --click-nth 1 ".sheet button"
  → clicked: "去体检"
COMMAND> adb shell run-as com.alarmhub.app sqlite3 databases/alarmhub.db "select permission_check_done from settings;"
1
```

### 2.2 硬验收之二：**在 App 界面真的点一次暂停** → 真落库 + 排程真被推迟

这一次用的是**真实触摸**（`adb shell input tap`，坐标由 DOM 的 `getBoundingClientRect()` 换算成设备像素；
WebView 屏幕区域 `[0,136]-[1080,2337]`，dpr=2.625），不是 DOM 的 `click()`：

```
# 触摸前
COMMAND> adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db "select id,name,pause_until,paused_at from alarm_groups where id=1;"
id  name  pause_until  paused_at
--  ----  -----------  ---------
1   未分组
COMMAND> adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db "select id,last_trigger_at from alarms;"
id  last_trigger_at
--  ---------------
1   1790924400000
adb shell date '+now = %Y-%m-%dT%H:%M:%S%z'      → now = 2026-10-02T05:41:10+0000
adb shell date -d @1790924400 -u …               → 2026-10-02T07:00:00+0000

# 真实触摸：分组标题上的「暂停」→ 面板里的「暂停」
  TAP CSS(295,196) → device(774,650)
  TAP CSS(304,751) → device(798,2107)

# 触摸后
COMMAND> adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db "select id,name,pause_until,paused_at,permanently_disabled from alarm_groups;"
id  name  pause_until    paused_at      permanently_disabled
--  ----  -------------  -------------  --------------------
1   未分组   1790985600000  1790919664474  0
2   工作日
3   节假日
COMMAND> adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db "select id,group_id,hour,minute,repeat_type,last_trigger_at from alarms;"
id  group_id  hour  minute  repeat_type  last_trigger_at
--  --------  ----  ------  -----------  ---------------
1   1         7     0       DAILY        1791010800000

adb shell date -d @1790985600 -u …   → pause_until    = 2026-10-03T00:00:00+0000
adb shell date -d @1791010800 -u …   → now registered = 2026-10-03T07:00:00+0000
```

**排程被推迟**这件事在系统侧也核对过（不只看应用自己写的列）：

```
COMMAND> adb shell dumpsys alarm | Select-String "com.alarmhub.app" -Context 0,2
    RTC_WAKEUP #21: Alarm{40302e0 … origWhen 1790985600000 … com.alarmhub.app}
      type=RTC_WAKEUP origWhen=2026-10-03 00:00:00.000 window=0 exactAllowReason=policy_permission …
    RTC_WAKEUP #32: Alarm{aa1b03f … origWhen 1791010795000 … com.alarmhub.app}
      type=RTC_WAKEUP origWhen=2026-10-03 06:59:55.000 window=0 exactAllowReason=policy_permission …
    RTC_WAKEUP #33: Alarm{787086a … origWhen 1791010800000 … com.alarmhub.app}
      type=RTC_WAKEUP origWhen=2026-10-03 07:00:00.000 window=0 exactAllowReason=policy_permission …
      Alarm clock:
        triggerTime=2026-10-03 07:00:00.000
```

三个注册（`pauseUntil` 恢复触发 00:00、冗余前沿 06:59:55、主触发 07:00:00 带 `Alarm clock:` 段）**全部**
从 10-02 移到了 10-03，10-02 一条不剩。

界面侧同步：`.shots/m6-paused-list.png`，行显示「1 天 1 小时后响铃」，分组按钮变「恢复」，
主界面出现「1 个分组处于暂停或停用状态」。

> 「真的不响」怎么算证明：这次暂停把**主触发本身**推迟到了暂停之后（上表），
> 也就是说暂停期内根本没有触发会到达 —— 加上 [M3 验收](M3-STATUS.md) §2.3 场景 2 已经验过
> 「暂停前注册、暂停后仍然活着的那一个触发会被 `GROUP_PAUSED` 静默拒绝」，
> 两条合起来就是 AC-3 的完整闭环。**没有为了等这一声而空等一天**，理由与 M3 §2.3 相同。

### 2.3 硬验收之三：界面新建的闹钟**到点真的响**

界面里两次保存建出了两个闹钟（一个 DAILY、一个 ONCE），然后把设备时钟拨到 06:58，等真实触发：

```
COMMAND> adb shell settings put global auto_time 0
COMMAND> adb shell date 100206582026.00        → Fri Oct  2 06:58:00 GMT 2026
COMMAND> adb logcat -c
… 等 135 秒 …
adb shell date '+clock now = %Y-%m-%d %H:%M:%S'   → 2026-10-02 07:00:15
COMMAND> adb logcat -d -s "AlarmHub/Receiver:I" "AlarmHub/Scheduler:I" "AlarmHub/Audio:I"
10-02 06:58:00.319  AlarmHub/Scheduler: scheduled alarm=1 at=2026-10-02T07:00Z[GMT] pre=2026-10-02T06:59:55Z[GMT] pauseUntil=null
10-02 06:58:00.408  AlarmHub/Scheduler: scheduled alarm=2 at=2026-10-02T07:00Z[GMT] pre=2026-10-02T06:59:55Z[GMT] pauseUntil=null
10-02 06:58:00.408  AlarmHub/Scheduler: recomputeAll: 2 stored, 2 registered, zone=GMT
10-02 06:59:55.014  AlarmHub/Receiver: alarm=1 PRE validated for 1790924400000; main trigger rings at the exact time
10-02 06:59:55.021  AlarmHub/Receiver: alarm=2 PRE validated for 1790924400000; main trigger rings at the exact time
10-02 07:00:00.062  AlarmHub/Receiver: ring started for alarm=2 at=1790924400006 snooze=false used=0/3
10-02 07:00:00.150  AlarmHub/Audio: ringing from content://settings/system/alarm_alert fadeIn=5s
10-02 07:00:00.217  AlarmHub/Receiver: alarm=1 kind=MAIN SILENT EXIT reason=ALREADY_RINGING scheduledAt=1790924400000 now=1790924400199
10-02 07:00:00.276  AlarmHub/Scheduler: scheduled alarm=1 at=2026-10-03T07:00Z[GMT] pre=2026-10-03T06:59:55Z[GMT] pauseUntil=null
```

| 断言 | 实测 |
|---|---|
| 界面新建的闹钟被真实注册 | `scheduled alarm=2 at=2026-10-02T07:00Z` |
| 准点精度 | 注册 `07:00:00.000`、起响 `at=1790924400006` ⇒ **6 ms** |
| 真的响了 | `ring started for alarm=2` + `ringing from content://settings/system/alarm_alert` |
| 响铃页真的置顶 | `topResumedActivity=com.alarmhub.app/.ring.RingActivity`，`.shots/m6-ui-alarm-ringing.png` |
| 状态机 | `debug-result.txt`：`ringing=true alarmId=2 … audioPlaying=true alarmStreamVolume=7` |
| 同一时刻的第二个闹钟 | `SILENT EXIT reason=ALREADY_RINGING` 并按 PRD §5.4 重排到次日 —— 顺带验了二次校验 |
| 响铃后删除 | `dismiss` → `stillExists=false`；DB 只剩 DAILY 那条，且 `last_trigger_at` 已是次日 |

**验证后已还原设备时钟**：`adb shell settings put global auto_time 1` → 实测回到 `2026-10-02 05:45:07 GMT`，
并触发 `TIME_SET` 全量重算（`origWhen` 重新落回当天 07:00）。这也是 FR-4.5.3 的一次顺带实测。

### 2.4 硬验收之四：批量转换的数量与界面一致

```
# 界面：设置 → 「把现有的一次性闹钟都设为响铃后删除」
  「共 1 个一次性闹钟，其中 0 个已标记」
  点「一键处理」→ 变「确认处理 1 个？」→ 再点
  「共 1 个一次性闹钟，其中 1 个已标记；刚才处理了 1 个」

COMMAND> adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db "select id,repeat_type,delete_after_ring from alarms;"
id  repeat_type  delete_after_ring
--  -----------  -----------------
1   DAILY        1
2   ONCE         1
```

界面说的「1 个」= 只有那条 `ONCE` 被改；`DAILY` 那条**没有**被误改（它本来就是 1，但转换的 SQL 明确
只碰 `repeat_type='ONCE'`）。

### 2.5 回归：三个基线数字一个都没变

```
JVM 单测       102 tests, 0 failures
仪测           28 tests = 2 迁移 + 17 数据层 + 6 调度 + 3 冒烟, 0 failures
调度验收       tools/m3-acceptance.ps1 → 5/5 scenarios passed
```

完整验收输出：[`.shots/m6-post-m3-acceptance.txt`](../.shots/m6-post-m3-acceptance.txt)。

---

## 3. 过程中发现并处理的问题

### 3.1 Capacitor 的插件调用**无法** resolve 成裸 JSON 数组（平台约束 + 适配器）

契约里 `listGroups` / `listAlarms` / `getPermissionStatus` 都返回数组。原生侧 `call.resolve(...)` 只能接
`JSObject`：

```
MessageHandler.java:117    data.put("data", successResult);      // 信封永远是对象
native-bridge.js:965       storedCall.resolve(result.data);      // JS 直接把 result.data 交给 promise
```

所以原生这三个方法返回 `{groups: …}` / `{alarms: …}` / `{items: …}`，由
`web/src/bridge/index.ts` 里一个 `Proxy` 解包。这样做的代价是多了一层适配；收益是**契约与页面一行都不用改**，
正是 M6.3「契约不变，页面代码不动」要的结果。契约里那三个方法仍写成返回数组——真实实现由适配器补上。

### 3.2 `PluginCall.getLong` 对 `Int` 类型的 extra 返回**默认值**（真陷阱，已绕开）

`PluginCall.java:196`：

```java
public Long getLong(String name, Long defaultValue) {
    Object value = this.data.opt(name);
    if (value instanceof Long) return (Long) value;
    return defaultValue;          // ← Int 走这里
}
```

而 JS 的小整数经 org.json 解析成 `Integer`。也就是说 `call.getLong("id")` 读 `{id: 2}` 会拿到 **null**。
这和 [M4-STATUS](M4-STATUS.md) §4.6 的 `--ei` / `--el` 是**同一类错误换了一层**，处理方式也一样：
`BridgeJson.num()` 是唯一被允许读数字的地方，它接受 `Long / Int / Short / Byte / Double / String`。

### 3.3 `JSObject.put(key, null)` 会**删掉**这个键（真陷阱，已绕开）

`JSONObject.put(name, null)` 的语义是 `remove(name)`。于是 JS 侧拿到的是 `undefined` 而不是 `null`，
而前端的判断是 `alarm.nextRingAt !== null` —— 对 `undefined` **成立**，于是暂停中的闹钟会被算出
`NaN` 倒计时。`putNullable()` 用 `JSONObject.NULL` 这个真正的哨兵解决。

### 3.4 仪测会**删掉用户的数据库**（真数据丢失，已修）

`MigrationTest` 的 `@After` 是：

```kotlin
listOf(dbFile, -wal, -shm).forEach { it.delete() }
```

而 `dbFile` 原来就是 `AppDatabase.NAME`，也就是**应用正在用的那个库**。M4 时这没人在意，因为除了播种，
没有任何路径会往库里写用户数据；**M6 之后界面能真的建闹钟了，这条 `@After` 就变成了「跑一次仪测删一次闹钟」**。

本次实测到的证据：

```
# 跑仪测之前
COMMAND> adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db "select id,repeat_type,delete_after_ring from alarms;"
id  repeat_type  delete_after_ring
--  -----------  -----------------
1   ONCE         1
# 跑 MigrationTest 之后
COMMAND> adb shell run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db "select id,repeat_type,delete_after_ring from alarms;"
（空）—— 文件被重新创建，只剩播种结果；permission_check_done 也回到 0
```

修法：`AppDatabase.build(context, name = NAME)` 加一个默认参数，仪测用 `migration-test.db` 这个草稿文件。
生产路径完全不变（默认值就是 `NAME`）。修完实测：同样的仪测跑完，闹钟还在。

### 3.5 `MigrationTest` 里「表应该是空的」这条断言（已改成断言 schema）

```
java.lang.AssertionError: no alarm exists yet expected null,
  but was:<AlarmEntity(id=1, groupId=1, hour=7, minute=0, …, lastTriggerAt=1790924400000, …)>
```

这条断言断言的是**用户有什么数据**，不是 schema。M4 已经因为同一原因把「分组应该是空的」删掉了
（[M4-STATUS](M4-STATUS.md) §4.8），却把闹钟那半句留了下来——因为当时「界面根本建不出闹钟」这个假设还成立。
M6 让它第一次真的失败。现在改成「`SELECT snooze_count` 能 prepare 出来」，与被测数据无关。

（连带发现：`settings` 那一半也不能断言「有行」——草稿库是全新的，应用启动时的播种只播到生产库上。）

### 3.6 与 M1 mock 的一处**刻意偏离**：`until` 形状的 `nextRingAt`

mock 的 `previewPause({until})` 用「**严格晚于** until」找下次响铃；原生用「**等于或晚于**」。
理由是 PRD §5.3 step 3 把恢复时刻定在某天 `00:00`，而 `00:00` 本身就是一个响铃时刻——用严格晚于会把
一个 00:00 的闹钟整整藏一天。mock 是临时实现、已随 M6 停用，这里记下来是因为它是**唯一**一处
两个实现语义不同的地方。

### 3.7 `call.reject(String, Throwable)` 不存在

Capacitor 的重载只收 `Exception`，而 `async` 帮助函数刻意捕 `Throwable`（否则 `NoClassDefFoundError`
会让 promise 永远 pending，界面表现为「按钮一直转」）。于是用 `reject(String)`，异常本身照旧写进 logcat。

### 3.8 `pickRingtone` 的选择器会**说谎**（已修）

`ringtoneUri == null` 的含义是「跟随系统默认铃声」，是一个**会响**的选择。但选择器把
`EXTRA_RINGTONE_EXISTING_URI` 为空渲染成「**None / Currently set**」，也就是宣称这条闹钟是静音的
（`.shots/m6-ringtone-picker.png` 就是修之前的样子）。改成把默认铃声 URI 传进去，选择器显示的就是
真正会播的那个；用户选中它时返回值又映射回 `null`（「跟随默认」）。

---

## 4. 与计划 / 既有文档的对齐

### 4.1 一条**没有照做**的计划项：DebugReceiver 没有删（划掉 M3-STATUS §4 的说法）

[M3-STATUS](M3-STATUS.md) §4 原文写着「**M6 落地 `AlarmHubPlugin` 的真实契约后，删除该 Receiver、该权限与
Manifest 里的声明**」。

~~M6 落地 `AlarmHubPlugin` 的真实契约后，删除该 Receiver、该权限与 Manifest 里的声明。~~

**更正（M6）**：**没有删**，而且这次是刻意保留的，理由有两条：

1. `tools/m3-acceptance.ps1` 的五个场景全部依赖它（注入测试闹钟、重放过期触发、读回注册状态）。
   删掉它，「调度验收 5/5」这个用户要求复现的基线就**无法复现**。
2. M7.1 还要在模拟器上重走一遍可在模拟器验证的 AC，同一套通道仍然需要。

保留的形态与 M4 一致：只存在于 `src/debug/`，release 构建里**完全不存在**（`ShellSmokeTest` 与本仓库的
release 构建核对过）。它不是"S6 没做完"，是**把删除推迟到 M7 收尾**，写在 §7 待确认第 2 条。

### 4.2 M3-STATUS §6 的 `onceDate` 回填：已按承诺在 M6 落地

M3 写「回填属于写入路径，留到 M6 的 `saveAlarm` 桥接层」。现在在 `AlarmHubPlugin.backfillOnceDate()`：
`ONCE` 且 `onceDate` 为空或已过去时，解析为「今天的该时分（若还没到）否则明天」，写回库。
判定用的比较与 mock 的 `rollForwardIfPast` 一致，所以编辑时看到的日期就是存下去的日期。

### 4.3 M3-STATUS §6 的「已恢复」显示

M3 记「`listAlarms` 需要把这种行显示成「已恢复」而不是「已暂停」」。契约的 `AlarmStatus`
**没有**「已恢复」这个状态，实际行为是：`NextRingCalculator.statusOf` 只有在 `pauseUntil > now` 时才报
`PAUSED`，过期的暂停直接走 `nextRing`（`floorOf` 里与 `now` 取大）⇒ 报 `SCHEDULED`。
也就是说「不会被显示成已暂停」这条已经满足，只是**没有**一条专门的「已恢复」文案——那需要动契约，没动。

### 4.4 M4-STATUS §6 的悬置项：`SYSTEM_ALERT_WINDOW` 已由 M5 决定

M4 写「未申请。M5 决定是引导开启还是降级为『点通知看页面』」。M5 的决定：**声明 + 引导**，
并把非 MIUI 的「后台弹出界面」映射到它（[M5-STATUS](M5-STATUS.md) §3.2）。授予之后解锁状态能否自动全屏，
仍是真机待验项。

### 4.5 契约**没有**变

`web/src/bridge/types.ts` 与 `web/src/bridge/mock.ts` 本次**零改动**。所有形状差异都在
`web/src/bridge/index.ts` 的适配器里收口（§3.1）。

---

## 5. 文件范围（替代 `git diff --stat`）

### 5.1 这个工作区**不是 git 仓库**

```
COMMAND> git status --porcelain=v1
fatal: not a git repository (or any of the parent directories): .git
（git log / git branch 同样失败；工作区根目录下没有 .git）
```

所以 `git diff --stat` 在这里产生不了。替代物是两份东西：

1. **哈希清单**：`.shots/file-scope.txt` —— `web/src/**` 每个文件的字节数与 SHA-256，
   外加本次改动的 android 文件；
2. **本节的逐文件归属表**：哪个文件属于 M5、哪个属于 M6、为什么。

> 强烈建议在这里 `git init` 一次。一个已经走到 M7 的项目没有版本控制，是本次交付里最不值当的风险：
> 之后任何一次「改坏了想回退」都只能靠人肉记忆。

### 5.2 M6 改动的文件（**只有这 5 个**）

| 文件 | 为什么 |
|---|---|
| `android/…/bridge/AlarmHubPlugin.kt` | M0 的 ping-only 实现 → 契约全量实现 |
| `android/…/bridge/BridgeJson.kt` | **新增**：领域 ↔ JSON 的唯一转换点 |
| `android/…/AlarmHubApp.kt` | 暴露 `holidayYears` / `holidaySource` 供 `getHolidayDataInfo` |
| `android/…/data/db/AppDatabase.kt` | `build(context, name)` 默认参数（§3.4） |
| `web/src/bridge/index.ts` | 容器内默认走原生 + 数组信封适配器 |
| `android/app/src/androidTest/…/MigrationTest.kt` | 仪测修正（§3.4 / §3.5）；**不进 APK** |

### 5.3 M5 改动的文件（与 M6 无关，列出来是为了让你能一眼分开看）

| 文件 | 为什么 |
|---|---|
| `android/app/src/main/AndroidManifest.xml` | 两条权限 + `<queries>` |
| `android/…/permissions/PermissionModels.kt`（新） | `PermissionKey` / `PermissionStatus` |
| `android/…/permissions/PermissionInspector.kt`（新） | 七项检测 |
| `android/…/permissions/PermissionSettingsLauncher.kt`（新） | 候选 Intent 序列 + 兜底 |
| `android/app/src/debug/…/DebugReceiver.kt` | 新增 `permissions` / `permissionjump` 诊断命令 |
| `web/src/state/store.ts` | 权限探针的共享状态 + 回前台重探 |
| `web/src/components/PermissionBanner.vue`（新） | 主界面警示条 |
| `web/src/components/PermissionGuide.vue`（新） | 首次启动引导 |
| `web/src/pages/AlarmListPage.vue` | 挂上警示条与引导（+ 一个 `openPermissions` 事件） |
| `web/src/pages/PermissionsPage.vue` | 改用 store 里的共享探针；修掉 `**任何**` 文案 |
| `web/src/App.vue` | 把上一条事件接到 `permissions` 路由 |

### 5.4 可以当场核对的断言：`web/src/pages/**` 里**没有** M6 的任何东西

M6 只引入了一个容器内的适配器。对页面目录做标识符检索（原样实测）：

```
COMMAND> Select-String -Path web\src\pages\*.vue -Pattern 'withUnwrappedLists|LIST_ENVELOPE_KEYS|registerPlugin'
（无匹配）

COMMAND> Select-String -Path web\src\pages\*.vue,web\src\App.vue -Pattern 'PermissionBanner|PermissionGuide|criticalMissingPermissions'
web/src/pages/AlarmListPage.vue:8: import PermissionBanner from '../components/PermissionBanner.vue'
web/src/pages/AlarmListPage.vue:9: import PermissionGuide from '../components/PermissionGuide.vue'
web/src/pages/AlarmListPage.vue:12: criticalMissingPermissions,
web/src/pages/AlarmListPage.vue:156: <div v-if="criticalMissingPermissions.length" class="perm">
web/src/pages/AlarmListPage.vue:157: <PermissionBanner :items="criticalMissingPermissions" @open="emit('openPermissions')" />
web/src/pages/AlarmListPage.vue:223: <PermissionGuide
```

也就是说：页面里出现的东西**全部**是 M5 的（警示条、引导、共享探针），**没有一处**是 M6 的接线。
M6.3 的纪律「契约不变，页面代码不动」在文件级别成立。

（`usingMock` 在 `AlarmListPage` / `SettingsPage` 里本来就出现——那是 M1c 的「演示数据」角标，
M6 只是把它从「构建开关」改成「浏览器回落」，页面一行都不用动。）

---

## 6. 截图

| 文件 | 内容 |
|---|---|
| `.shots/m6-paused-list.png` | 真实触摸暂停之后的主界面：行「1 天 1 小时后响铃」、分组按钮变「恢复」、顶部提示 1 个分组暂停中 |
| `.shots/m6-ui-alarm-ringing.png` | **界面新建的闹钟**到点响铃时的响铃页（真实 `AlarmManager` 触发，非调试通道） |
| `.shots/m6-ringtone-picker.png` | 编辑页点「铃声」打开的系统选择器（§3.8 修之前的样子） |
| `.shots/m6-post-m3-acceptance.txt` | 改动之后的调度验收完整输出（5/5） |
| `.shots/file-scope.txt` | §5 的哈希清单 |

---

## 7. 待用户确认

| # | 事项 | 现状 | 备选 |
|---|---|---|---|
| 1 | 契约里 `getHolidayDataInfo` 与 `batchUpdateAlarms` **没有任何页面调用** | 已实现、已实测，但属于死 API 面 | ① 保留（M7 的「关于/节假日」页可能用得上）；② 从契约里删掉，减少一份要维护的表面 |
| 2 | `DebugReceiver` 按 M3 计划应在 M6 删除 | **没删**，理由见 §4.1（它撑着 5/5 这条基线） | ① 留到 M7 收尾一起删；② 现在就删，并接受 `tools/m3-acceptance.ps1` 失效 |
| 3 | 本次**没有新增测试用例** | 单测 102 / 仪测 28 与改动前**完全一致** | 为桥接层补一组仪测（需要一个真实 Application + Room，属于集成测试） |
| 4 | 仪测会与被测应用共用进程与数据目录 | 本次修掉了「删库」这一条（§3.4），但例行的 `AppDatabase.build(context)` 在仪测里仍然指向生产库名 | 更彻底的做法是给仪测一个独立的 Application 或 `Context`，属于 M7 打磨 |
| 5 | `VITE_BRIDGE=mock` 这个逃生开关 | 保留（容器内也能强制回 mock 审界面） | 删掉，只留「浏览器 = mock」这一条规则 |
| 6 | 长期：这个工作区**没有 git** | 见 §5.1 | 现在就 `git init` 并提交一个基线 |

---

## 8. 遗留与下一里程碑

| 项 | 说明 |
|---|---|
| 「到点真的响」的完整回归 | 本次为了不空等，用「拨设备时钟到 06:58 再等 2 分钟」的办法验的。M7 可以把「界面新建 → 到点响」做成 `tools/` 里的一个可重跑脚本（含拨钟与还原） |
| 契约的死 API | §7 第 1 条 |
| 仪测的数据隔离 | §7 第 4 条 |
| 真机 | M5 的「跳转命中」与 M7.2 的 ROM 相关项，见 [MACHINE-CHECKLIST.md](MACHINE-CHECKLIST.md) |
| 200 个闹钟的滚动（AC-17） | 模拟器上未做；`recomputeAll` 是全量清扫，200 行时值得实测一次耗时 |
