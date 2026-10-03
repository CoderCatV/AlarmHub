# 闹钟坞 (AlarmHub)

按场景整组暂停的本地闹钟。把闹钟按「工作日 / 节假日 / 自定义场景」分组，请假时一键暂停整组 N 天并自动恢复；一次性提醒闹钟默认用完即删。

**不联网**：App 不申请 `INTERNET` 权限，无账号、无埋点、无广告。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/STATUS.md](docs/STATUS.md) | **当前进度与交接说明 —— 恢复工作先读这一份** |
| [docs/PRD.md](docs/PRD.md) | 需求文档（已确认）：功能需求、数据模型、关键业务规则、17 条验收标准 |
| [docs/TECH-STACK.md](docs/TECH-STACK.md) | 技术栈（已确认）：架构选型、关键实现决策、锁定版本 |
| [docs/DEVELOPMENT-PLAN.md](docs/DEVELOPMENT-PLAN.md) | 开发计划：M0~M7 里程碑、验证方式、跨语言接口契约 |
| [docs/M0-STATUS.md](docs/M0-STATUS.md) | M0 完成报告：交付项、验证证据 |

## 当前状态

**M0、M1 已完成。** M1 是前端页面效果：5 个页面（列表 / 编辑 / 分组管理 / 设置 / 权限体检）跑在 mock 数据上。
**下一步是 M2 · 业务规则层**（纯 Kotlin + 单元测试）。

界面接的是 `web/src/bridge/mock.ts` 的演示数据，**不是真实数据库** —— 原生实现要到 M6；届时用 `VITE_BRIDGE=native` 构建即切换到真实插件，页面代码不需改动。mock 在原生构建里不存在。

审阅界面时的两个调试入口（仅 mock 下可用）：

```js
window.__alarmhubMock                                   // 直接操作 mock 数据
sessionStorage.setItem('alarmhub:scenario', 'empty')    // 然后 location.reload() 看空状态
sessionStorage.removeItem('alarmhub:scenario')          // 恢复演示数据
```

## 架构

```
H5 管理界面 (web/, Vue 3)  ← 纯视图，不含业务规则
        │  Capacitor 插件桥
        ▼
bridge/  AlarmHubPlugin.kt  ← 唯一出入口
domain/  业务规则（纯 Kotlin，可单测）
data/    Room
alarm/   AlarmManager 调度 · Receiver · 前台服务
ring/    响铃 Activity（原生，全链路不经过 WebView）
```

**四条不可违反的架构不变量**（理由见 TECH-STACK §4.1）：

1. 业务规则只在 `domain/` 实现一份，前端绝不复制算法。
2. 响铃链路不依赖 WebView。
3. 数据只有一个真源：原生 SQLite，H5 不做状态缓存。
4. 权限跳转失败不影响文案指路。

## 环境变量

每次新会话设一次：

```powershell
. .\tools\env.ps1
```

它把 `JAVA_HOME`（JDK 21）、Android SDK、Gradle 指到本机已装好的位置，并把 `GRADLE_USER_HOME` 与 npm 缓存放在仓库内（`~/.gradle` 不用，保持自包含）。

## 构建

```powershell
# 类型检查（vue-tsc，会检查 .vue 的 script 与模板）
# 注意：单独跑 tsc 不会检查 .vue —— TypeScript 只认 .ts/.tsx/.d.ts，.vue 被静默忽略
npm run web:typecheck

# 前端 -> www/
npm run web:build

# 同步进 Android 工程
npx --no-install cap sync android

# 构建 + 安装
cd android
gradle :app:assembleDebug --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.alarmhub.app/.MainActivity
```

`www/` 是构建产物，不手工编辑。

用全局 `gradle`，不要用 `gradlew`（后者每次要在 `GRADLE_USER_HOME` 里解包 439 MB）。

## 看页面 / 调页面 / 测试

```powershell
# 截图（第一次常出 0 字节，重试即可）
adb shell screencap -p /sdcard/s.png
adb pull /sdcard/s.png .shots\s.png

# 页面 console（Capacitor 会转发到 logcat）
adb logcat -d -s "Capacitor/Console:V" "Capacitor:V"

# 用 Chrome DevTools 协议直接操作 DOM
$appPid = (adb shell pidof com.alarmhub.app).Trim()
adb forward tcp:9222 localabstract:webview_devtools_remote_$appPid
node tools\cdp-probe.js

# 仪器测试
gradle :app:connectedDebugAndroidTest --console=plain

# 备选（若上面的任务在受限 shell 沙箱里报 "Fatal error while executing main"：
# AGP 8.13 走 UTP，其辅助 JVM 需要命名管道）
gradle assembleDebugAndroidTest --console=plain
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.alarmhub.app.ShellSmokeTest `
    com.alarmhub.app.test/androidx.test.runner.AndroidJUnitRunner
```

## 不要改回去的东西

模板自带、已经踩过坑的配置：

| 文件 | 配置 | 原因 |
|---|---|---|
| `android/build.gradle` | 阿里云 Maven 镜像排在 `google()`/`mavenCentral()` 前 | 国内直连 Google Maven 太慢 |
| `android/build.gradle` | `buildscript { ext.kotlin_version }` 写在 buildscript **内部** | `buildscript{}` 会被 Gradle 提升，读不到文件后面 `apply from: "variables.gradle"` 定义的值 |
| `android/build.gradle` | `allprojects` 里 force `kotlin-stdlib` / `-jdk7` / `-jdk8` | Kotlin 1.8 起 jdk7/jdk8 变成纯重定向模块，但 espresso 拉的 1.6.21 仍含真实类，androidTest 会重复类。**必须放在根 `allprojects`**：Capacitor 库模块有自己的 androidTest classpath，写在 `app/build.gradle` 里够不到 |
| `android/debug.keystore` + `app/build.gradle` 的 `signingConfigs` | 调试密钥放进项目内 | AGP 每次构建要写 `<用户目录>/.android/debug.keystore.lock`，受限环境下会被拒绝 |
| `app/build.gradle` | `kotlinOptions.jvmTarget = '21'` | `capacitor.build.gradle` 把 Java 目标设为 21，不一致会报 "Inconsistent JVM-target compatibility" |
| `android/gradle.properties` | `org.gradle.java.home` 指向 JDK 21 | Capacitor 8 的模块是 `sourceCompatibility 21` |
| `activity_main.xml` | WebView 带 `android:id="@+id/webview"` | 原模板没有 id，Espresso 定位不到 |
| `AndroidManifest.xml` | 不申请 `INTERNET` | PRD 的隐私承诺（AC-15），由 `ShellSmokeTest.shellPageLoads` 守护 |

更完整的工具链说明在全局技能 `android-dev` 里，任何目录下的新会话都会自动加载。
