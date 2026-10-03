# AlarmHub 技术栈方案

| 项 | 值 |
|---|---|
| 文档版本 | v1.0 |
| 对应需求 | [docs/PRD.md](PRD.md) v1.0（已确认） |
| 文档状态 | **待确认**——确认后进入开发计划阶段 |
| 前置事实 | 工作目录已有一个端到端验证过的 Capacitor 8 壳工程（模板 `HelloShell`），闹钟逻辑从零开始 |

---

## 1. 结论

**推荐方案：原生闹钟引擎 + H5 管理界面 + 原生响铃页的混合架构。**

- **所有业务逻辑、闹钟调度、数据存储、响铃发声** 用 Kotlin 原生实现，保证可靠性；
- **除响铃页以外的全部界面** 用 H5（Vue 3 + Vite + TypeScript）实现，复用已验证的 Capacitor 8 壳；
- **响铃页是唯一的原生 UI**，因为它是整条链路上唯一「必须秒起且不能被系统回收」的界面。

这个划分的关键在于：**可靠性要求最高的响铃链路完全不经过 WebView**。H5 只在「你打开 App 管理闹钟」时出现，被系统回收了也只是重进时重新加载，不影响闹钟本身。

---

## 2. 候选方案对比

| 方案 | 可靠性 | UI 迭代速度 | 与现有工程 | 结论 |
|---|---|---|---|---|
| **A. 全原生 Kotlin + Jetpack Compose** | 最高 | 慢（每改一次 UI 都要编译装 APK） | 需丢弃已验证的壳 | 备选 |
| **B. H5 管理界面 + 原生闹钟引擎 + 原生响铃页** | 与 A 等同（响铃链路全原生） | 快（浏览器直接看效果） | 直接复用 | **推荐** |
| C. 全 H5（含响铃页） | ❌ 响铃依赖 WebView 冷启动，0.3~1s 延迟，且进程被回收会丢 | 最快 | 复用 | 排除 |
| D. Flutter / React Native | 中（RN 的精确闹钟仍需自己写原生模块） | 中 | 需全新工程 | 排除：本机无 Flutter SDK，且闹钟可靠性问题更多 |

### 为什么排除 C（全 H5）

响铃时 WebView 需要冷启动 + Capacitor 注入，实测通常 0.3~1 秒；如果系统刚回收了 WebView 进程，还会先白屏再渲染。闹钟是「早响 0 秒都算失败」的场景，这条不能接受。PRD §FR-4.3 要求「唤醒到发声 < 1 秒」，方案 C 达不到。

### 为什么不选 A（全原生 Compose）

技术上完全可行，且可靠性一样。排除理由是**成本**：本工程已经有一个验证过构建、装机、截图、CDP 调试、Espresso 仪测全链路的 Capacitor 壳；改用 Compose 等于把它全部推倒，而且你要求的「先做一个前端页面效果」这一步会变成「编译 APK → 装机 → 截图」的慢循环，而不是浏览器里即时可见。**如果你想换 A，现在说，代价是放弃已跑通的壳。**

---

## 3. 技术栈清单

### 3.1 原生侧（Kotlin）

| 用途 | 选型 | 说明 |
|---|---|---|
| 语言 | **Kotlin 2.2.21** | 已实测编译通过并在设备上运行；选 2.2.x 而非 2.3.x 的理由见 §6.1 |
| 构建 | **Gradle 8.14.3（全局安装）+ AGP 8.13.0** | 沿用现有配置，不使用 `gradlew`（省 439MB 解包） |
| 编译 JDK | **JDK 21** | Capacitor 8 的模块是 `sourceCompatibility 21`，已钉在 `gradle.properties` |
| SDK | `minSdk 31`（Android 12）/ `compileSdk 36` / `targetSdk 36` | 已确认只支持 Android 12+，可省掉旧版权限分支 |
| 持久化 | **Room 2.8.x（SQLite，WAL 模式）** | 闹钟触发与暂停写入之间存在并发，需要事务保证 |
| 注解处理 | **KSP 2.2.21-2.0.5** | 旧式 `<kotlin>-<ksp>` 命名，与 Kotlin 的配对关系无歧义（M3 才启用） |
| 异步 | **Kotlin Coroutines 1.10.x + Flow** | |
| 闹钟调度 | **AlarmManager**（`setAlarmClock` 主 + `setExactAndAllowWhileIdle` 冗余前沿） | 见 §4.2 |
| 响铃发声 | **Foreground Service + MediaPlayer**，`AudioAttributes USAGE_ALARM` | 见 §4.4 |
| 响铃界面 | **原生 Activity + XML layout（View 体系，不用 Compose）** | 见 §4.5 |
| 依赖注入 | **手写 AppContainer** | 项目规模不值得引入 Hilt |
| 单元测试 | **JUnit 4 + kotlin.test**（纯 JVM，测 domain 层） | AC-1 / AC-2 / AC-5 靠它 |
| 仪器测试 | **Espresso**（沿用已验证的 adb 直跑链路） | |

