# M2 完成报告 · 业务规则层

| 项 | 值 |
|---|---|
| 里程碑 | **M2 · 业务规则层（纯 Kotlin + 单元测试）** |
| 状态 | **已完成** —— 102 个单测全绿，其中 4 行是 PRD §5.3 的场景验证表 |
| 验证命令 | `cd android; gradle :app:testDebugUnitTest --console=plain` |
| 相关文档 | [PRD](PRD.md) §5 / §8 · [TECH-STACK](TECH-STACK.md) §4.1 / §4.8 · [DEVELOPMENT-PLAN](DEVELOPMENT-PLAN.md) §M2 |

---

## 1. 交付物

| # | 任务 | 产出 | PRD 依据 |
|---|---|---|---|
| M2.1 | 领域模型 | `domain/model/`：`Alarm` · `Group` · `Settings` · `RepeatType` · `WeekdayMask` · `AlarmRule` · `AlarmSchedule` · `PauseScope` | §4.1 / §4.2 / §4.3 |
| M2.2 | `RepeatRule` | `domain/schedule/RepeatRule.kt` —— 5 种重复规则的日期匹配 + 保存期校验 | §5.2 |
| M2.3 | `HolidayCalendar` | `domain/calendar/`（接口 + 数据 + 解析器）· `app/src/main/assets/holidays.json`（2025 / 2026）· `domain/json/MiniJson.kt` | §FR-6 |
| M2.4 | `NextRingCalculator` | `domain/schedule/NextRingCalculator.kt` —— 下次响铃 + 列表行状态 | §5.1 / §5.5 |
| M2.5 | `PauseResolver` | `domain/schedule/PauseResolver.kt` —— 「跳过 N 个响铃日」→ 绝对恢复时刻 | §5.3 |
| M2.6 | 单元测试 | `app/src/test/java/com/alarmhub/app/domain/` 共 8 个测试类 | §8 AC-1 / AC-2 / AC-5 |
| — | 额外 | `domain/schedule/RingTimeValidator.kt` —— 响铃时刻二次校验（PRD §5.4）。**M3 的 `AlarmReceiver` 直接调用它**，所以趁 M2 一起做掉，AC-5 的单测部分因此在本里程碑就能完成 | §5.4 |
| — | 额外 | `domain/TimeSource.kt` + `domain/DomainServices.kt` —— 时钟注入缝：算法本身只收 `now: Long`，生产调用点必须显式交出 `TimeSource`，于是全项目只有 `TimeSource.system` 一处读系统时间 | §6 可测性 |

**`domain/` 零 Android 依赖**：全包内没有任何 `import android.*` / `androidx.*` / `com.getcapacitor.*` / `org.json.*`（已用 grep 核验）。业务规则内部没有一处 `System.currentTimeMillis()` —— 它只出现在 `TimeSource.system` 这一个地方。

---

## 2. 验证证据

### 2.1 Gradle（计划里写明的验证方式）

```
$ cd android; gradle :app:testDebugUnitTest --console=plain
BUILD SUCCESSFUL
```

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `PauseResolverTest` | 20 | PRD §5.3 场景表 4 行 + AC-1 + 边界 |
| `NextRingCalculatorTest` | 18 | PRD §5.1 / §5.5 优先级 + 暂停叠加 |
| `RepeatRuleTest` | 14 | PRD §5.2 五种规则 + AC-2 国庆调休逐日核对 |
| `DomainModelTest` | 13 | 领域模型、星期掩码、**出厂默认值** |
| `RingTimeValidatorTest` | 13 | AC-5 竞态 + §5.4 全部分支 |
| `HolidayDataParserTest` | 12 | **随 APK 出货的数据文件**本身的正确性 |
| `MiniJsonTest` | 8 | 手写 JSON 读取器 |
| `DomainServicesTest` | 4 | 时钟注入缝：同一调用换一个 `TimeSource` 就换一个答案 |
| **合计** | **102** | 0 失败 / 0 错误 |

### 2.2 独立复跑：**在完全没有 Android classpath 的纯 JVM 上**

这是「单测能在纯 JVM 上跑」这条约束的直接证据 —— 不用 Gradle、不挂 `android.jar`、不挂 mockable android jar，只用 `kotlin-stdlib` + `junit`：

```
$ java -cp "<test-classes>;<main-classes>;junit-4.13.2.jar;hamcrest-core-1.3.jar;kotlin-stdlib-2.2.0.jar" \
      org.junit.runner.JUnitCore <8 个测试类>
JUnit version 4.13.2
......................................................................................................
Time: 0.199
OK (102 tests)
```

