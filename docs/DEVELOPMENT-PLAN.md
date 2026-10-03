# AlarmHub 开发计划

| 项 | 值 |
|---|---|
| 文档版本 | v1.0 |
| 对应需求 | [docs/PRD.md](PRD.md) v1.0（已确认，含 D1~D9） |
| 对应技术栈 | [docs/TECH-STACK.md](TECH-STACK.md) v1.0（已确认，含 T1~T6） |
| 文档状态 | **待确认**——确认后进入 M1（前端页面效果） |
| 关键事实 | 工作目录已有端到端验证过的 Capacitor 8 壳工程；闹钟逻辑从零开始 |

---

## 1. 计划原则

### 1.1 每个里程碑都必须能在模拟器上跑起来并截图

不设「全部写完再联调」的阶段。**每个里程碑结束时，App 必须能被 `adb install` 进去、打开、看到该里程碑的成果。** 理由是技术栈里有三个集成风险（Kotlin×AGP、KSP×Room、Capacitor 桥），任何一个在后期才暴露都会导致返工。M0 专门用来一次性清掉它们。

### 1.2 前端先行，并冻结接口契约

你的流程要求「先做一个前端页面效果」再正式开发，这与工程上的最优顺序一致——但有个前提必须处理：**前端在 M1 阶段还没有真实数据**。做法是：

1. M1 先写出 `web/src/bridge/` 的 **TypeScript 接口契约**（`AlarmHubBridge` 类型定义），它就是这个项目跨语言 API 的唯一契约；
2. M1 提供一个 **mock 实现**满足该契约，让界面能跑起来看效果；
3. M6 用真实的 Capacitor 插件实现同一个契约，**前端代码一行不改**。

这样「先看前端效果」不会变成「先写一遍假代码再重写一遍」。

### 1.3 不按工时估算

这是一个 AI 驱动的开发过程，工时估算没有参考价值（不存在「今天写 4 小时」的概念）。计划只给**规模评级**（S/M/L）和**里程碑之间的依赖顺序**，进度以「里程碑是否通过验证」衡量。

### 1.4 业务规则先于界面接线

`domain/` 层（重复规则、响铃日换算、暂停判定）是 PRD 里 AC-1 / AC-2 / AC-5 的载体，也是最容易写错的部分。它被安排在 M2 单独完成并全量单测，**不依赖任何 UI**。这样 PRD §5.3 那张场景验证表可以在没有界面的情况下先证明是对的。

---

## 2. 里程碑总览

| # | 里程碑 | 规模 | 依赖 | 结束标志 |
|---|---|---|---|---|
| **M0** | 工程基线与集成风险清除 | M | — | 模拟器上打开 App，看到由 Kotlin 返回的信息 |
| **M1** | **前端页面效果** ← 你的确认点 | L | M0 | 浏览器 + 模拟器截图，7 个页面全部可交互 |
| M2 | 业务规则层（纯 Kotlin + 单测） | M | M0 | 单测全绿，AC-1 / AC-2 通过 |
| M3 | 数据层与调度引擎 | L | M2 | logcat 实测闹钟准点触发、暂停后不触发 |
| M4 | 响铃链路（原生） | L | M3 | 模拟器实测完整响铃流程 |
| M5 | 权限体检与 HyperOS 适配 | M | M0 | 检测准确、跳转命中或有兜底 |
| M6 | 桥接真实数据 | M | M1, M3, M4, M5 | 界面操作真实落库，暂停后真的不响 |
| M7 | 端到端联调与真机验证 | M | M6 | PRD §8 的 17 条 AC 逐条确认 |

**关键路径**：M0 → M2 → M3 → M4 → M6 → M7。
**可并行**：M1 与 M2/M3 无依赖，但按你的流程 M1 先做并等你确认；M5 只依赖 M0，可以在 M2~M4 期间穿插。

```
M0 ──┬─→ M1（前端效果）──────────────┐
     │        ↑ 你确认                │
     ├─→ M2 ─→ M3 ─→ M4 ─────────────┼─→ M6 ─→ M7
     └─→ M5 ────────────────────────┘
```