### 3.2 前端侧（H5）

| 用途 | 选型 | 说明 |
|---|---|---|
| 框架 | **Vue 3（Composition API + `<script setup>`）** | 页面/组件数量适中，SFC 写表单和弹层最省事 |
| 语言 | **TypeScript** | 能编译期发现调错插件 API 的参数 |
| 构建 | **Vite 8.3.2**，`build.outDir` 指向 `www/` | 产物即 Capacitor 的 `webDir`，零额外配置 |
| 路由 | **不用路由库**，手写一个轻量页面栈 | 只有 7 个页面，引入 vue-router 是负担 |
| 状态 | **Vue `reactive` + composable（不用 Pinia）** | 单一数据源来自原生，前端不需要复杂 store |
| UI 组件库 | **不引入**（不用 Vant / NutUI） | 目标是贴合 HyperOS 视觉，组件库的设计语言会互相打架 |
| 样式 | 原生 CSS + CSS 变量做主题（深色/浅色） | |
| 时间选择 | 自研**滚轮式**时/分选择器 | 表盘式放 P2，见 §9 待确认点 |

### 3.3 安全与网络

| 项 | 值 |
|---|---|
| `INTERNET` 权限 | **不申请**（PRD §6 硬承诺）。App 完全无法联网 |
| 网络依赖 | 无。前端资源全部打包进 APK |
| 数据存储 | 仅本机 SQLite；无账号、无埋点、无崩溃上报 |

---

## 4. 关键实现决策

### 4.1 架构分层与「单一实现」原则

```
┌─────────────────────────────────────────────────────────┐
│  H5 界面层（Vue 3）  —— 纯视图，不含任何业务规则          │
│    列表 / 编辑 / 设置 / 分组管理 / 权限体检 / 暂停面板     │
└────────────────────────┬────────────────────────────────┘
                         │  Capacitor 插件桥（JSON）
┌────────────────────────┴────────────────────────────────┐
│  bridge/  AlarmHubPlugin.kt —— 唯一的原生↔H5 出入口      │
├─────────────────────────────────────────────────────────┤
│  domain/  业务规则（纯 Kotlin，无 Android 依赖，可单测）   │
│    RepeatRule · NextRingCalculator · PauseResolver        │
│    HolidayCalendar                                        │
├─────────────────────────────────────────────────────────┤
│  data/    Room 实体 / DAO / Repository                    │
├─────────────────────────────────────────────────────────┤
│  alarm/   AlarmManager 调度 · BroadcastReceiver · 前台服务 │
│  ring/    响铃 Activity · 铃声 · 震动                     │
│  permission/  权限检测 · MIUI 跳转                        │
└─────────────────────────────────────────────────────────┘
```

**架构不变量（开发中不可违反）**：

1. **业务规则只在 `domain/` 实现一份。** 前端绝不复制「跳过 N 个响铃日」的换算算法——它通过桥调用 `previewPause()` 拿结果。这是防止 JS 与 Kotlin 两份实现漂移的唯一办法。
2. **响铃链路不依赖 WebView。** 闹钟触发 → 响铃 Activity → 发声，全程原生。
3. **数据只有一个真源：原生 SQLite。** H5 不缓存状态，每次进页面从原生拉。
4. **跳转与文案分离。** 权限页的「一键跳转」失败不影响文案指路（PRD §FR-5.3）。

### 4.2 闹钟调度：主触发 + 冗余前沿

| 触发 | API | 理由 |
|---|---|---|
| **主触发**（准点） | `setAlarmClock()` | 第三方能拿到的最高优先级，Doze 下免豁免精确触发，并在状态栏显示系统闹钟图标（和系统闹钟一致的用户预期） |
| **冗余前沿**（准点前 5 秒） | `setExactAndAllowWhileIdle()` | 防止单次触发被系统丢弃。**故意不用 `setAlarmClock`**——否则状态栏图标会显示早 5 秒的错误时间 |