### 2.3 节假日数据真的进了 APK

```
$ gradle :app:assembleDebug --console=plain
BUILD SUCCESSFUL
$ # 解开 app-debug.apk
FOUND assets/holidays.json, 4157 bytes
```

数据文件单独校验过自洽性：每个调休日都确实是周六/周日；没有任何一天同时是节假日又是调休上班日；每个年份的 `rest` 展示分组恰好等于该年 `holiday` 清单（防止界面显示的假期和引擎判定的假期不一致）。

---

## 3. PRD §5.3 场景验证表 —— 实测结果

`PauseResolverTest` 里每一行都是一条断言，下面是实际跑出来的值（与 PRD 表格逐行对应）。

| 当前时间 | N | 跳过的响铃日 | 恢复时刻 | PRD 表 | 实测 |
|---|---|---|---|---|---|
| 周一 10:00（今早已响过） | 1 | 周二 | **周三 00:00** | ✅ | ✅ |
| 周一 06:00（今早还没响） | 1 | 周一 | **周二 00:00** | ✅ | ✅ |
| 周一 10:00 | 3 | 周二、三、四 | **周五 00:00** | ✅ | ✅ |
| 周五 20:00 | 1 | 下周一 | **下周二 00:00** | ✅ | ✅ |

> 表里的「每天 07:00」是前提：**只有当组内闹钟在 10:00 前都响完**，周一 10:00 那两行才成立。
> 若组里还有一个 13:30 的闹钟，周一当天就还剩一次响铃，于是周一本身成为被跳过的响铃日、恢复时刻提前一天。这是 PRD §5.3 步骤 3「该分组内所有启用闹钟」的必然结果，测试里用 `an alarm later today makes today itself a skipped ring day` 单独钉住了这个区别。

### 3.1 AC-2 国庆调休

| 断言 | 结果 |
|---|---|
| 2025-10-11（调休周六）是工作日 | ✅ `isWorkday = true` |
| 2025-10-01（法定假日的周三）不是工作日 | ✅ `isWorkday = false` |
| 工作日组从 10-10 20:00 起下一个响铃日是 10-11 | ✅ |
| 工作日组 10-01 全天不响，下一次响铃 10-09 07:00 | ✅ |
| 节假日组 10-01 08:00 响 | ✅ |
| 9/20–10/31 逐日核对 WORKDAY / HOLIDAY 互补（无一天同时命中或同时不命中） | ✅ 42 天 |

---

## 4. 过程中抓到的 4 个真问题

都不是靠读代码看出来的，是单测逼出来的。

### 4.1 `nextRingAt` 用了「严格大于」→ 恢复时刻当天 00:00 的闹钟被跳过（已修）

暂停的恢复时刻天然是**某天 00:00**，而 00:00 本身就是一个合法的响铃时刻。先前用 `nextRingAfter(resumeAt)`（严格晚于），会把「恢复当天 00:00 响的闹钟」跳过去、报成第二天。

改法是新增 `ScheduleMath.nextRingOnOrAfter()`，恢复时刻用「大于等于」语义；`ScheduleMath.nextRingAfter()` 保留给「从当前时刻往后找」的场景。
修完的效果：`nextRingAt` 可能等于 `resumeAt`，这才是正确的 —— 暂停一结束就响。

### 4.2 已有暂停没有约束「逐日检查」→ 暂停期内再次暂停会提前两天恢复（已修）

PRD §5.3 步骤 3 的括号（「仅 cursor == today 时需要这个时分判断」）容易被读成「只有今天才做时间比较」。但这样实现时：一个已暂停到周四 00:00 的闹钟，周一再次暂停 1 天，扫描会把**周二、周三（本来就在暂停中！）**当成刚被跳过的响铃日，于是恢复时刻落在周三 00:00 —— 用户以为还在暂停，闹钟却开始响了。

正确读法是把 `floor = max(now, existing pauseUntil)` 应用到**每一天**：

- 未暂停时 `floor` 就是 `now`，而 `now` 对以后每一天都已过午夜，所以时间比较实际上只在今天生效 —— 与括号描述完全一致；
- 已暂停时，被现有暂停覆盖的日子不会被当成响铃日，再次暂停于是干净地落在「旧暂停结束后的第 N 个响铃日」。

`ringsOn()` 的 KDoc 里写了完整推导。