---

## 3. 里程碑详情

### M0 · 工程基线与集成风险清除

**目标**：把「构建、桥、前端产物」三个集成风险一次性清掉。此后每一步都能装进模拟器看见。

#### 任务

| # | 任务 | 说明 |
|---|---|---|
| M0.1 | **版本锁定** | 实际解析一次依赖，把真实的 Kotlin / KSP / Room / Coroutines 版本回填 [TECH-STACK.md](TECH-STACK.md) §6，锁定后不再变动 |
| M0.2 | **工程身份迁移** | `com.dsh.helloshell` → `com.alarmhub.app`；应用名 → 「闹钟坞」；涉及 `android/app/build.gradle`（namespace + applicationId）、Java 源码目录、`MainActivity`、`ShellSmokeTest`、`capacitor.config.json`、`package.json` |
| M0.3 | **接入 Kotlin** | 根 `build.gradle` 加 KGP classpath；app 模块加 `kotlin-android`；`MainActivity.java` → `MainActivity.kt` |
| M0.4 | **处理既有技术债** | 上调 `force kotlin-stdlib` 版本（现为 1.8.22，与 Kotlin 2.3 冲突），并重跑冒烟仪测确认「espresso/cordova 重复类」问题没有回归 |
| M0.5 | **前端构建链** | 建 `web/`（Vue 3 + Vite + TS），`build.outDir` 指向 `www/`；`npm run build` → `npx cap sync android` 打通 |
| M0.6 | **最小桥闭环** | 写一个 `AlarmHub.ping()`：Kotlin 侧返回应用版本、SDK 版本、构建类型；H5 页面上显示出来 |

#### 验证方式

```powershell
# 1. 前端构建 + 同步
npm run build; npx --no-install cap sync android
# 2. 原生构建
cd android; gradle assembleDebug --console=plain
# 3. 装机并打开
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.alarmhub.app/.MainActivity
# 4. 截图确认页面显示了 Kotlin 返回的信息
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png .shots/m0.png
# 5. 冒烟仪测（gradlew connectedAndroidTest 在沙箱里跑不了）
adb shell am instrument -w -e class com.alarmhub.app.ShellSmokeTest `
    com.alarmhub.app.test/androidx.test.runner.AndroidJUnitRunner