两个触发都走同一套「响铃时刻二次校验」（PRD §5.4）。若前沿已经进入响铃流程，主触发的校验会发现「已响铃」而静默退出，不会响两次。

### 4.3 精确闹钟权限：用 `USE_EXACT_ALARM`

| 权限 | 行为 | 采用 |
|---|---|---|
| `USE_EXACT_ALARM` | 安装即授予，用户无法关闭 | ✅ **采用** |
| `SCHEDULE_EXACT_ALARM` | Android 14+ 对 targetSdk 34+ 的新装机默认**不授予**，需引导用户手动开 | ❌ 不用（会多一道用户操作） |

`USE_EXACT_ALARM` 在 Google Play 上限定「闹钟/日历类应用」，本项目**个人自用、不上架**，无政策风险（PRD 风险 R5）。同时在权限体检页检测 `canScheduleExactAlarms()` 作为兜底，异常时给出引导。

### 4.4 响铃发声：前台 Service 持有 MediaPlayer

**为什么发声不能放在响铃 Activity 里**：拉起全屏 Activity 依赖 `fullScreenIntent`，而这个权限在 Android 14+ 可能被用户关闭。如果声音跟着 Activity 走，一旦 Activity 拉不起来就变成「有通知但没声音」——对闹钟是致命的。

所以：**前台 Service 负责发声与状态，Activity 只负责 UI**。

| 项 | 决策 |
|---|---|
| Service 类型 | `mediaPlayback`（确实在播放音频，且启动时机不受 Android 14 限制） |
| 音频属性 | `AudioAttributes(USAGE_ALARM, CONTENT_TYPE_SONIFICATION)` — 绕过静音与勿扰模式（PRD §FR-4.3.11） |
| 渐强 | 手动 `MediaPlayer.setVolume()` 递增，不用系统渐强 API |
| 自动停止 | Service 内定时器，到 `autoStopMinutes` 后走「响铃结束」流程（含响铃后删除判定） |
| 通知 | 高优先级 + `fullScreenIntent` + `CATEGORY_ALARM`，点击回到响铃页 |
| UI 拉起 | 响铃 Activity `showWhenLocked` + `turnScreenOn`，锁屏直接显示（PRD §FR-4.3.2） |

### 4.5 响铃页用 View 而不是 Compose

响铃页是唯一必须「秒起」的界面。引入 Jetpack Compose 意味着额外加载 Compose 运行时（首次约 1MB 类加载），而这个项目其余界面全在 H5，**为单独一页引入 Compose 不划算**。所以响铃页用传统 XML layout + View 体系，视觉上按同一套设计规范手写，保证和 H5 界面观感一致。

### 4.6 铃声选择：用系统选择器，不要存储权限

用 `RingtoneManager.ACTION_RINGTONE_PICKER` 拉起系统铃声选择器，拿回 `content://` URI。

**好处：不需要申请 `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`。** 这是刻意的选择——PRD 承诺「纯本地、最小权限」，能不要的权限一个都不要。

### 4.7 完整权限清单

| 权限 | 用途 | 申请方式 |
|---|---|---|
| `USE_EXACT_ALARM` | 精确闹钟 | 安装即授予 |
| `POST_NOTIFICATIONS` | 响铃通知 | 运行时申请（Android 13+） |
| `USE_FULL_SCREEN_INTENT` | 锁屏直显响铃页 | Android 14+ 需在体检页检测 `canUseFullScreenIntent()` |
| `FOREGROUND_SERVICE` | 前台服务 | 安装即授予 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 前台服务类型 | 安装即授予 |
| `RECEIVE_BOOT_COMPLETED` | 开机重注册 | 安装即授予 |
| `VIBRATE` | 震动 | 安装即授予 |
| `WAKE_LOCK` | 响铃期间保持唤醒 | 安装即授予 |
| `SYSTEM_ALERT_WINDOW` | 后台弹出界面（HyperOS 需手动开） | 跳转引导，**尽力而为不阻塞** |
| ~~`INTERNET`~~ | — | **明确不申请** |

### 4.8 业务规则单测策略

`domain/` 包设计为**纯 Kotlin、零 Android 依赖**（时间通过参数注入，不用 `System.currentTimeMillis()`）。这样 PRD 里的验收项可以直接写成 JVM 单元测试：