### 4.3 mock 里的 TS 临时实现有一个同源 bug（已记录，未改）

`web/src/bridge/mock.ts` 的 `previewOf()` 对**每一天**都套用了 `ringAt(a, day) > now`：

```js
const ringsThatDay = affected.some((a) => {
  if (!dayMatchesRepeat(a, day)) return false
  return ringAt(a, day) > now      // ← 对未来的日子也成立，与 PRD 括号矛盾
})
```

后果正是 §4.2 描述的那类错误：PRD §5.3 表的第 4 行（周五 20:00、闹钟 07:00）在这个实现下会算错，因为 07:00 永远小于 20:00，算法会一天都找不到、恢复时刻报晚好几天。

**结论**：Kotlin 版（以 PRD 为准）是权威实现；mock 那份是 M1 的审阅道具，在 M6 接原生的同时删除，不投入修复。

### 4.4 两处属于**我的测试期望**写错（已改正，记下来避免下次重犯）

| 写错的地方 | 错的期望 | 正确值 | 为什么 |
|---|---|---|---|
| 调休周六的恢复时刻 | 10-11 00:00 | **10-12 00:00** | 恢复时刻是「被跳过的那天的次日 00:00」；10-11 全天都在暂停内 |
| 跨年场景的下次响铃 | 2026-01-05 07:00 | **2026-01-04 07:00** | 01-04 是调休上班日（周日），算法算对了，是我的期望错了 |
| 闰年一次性闹钟的「下次响铃」 | 2028-03-01 07:00 | **null** | 一次性闹钟只在 2028-02-29 响一次，之后没有下次响铃 |

---

## 5. 与 PRD / 契约的对齐

- `PauseResolution` 的字段与冻结契约 `PausePreview`（`resumeAt` / `skippedRingDays` / `nextRingAt`）逐一对应，M6 的 `AlarmHubPlugin.kt` 可以直接 projection 过去，不需要改前端。
- 列表行状态 `AlarmStatus`（`SCHEDULED` / `PAUSED` / `DISABLED` / `EXPIRED` / `NEVER`）与 `web/src/bridge/types.ts` 的同名联合类型一一对应。
- 星期掩码 bit0 = 周一 … bit6 = 周日，与 PRD §4.2 和契约一致，位序换算只写在 `ScheduleMath.weekdayIndex` 一处。
- 出厂默认值全部按 PRD §4.3：`defaultDeleteOnceAfterRing = true`、`defaultPauseDays = 1`，并有单测钉住。

### 5.1 一处**技术选型**的偏离（已在 TECH-STACK 补记）

**JSON 解析没有用 `org.json`。** 它在 Android SDK 里存在，但 JVM 单测里是 stub（会抛 "not mocked"），而 `domain/` 又不能碰 Android 类型；引入一个 JSON 依赖只为读一个扁平的节假日文件不划算。因此写了 `MiniJson`（约 130 行，支持对象/数组/字符串转义/数字/字面量，有 8 条单测覆盖畸形输入）。

好处是节假日文件格式完全由我们掌握，且「数据文件格式」这件事也被单测覆盖了。

---

## 6. 遗留与下一里程碑

| 项 | 说明 |
|---|---|
| 「启用闹钟」的定义 | `PauseResolver.previewForGroup` 用 `Alarm.contribributesToRingDays`（enabled && !expired && !permanentDisabled）过滤，对应 PRD §5.3 的「所有启用闹钟」。M3 的 Repository 取数时应保持一致 —— 若把已永久停用的闹钟也算作响铃日，暂停会多跳一天。 |
| 一次性闹钟的 `onceDate` 回填 | PRD §5.2 要求保存时把空 `onceDate` 解析成「下一个该时分的日期」并写回。`AlarmRule.Once(null)` 目前匹配不到任何一天（诚实做法），回填逻辑属于写入路径，放在 M3 的 Repository。 |
| `holidays.json` 的年度维护 | 目前覆盖 2025 / 2026（PRD FR-6.3 要求「当前年份及下一年份」）。2026 年底需按国务院通知替换文件，代码不动；缺失年份自动降级为周一~周五并在设置页提示（已实现并测试）。 |
| 响铃链路的使用方 | `RingTimeValidator` 已就绪，M3.5 的 `AlarmReceiver` 直接调用；`RingExitReason.ALARM_GONE` 对应「不响也不重排」，其余原因对应「静默退出并重排下一次」。 |