```

#### 产出物

- 可构建、可安装、桥已打通的空工程
- `.shots/m0.png` 截图
- [TECH-STACK.md](TECH-STACK.md) §6 的锁定版本表

#### 风险

| 风险 | 应对 |
|---|---|
| Kotlin 2.3.x 与 AGP 8.13 兼容性警告或报错 | 回退 Kotlin 2.2.x |
| `kotlin-stdlib` force 调整后重复类问题回归 | 立即重跑冒烟仪测；若回归则改用 `exclude` 而非 `force` |
| KSP 版本与 Kotlin 不匹配 | KSP 已独立版本号（2.3.x），按镜像实际配对 |

---

### M1 · 前端页面效果 ← **你的确认点**

**目标**：在浏览器和模拟器上看到一个完整、可点击、视觉成型的 AlarmHub，数据来自 mock。

> **这一步就是你要的第 7 步。做完后停下来等你确认，确认通过才进入 M2。**

#### 任务

| # | 任务 | 说明 |
|---|---|---|
| M1.1 | **冻结接口契约** | 写 `web/src/bridge/types.ts`：`AlarmHubBridge` 全部方法签名与数据结构（见 §4）。这是跨语言唯一契约 |
| M1.2 | **mock 实现** | 满足同一契约的内存实现；含演示数据：3 个分组（未分组 / 工作日 / 节假日）、8 个闹钟、2 个已过期、1 个处于暂停中的分组 |
| M1.3 | **设计规范** | 配色（深色/浅色两套）、字号阶梯、间距、圆角、图标风格。目标是贴合 HyperOS 观感 |
| M1.4 | **主列表页** | 顶部当前时间 + 全局状态条；按分组分节（节头含组名、标识色、组开关、暂停按钮）；闹钟行含大字时间、标签、重复摘要、倒计时、开关；底部「已过期」区；暂停/停用灰显 |
| M1.5 | **暂停面板**（底部弹层） | 快捷 1 / 2 / 3 / 7 天 + 自定义日期；**恢复时刻预览**与「下次响铃」预览；确认/取消 |
| M1.6 | **新建 / 编辑闹钟页** | 滚轮式时/分选择器（自研）、重复规则（单次/每天/自定义周几/法定工作日/法定节假日）、标签、分组选择、铃声、震动、渐强、贪睡、响铃超时、「响铃后删除」（仅一次性闹钟显示） |
| M1.7 | **分组管理页** | 分组列表 + 拖拽排序 + 新建/重命名/改色/删除（删除时提示将影响多少个闹钟） |
| M1.8 | **设置页** | 按 PRD §FR-4.4 逐项；含批量转换入口（显示将影响的数量）与权限体检入口 |
| M1.9 | **权限体检页** | 逐项状态卡片（✅/❌）+ 一键跳转按钮 + 兜底指路文案 |

#### 验证方式

- 浏览器截图（深浅色各一套）——快速迭代用
- 模拟器截图（深浅色各一套）——真实效果
- 200 条假数据滚动流畅性检查
- 全部页面可点击走通，无 JS 报错（`adb logcat -s "Capacitor/Console:V"`）

#### 产出物

- `.shots/m1-*.png` 截图集
- 可交互的界面原型（mock 数据）
- 冻结的 `AlarmHubBridge` 契约

#### 这一步需要你做的

看截图 / 装 APK 实际点一遍，确认：**视觉风格、页面结构、交互流程、信息密度**是否符合预期。有意见在这一步提，改动成本最低。

---

### M2 · 业务规则层（纯 Kotlin + 单元测试）

**目标**：把 PRD §5 的全部业务规则实现成零 Android 依赖的纯 Kotlin，并用单测证明正确。

#### 任务

| # | 任务 | 说明 |
|---|---|---|
| M2.1 | **领域模型** | `Group` / `Alarm` / `Settings` 的领域对象（与 Room 实体分离） |
| M2.2 | `RepeatRule` | 5 种重复规则的日期匹配（PRD §5.2） |
| M2.3 | `HolidayCalendar` | 接口 + JSON 实现；内置 2025 / 2026 中国法定节假日与调休数据，放 `assets/holidays.json`；数据缺失时降级为「周一~周五」 |
| M2.4 | `NextRingCalculator` | 下次响铃时间计算（PRD §5.1） |
| M2.5 | `PauseResolver` | 「跳过 N 个响铃日」→ 绝对恢复时刻的换算（PRD §5.3） |
| M2.6 | **单元测试** | 覆盖 PRD §5.3 场景表的全部 4 行 + AC-1 + AC-2 + 边界（未选任何星期、跨年、闰年、暂停期内再次暂停） |

#### 关键约束

**`domain/` 包内不出现任何 Android 类型**，时间通过参数注入而非 `System.currentTimeMillis()`。这是单测能在纯 JVM 上跑的前提。

#### 验证方式

```powershell
cd android; gradle testDebugUnitTest --console=plain
```

全绿，且测试用例数量与 PRD §5.3 场景表逐行对应。

#### 产出物

- `domain/` 包 + `assets/holidays.json`
- 单测报告
- **PRD §5.3 那张验证表的实测结果**（这是本项目最核心的正确性证明）

---

### M3 · 数据层与调度引擎

**目标**：数据能存，闹钟能被系统按时唤醒，暂停后确实不触发。

#### 任务

| # | 任务 | 说明 |
|---|---|---|
| M3.1 | **Room 接入** | KSP 插件、实体 / DAO / Database、WAL 模式、schema 导出目录 |
| M3.2 | **首次启动种子数据** | 创建「未分组」「工作日」「节假日」三个内置分组，并写入默认设置（含 `defaultDeleteOnceAfterRing = true`、`defaultPauseDays = 1`） |
| M3.3 | **Repository** | 事务封装；暂停写入与响铃读取之间的并发由 SQLite 事务保证 |
| M3.4 | `AlarmScheduler` | 注册 / 取消 / 全量重算；主触发用 `setAlarmClock`，冗余前沿用 `setExactAndAllowWhileIdle`（[TECH-STACK](TECH-STACK.md) §4.2）；**PendingIntent 的 `requestCode` 由 `alarm.id` 派生**，杜绝复用错乱 |
| M3.5 | `AlarmReceiver` | 响铃时刻二次校验（PRD §5.4）：任一条件命中则静默退出并重排下一次 |
| M3.6 | **系统事件 Receiver** | `BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` / `MY_PACKAGE_REPLACED` → 全量重算重注册（PRD §FR-4.5） |
| M3.7 | **暂停恢复触发** | 在 `pauseUntil` 时刻也注册一个触发，用于到点后重新调度（保证「自动恢复」真的发生） |

#### 验证方式

用临时调试入口（或仪测）驱动，配合 logcat 与系统时间调整：

1. 建一个 1 分钟后的闹钟 → logcat 确认准点触发
2. 暂停该闹钟所在分组 → 触发时刻确认**不进入响铃流程**
3. `adb shell date` 调整系统时间到 `pauseUntil` 之后 → 确认自动恢复并重新注册
4. 模拟重启（`adb shell am broadcast` 发 `BOOT_COMPLETED`）→ 确认重注册
5. 修改时区 → 确认重算

#### 产出物

- 数据库 + 调度链路可用
- logcat 验证记录

---

### M4 · 响铃链路（原生）

**目标**：闹钟响起来，且锁屏、静音、App 被回收时都能响。

#### 任务

| # | 任务 | 说明 |
|---|---|---|
| M4.1 | `RingForegroundService` | `mediaPlayback` 类型前台服务持有 `MediaPlayer`；`AudioAttributes(USAGE_ALARM, CONTENT_TYPE_SONIFICATION)` 绕过静音与勿扰 |
| M4.2 | **渐强与震动** | 手动 `setVolume()` 递增实现渐强；`VibratorManager` 按闹钟设置震动 |
| M4.3 | **自动停止** | Service 内定时器，到 `autoStopMinutes` 走「响铃结束」流程 |
| M4.4 | `RingActivity` | 原生 XML layout，视觉按 M1 设计规范手写；`showWhenLocked` + `turnScreenOn`；贪睡（主按钮）与关闭；音量键行为可配置 |
| M4.5 | **响铃通知** | 高优先级 + `fullScreenIntent` + `CATEGORY_ALARM`；点击回到响铃页 |
| M4.6 | **贪睡调度** | 按 `snoozeMinutes` 重排；次数用尽后自动关闭 |
| M4.7 | **响铃结束流程** | `deleteAfterRing = true` → 从库中彻底删除并取消全部注册；否则标记 `expired = true` |

#### 验证方式

模拟器实测以下场景并截图：

- 前台响铃 / 后台响铃 / 锁屏直接响铃
- 贪睡 → 再次响铃 → 次数用尽自动关闭
- 超时自动停止
- 响铃后删除的闹钟从列表消失
- 未勾选删除的闹钟进入「已过期」区
- 系统静音模式下仍响铃

#### 风险

| 风险 | 应对 |
|---|---|
| API 36 上 `mediaPlayback` 前台服务启动被拒 | 在 M0 的 SDK 环境上先做一次最小验证；若被拒则改用 `specialUse` 类型并补 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| `fullScreenIntent` 被系统降级为普通通知 | 声音不依赖 Activity 拉起（[TECH-STACK](TECH-STACK.md) §4.4），因此最差情况是「有声音、需点通知才见界面」 |

---

### M5 · 权限体检与 HyperOS 适配

**目标**：用户能看清缺什么权限、能一键跳过去，跳不动时有兜底。

#### 任务

| # | 任务 | 说明 |
|---|---|---|
| M5.1 | **权限检测** | 精确闹钟（`canScheduleExactAlarms`）、通知、全屏 Intent（`canUseFullScreenIntent`）、自启动、省电策略、后台弹出界面、锁屏显示 |
| M5.2 | **MIUI 跳转** | 按候选 Intent 序列逐个尝试，全部失败则兜底跳本应用系统详情页（PRD §FR-5.3） |
| M5.3 | **主界面警示条** | 关键权限缺失时常驻提示，点击进入体检页 |
| M5.4 | **首次启动引导** | 引导完成体检（可跳过，但保留警示） |
| M5.5 | **文案** | 明确告知「手动结束运行后任何第三方 App 都无法唤醒」（PRD §FR-5.6） |

#### 验证方式

- 模拟器：验证各项检测的准确性（在模拟器上「自启动」等项应正确显示为不适用或已满足）
- **小米 14 真机**：验证跳转是否命中；未命中时验证兜底页可用

> 这一步的检测逻辑在模拟器上可验证，但**跳转能否命中只有真机能证明**。届时需要你配合。

---

### M6 · 桥接真实数据

**目标**：把 M1 的 mock 换成真实实现，界面操作真实落库、真实驱动调度。

#### 任务

| # | 任务 | 说明 |
|---|---|---|
| M6.1 | `AlarmHubPlugin.kt` | 实现 `AlarmHubBridge` 契约的全部方法（§4） |
| M6.2 | **注册插件** | 在 `MainActivity` 注册，确保 H5 能拿到 |
| M6.3 | **前端切真** | 把 mock 实现替换为真实插件调用，**契约不变，页面代码不动** |
| M6.4 | **保存即重算** | 任何写操作后触发 `AlarmScheduler` 重算，保证界面与调度一致 |
| M6.5 | **浏览器降级** | 无原生环境时自动回落到 mock，便于继续用浏览器调 UI |

#### 验证方式

- 在 H5 界面新建闹钟 → 查数据库确认落库 → 到点真的响
- 在 H5 界面暂停分组 → 到点真的不响（这是 **AC-3 的完整闭环**）
- 批量转换 → 数据库中所有一次性闹钟的 `deleteAfterRing` 都为 true，数量与界面提示一致
- 权限体检页的跳转按钮真的能跳

---

### M7 · 端到端联调与真机验证

**目标**：把 PRD §8 的 17 条验收标准逐条走一遍。

#### 任务

| # | 任务 |
|---|---|
| M7.1 | 在模拟器上走完可在模拟器验证的 AC（AC-1 ~ AC-11、AC-13、AC-15 ~ AC-17） |
| M7.2 | 在小米 14 真机上验证 ROM 相关项（AC-12 后台唤醒、AC-14 权限跳转） |
| M7.3 | 收尾打磨：空状态、错误提示、加载态、边界文案 |
| M7.4 | 输出验收报告，逐条标注「通过 / 未通过 / 无法验证」及证据（截图或 logcat） |
| M7.5 | 产出最终 APK |

#### 需要你配合的部分

真机验证需要你：把小米 14 连上电脑开 USB 调试；或者我构建好 APK 给你，你手动安装并按体检页的指路文案操作一遍，然后把结果反馈给我。

---

## 4. 跨语言接口契约（M1.1 冻结）

这是整个项目唯一的跨语言 API，M1 的 mock 与 M6 的原生实现必须完全一致。

```typescript
interface AlarmHubBridge {
  // —— 元信息 ——
  ping(): Promise<{ version: string; sdkInt: number; buildType: string }>;
  getHolidayDataInfo(): Promise<{ years: number[]; source: string; degraded: boolean }>;