| 验收项 | 测试方式 |
|---|---|
| AC-1「跳过 N 个响铃日」4 种场景 | 纯函数测试，喂入 (当前时间, 重复规则, N) 断言恢复时刻 |
| AC-2 国庆调休周六响铃 | 喂入节假日数据 + 日期，断言匹配结果 |
| AC-5 响铃时刻二次校验 | 构造「暂停时刻与触发时刻相差 1ms」的竞态用例 |

节假日数据通过 `HolidayCalendar` 接口注入，测试用固定数据集，不依赖真实年份。

**M2 实施时补充的两条决定**（详见 [M2-STATUS.md](M2-STATUS.md)）：

1. **JSON 解析不用 `org.json`，改用手写的 `domain/json/MiniJson.kt`。** `org.json` 只存在于 Android SDK 里，在 JVM 单测中是抛 "not mocked" 的 stub，而 `domain/` 又不允许依赖 Android 类型；为止这一个扁平的节假日文件引入 JSON 依赖也不划算。`MiniJson` 约 130 行，支持对象 / 数组 / 字符串转义 / 数字 / 字面量，畸形输入有 8 条单测覆盖。副作用是节假日文件的格式完全由我们掌握。
2. **节假日数据的「可核对性」是数据文件的设计目标。** `holidays.json` 的 `holiday` / `workday` 两个清单只收「国务院通知里逐个点名的日期」，因此能逐条对照原文核验；`rest`（按节日分组的展示块）不参与任何判定，写错也不会让闹钟在错误的日子响。单测会校验：每个调休日确实是周末、没有任何一天同时是节假日和调休上班日、每个年份的 `rest` 恰好等于该年 `holiday` 清单，且数据文件真的是随 APK 出货的那一份。

---

## 5. 与现有工程的整合点

需要改动的既有文件（都是模板身份相关的机械改动）：

| 文件 | 改动 |
|---|---|
| `package.json` | `name` → `alarmhub`；新增 vue / vite / typescript 依赖 |
| `capacitor.config.json` | `appId` → `com.alarmhub.app`；`appName` → `闹钟坞`；`webDir` 保持 `www` |
| `android/app/build.gradle` | `namespace` / `applicationId` → `com.alarmhub.app`；加 Kotlin、Room、KSP 插件 |
| `android/build.gradle` | buildscript 加 Kotlin Gradle Plugin、KSP 插件 classpath |
| `android/variables.gradle` | 新增 Room / KSP / lifecycle 版本号；**复用已有的 `androidxCoreVersion` 等 ext 值**，避免与 Capacitor 8 的 classpath 冲突 |
| `android/app/src/main/java/com/dsh/helloshell/` | 整体迁移到 `com/alarmhub/app/`，`MainActivity.java` → `MainActivity.kt` |
| `android/app/src/androidTest/.../ShellSmokeTest.java` | 迁移包名，作为持续的冒烟测试保留 |
| `android/app/src/main/AndroidManifest.xml` | 加权限、Receiver、Service、响铃 Activity 声明 |
| `www/index.html` | 替换为 Vue 构建产物（构建时生成，不手写） |

**已解决的既有技术债（不要改回去，详见 [README.md](../README.md)）**：阿里云镜像顺序、仓库内 debug.keystore、JDK 21 钉定、WebView 的 `android:id`。

### 5.1 需要重新处理的一处旧配置

现有 `android/app/build.gradle` 里有一段：

```groovy
configurations.all {
    resolutionStrategy {
        force 'org.jetbrains.kotlin:kotlin-stdlib:1.8.22'
        force 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22'
    }
}
```

这是当初为修「espresso 拉 1.6.21 / cordova 拉 1.8.22 导致 androidTest classpath 重复类」加的。**引入 Kotlin 2.3 后会冲突**（会把 stdlib 降级回 1.8.22）。处理方式：把 force 值上调到 Kotlin 2.3 对应的 stdlib 版本，并重跑冒烟仪测确认重复类问题没有回归。

---

## 6. 版本锁定（M0 已实测锁定）

以下版本是 M0 真实解析并**编译、装机、跑通仪测**之后的实际值，不再变动：

| 组件 | 锁定版本 | 状态 |
|---|---|---|
| AGP | 8.13.0 | 沿用模板 |
| Gradle | 8.14.3（全局安装） | 沿用模板 |
| Kotlin | **2.2.21** | ✅ 编译并运行通过（`KotlinVersion.CURRENT` = 2.2.21） |
| KSP | **2.2.21-2.0.5** | 已锁定，M3 启用 |
| Room | **2.8.5** | 已锁定，M3 启用 |
| Coroutines | **1.10.2** | 已锁定 |
| Capacitor | 8.5.2 | 沿用模板 |
| Vue | **3.5.43** | ✅ 构建通过 |
| Vite | **8.3.2** | ✅ 构建通过 |
| @vitejs/plugin-vue | **6.0.9** | ✅ |
| TypeScript | **~5.9** | ✅ 见下面的说明 |
| vue-tsc | **3.3.11** | ✅ 检查 `.vue` 的 script 与模板 |
| compileSdk / targetSdk | 36 | |
| minSdk | 31 | |

**TypeScript 为什么是 5.9 而不是更新版**：`typescript` 在 Vite 的构建链里**完全不参与编译**（Vite 用 oxc 转译），只用于类型检查。而唯一能检查 `.vue` 文件的工具是 `vue-tsc`，它需要 TypeScript 的 JS 编译器 API；TypeScript 7 是 Go 原生移植版，不再导出 `./lib/tsc`，`vue-tsc` 会直接报 `ERR_PACKAGE_PATH_NOT_EXPORTED`。

由此带来一个**容易漏掉的后果**：单独跑 `tsc` 根本不会检查 `.vue` —— TypeScript 只认 `.ts` / `.tsx` / `.d.ts` 后缀，`.vue` 被静默忽略。M1 期间那 1500+ 行 SFC 代码因此一直没有静态检查，补上 `vue-tsc` 后立刻抓到一个未使用变量。**类型检查一律用 `npm run web:typecheck`。**

### 6.1 为什么 Kotlin 用 2.2.21 而不是 2.3.x

两个理由，都是为了在 M0 消风险而不是赌运气：

1. **2.2.x 是 AGP 8.13 的稳妥配对**，2.3.x 相对 AGP 8.13 偏新。
2. **KSP 的版本号在 2.3 处换了规则**：2.3 之前是 `<kotlin版本>-<ksp版本>`（如 `2.2.21-2.0.5`），配对关系自明；2.3 起改为独立版本号（`2.3.12` 之类），配对不再能一眼看出。选 2.2.21 就能用一个无歧义的 KSP 版本。

计划里原本写的「若 Kotlin 2.3.x 报错则回退 2.2.x」这条待验证点，因为在 M0 就直接选了安全的 2.2.21，**已经不再需要验证**。

### 6.2 关于受限沙箱（已解除，仅作记录）

M0 期间本会话处于受限文件沙箱，遇到两个与项目本身无关的障碍，都已通过把会话切到完全权限解决，**相关的绕行手段已全部删除**：

1. **既有目录树不可写**：本仓库从模板项目复制而来，`android/`、`node_modules/` 等目录的 ACL 不含会话的沙箱授权项，`npm install` 与 `gradle` 均失败。当时的做法是在 `.m0build/` 镜像里构建，现已删除镜像，构建回到仓库内。
2. **Vite 构建崩溃**：受限沙箱禁止带管道 stdio 的子进程，此时 `child_process.spawn` **同步抛 EPERM**；而 Vite 的 `optimizeSafeRealPathSync()` 在 try/catch **之外**调用 `exec("net use")`，整个构建被打断。当时的做法是用一个预加载脚本让 `fs.realpathSync.native` 首次调用抛出 Vite 要找的 `EISDIR` 错误，触发它自己的提前返回分支。现已确认完全权限下 `npm run web:build` 直接可用，脚本已删除。

留下这条记录的原因：如果以后又在受限沙箱里开工，这两个现象会原样复现，不必重新排查。

### 6.3 仍未验证的一项

**`mediaPlayback` 类型前台服务在 Android 16（API 36 模拟器）上的启动行为**——属于 M4，届时实测。

---

## 7. 开发与验证环境