  // —— 分组 ——
  listGroups(): Promise<Group[]>;
  saveGroup(g: Partial<Group>): Promise<Group>;
  deleteGroup(o: { id: number; moveAlarmsTo: number }): Promise<{ movedCount: number }>;
  reorderGroups(o: { orderedIds: number[] }): Promise<void>;

  // —— 闹钟 ——
  listAlarms(): Promise<AlarmView[]>;          // 含算好的 nextRingAt / 状态标记
  saveAlarm(a: Partial<Alarm>): Promise<Alarm>;
  deleteAlarm(o: { id: number }): Promise<void>;
  setAlarmEnabled(o: { id: number; enabled: boolean }): Promise<void>;
  batchUpdateAlarms(o: { ids: number[]; patch: Partial<Alarm> }): Promise<{ affected: number }>;

  // —— 暂停 / 停用 ——
  previewPause(o: PauseRequest): Promise<PausePreview>;   // 不落库，仅换算
  pause(o: PauseRequest): Promise<{ resumeAt: number }>;
  resume(o: { scope: 'group' | 'alarm'; id: number }): Promise<void>;
  setPermanentDisabled(o: { scope: 'group' | 'alarm'; id: number; disabled: boolean }): Promise<void>;

  // —— 设置 ——
  getSettings(): Promise<Settings>;
  updateSettings(patch: Partial<Settings>): Promise<Settings>;
  countOnceAlarms(): Promise<{ total: number; alreadyMarked: number }>;
  bulkSetDeleteAfterRing(): Promise<{ affected: number }>;