| 项 | 值 |
|---|---|
| 开发验证设备 | 模拟器 `dsh_pixel`（Pixel 7 / Android 16 / API 36），WHPX 加速已确认可用 |
| 最终验证设备 | 你的**小米 14（HyperOS）真机** |
| 装机方式 | 模拟器走 `adb install`；真机走 USB 调试，或直接传 APK 文件手动安装 |
| 构建命令 | `gradle assembleDebug`（全局 Gradle，不用 `gradlew`） |
| UI 查看 | 模拟器截图（`adb shell screencap`）；前端开发期可用浏览器直看 `web/` |
| 页面调试 | Chrome DevTools 协议（`adb forward` + `tools/cdp-probe.js`），可查 console 报错、直接操作 DOM |
| 仪器测试 | `adb shell am instrument` 直跑（`gradlew connectedAndroidTest` 在沙箱里跑不了，UTP 需要命名管道） |

### 7.1 一个必须说清的限制

**模拟器无法复现 MIUI/HyperOS 的后台管控行为。** Pixel 7 上「闹钟准时响」不代表小米 14 上也一定响。所以 PRD §9 里 R1（后台被杀）和 R2（权限未授予）这两条风险，**只能在你的真机上验证**，模拟器阶段我只能保证逻辑正确和权限检测准确。

这意味着开发过程中会需要一个「真机验证」环节，届时需要你配合把设备连上电脑开 USB 调试，或者我把 APK 给你手动安装并按指路文案操作。

---

## 8. 这套方案如何满足 PRD 的验收标准

| PRD 验收项 | 靠什么满足 |
|---|---|
| AC-1 / AC-2 响铃日换算与调休 | `domain/` 纯 Kotlin 单测（§4.8） |
| AC-3 / AC-4 暂停与自动恢复 | 暂停换算成绝对时间戳 + 恢复时刻也注册一个「重新调度」触发 |
| AC-5 竞态二次校验 | 响铃时刻查库（§4.2）+ 单测 |
| AC-6 ~ AC-8 响铃后删除与批量转换 | 桥 API + Room 事务 |
| AC-9 ~ AC-10 重启/改时间重注册 | `BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` Receiver |
| AC-11 锁屏直显 | `showWhenLocked` + `fullScreenIntent`（§4.4） |
| AC-12 App 被回收仍能响 | `setAlarmClock` 免豁免 + 前台 Service（§4.2 / §4.4） |
| AC-13 静音/勿扰仍响 | `USAGE_ALARM`（§4.4） |
| AC-14 权限体检与跳转 | §4.7 + `try/catch` 兜底 |
| AC-15 不申请 INTERNET | §3.3 |
| AC-16 深浅色主题 | CSS 变量 + H5 单一实现 |
| AC-17 200 个闹钟流畅 | 列表虚拟化按需引入；200 条数据量本身无压力 |

---

## 9. 决策点（已确认）

| # | 决策点 | 结论 | 状态 |
|---|---|---|---|
| T1 | **整体架构方向** | **方案 B**：H5 管理界面 + 原生闹钟引擎 + 原生响铃页 | ✅ 已确认 |
| T2 | 前端框架 | Vue 3 + Vite + TypeScript | ✅ 已定 |
| T3 | 时间选择器形态 | **第一版滚轮式**；表盘式放 P2 | ✅ 已确认 |
| T4 | 是否引入 UI 组件库 | 不引入，全部自研 | ✅ 已定 |
| T5 | 最低 Android 版本 | **`minSdk 31`（Android 12）** | ✅ 已确认 |
| T6 | 响铃页是否也用 H5 | 用原生（§4.5）。T1 选 B 后此项必然成立 | ✅ 已定 |

### 9.1 `minSdk 31` 带来的简化

选择 Android 12+ 后，以下兼容分支可以完全省掉：

- 通知渠道创建（API 26+ 才需要，现在是所有目标设备都有）
- `showWhenLocked` / `turnScreenOn`（API 27+ 原生支持，无需 `WakeLock` 兼容写法）
- 前台服务的 `startForegroundService` 超时兼容（API 26+ 统一行为）
- 旧版 `AlarmManager.setExactAndAllowWhileIdle` 的 Doze 兼容分支

仍然需要处理的分支（因为 `targetSdk 36`）：

- Android 13+ 的 `POST_NOTIFICATIONS` 运行时申请
- Android 14+ 的 `foregroundServiceType` 声明（`mediaPlayback`）
- Android 14+ 的 `canUseFullScreenIntent()` 检测

---

## 10. 下一步

本方案确认（含 §9 决策点）后：

1. 输出**开发计划**：任务拆解、里程碑、每个里程碑的验证方式与产出物；
2. 开发计划确认后，先做**前端页面效果**（真实可运行的界面，模拟器截图给你看）；
3. 确认后进入正式开发。