  // —— 权限 ——
  getPermissionStatus(): Promise<PermissionItem[]>;
  openPermissionSetting(o: { key: PermissionKey }): Promise<{ opened: boolean; fallback: boolean }>;
}

// 暂停请求：「跳过 N 个响铃日」或「直接指定恢复时刻」，二选一
type PauseRequest =
  | { scope: 'group' | 'alarm'; id: number; days: number }
  | { scope: 'group' | 'alarm'; id: number; until: number };

interface PausePreview {
  resumeAt: number;            // 恢复时刻（epoch millis）
  skippedRingDays: number[];   // 被跳过的响铃日，用于界面展示
  nextRingAt: number | null;   // 恢复后的首次响铃
}
```

**契约的两条纪律**：

1. **`previewPause` 是纯计算，不落库。** 前端做「恢复时刻预览」靠它，因此不需要在前端重实现 PRD §5.3 的算法。
2. **`AlarmView` 里带算好的 `nextRingAt`。** 倒计时由原生算，前端只负责格式化显示。

---

## 5. 里程碑门禁

每个里程碑结束时必须同时满足：

| 门禁 | 要求 |
|---|---|
| **可构建** | `gradle assembleDebug` 零错误 |
| **可安装** | `adb install -r` 成功，启动不崩溃 |
| **可看见** | 有对应的截图存进 `.shots/` |
| **可验证** | 该里程碑声明的验证方式已实际执行并留下记录（日志 / 截图 / 测试输出） |
| **文档同步** | 若实现偏离了 PRD 或 TECH-STACK，必须回改文档，**不允许文档与代码不一致** |

---

## 6. 计划级风险

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| P1 | 模拟器（Pixel 7 / Android 16）与小米 14（HyperOS）行为差异大 | 模拟器上通过 ≠ 真机可用 | M7 专门留真机验证；M5 的跳转逻辑从一开始就设计兜底而非假设成功 |
| P2 | M1 的前端在 mock 上做得漂亮，M6 接真数据时发现契约不够用 | 需要回头改契约和页面 | M1.1 把契约冻结成显式文件；M2 的领域模型与契约数据结构对齐设计 |
| P3 | M0 的三个集成风险任一未清除 | 后期返工 | M0 就是为此存在；未通过不进入 M1 |
| P4 | HyperOS 私有设置页的 Intent 全部失效 | 一键跳转不可用 | 兜底到应用详情页 + 图文指路（PRD §FR-5.3） |
| P5 | 节假日数据只内置到 2026 年 | 2027 年后「工作日」判断失真 | 数据与代码分离；缺失时自动降级为周一~周五并在设置页提示（PRD §FR-6.3） |
| P6 | 真机验证需要你配合，可能成为瓶颈 | M7 卡住 | 真机项与其余项解耦，M7.1（模拟器项）可先完成，M7.2 单独等你的时间 |

---

## 7. 下一步

计划确认后，我立即开始 **M0**（工程基线与集成风险清除），完成后进入 **M1 前端页面效果**，做完停下来给你看截图，等你确认后再继续 M2 起的正式开发。

如果你想跳过 M0 直接看界面，也可以——但代价是界面只能先在浏览器里看，装不进模拟器。我的建议是走完 M0，因为它是后面每一步能被你实际看见的前提。
