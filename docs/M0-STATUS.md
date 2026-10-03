# M0 完成报告 · 工程基线与集成风险清除

| 项 | 值 |
|---|---|
| 对应计划 | [DEVELOPMENT-PLAN.md](DEVELOPMENT-PLAN.md) M0 |
| 状态 | ✅ **全部完成** |
| 验证方式 | 真实构建 + 装机 + 截图 + 仪器测试，全部在模拟器 `emulator-5554`（Pixel 7 / Android 16 / API 36）上执行 |

---

## 1. 交付项

| # | 任务 | 结果 | 证据 |
|---|---|---|---|
| M0.1 | 版本锁定 | Kotlin **2.2.21**、KSP **2.2.21-2.0.5**、Room **2.8.5**、Coroutines **1.10.2**、Vue **3.5.43**、Vite **8.3.2**、plugin-vue **6.0.9**、TypeScript **7.0.2** | [TECH-STACK.md](TECH-STACK.md) §6 |
| M0.2 | 工程身份迁移 | `com.dsh.helloshell` → `com.alarmhub.app`，应用名 → 闹钟坞 | 设备上 `appId = com.alarmhub.app` |
| M0.3 | 接入 Kotlin | KGP + `kotlin-android`，`MainActivity.kt`，`jvmTarget = 21`（对齐 Capacitor 的 Java 21） | `:app:compileDebugKotlin` 成功；运行时 `kotlinVersion = 2.2.21` |
| M0.4 | `kotlin-stdlib` 冲突处理 | `kotlin-stdlib` 上调至 2.2.21，`kotlin-stdlib-jdk8` 钉在 1.8.22（最后一个纯重定向版本） | androidTest 编译通过且仪测运行成功，**重复类问题未回归** |
| M0.5 | 前端构建链 | `web/`（Vue 3 + Vite + TS）→ `www/` → `cap sync` → assets | `✓ 16 modules transformed`；`cap sync` 输出 `Copying web assets from www to ...assets/public` |
| M0.6 | 最小桥 | `AlarmHubPlugin.ping()`，`MainActivity` 在 `super.onCreate()` 之前注册 | 见 §2 日志 |

---

## 2. 桥连通性的直接证据

```
Capacitor: Registering plugin instance: AlarmHub
Capacitor/Console: pluginHeaders=["SystemBars","AlarmHub","CapacitorCookies","WebView","CapacitorHttp"]
Capacitor: callback: 57058760, pluginId: AlarmHub, methodName: ping, methodData: {}
Capacitor/Console: ping ok: {"version":"1.0","sdkInt":36,"buildType":"debug",
                            "appId":"com.alarmhub.app","kotlinVersion":"2.2.21",
                            "device":"Google sdk_gphone64_x86_64"}
```

Kotlin 侧被注册、JS 侧发起调用、数据穿过 WebView 回到 H5——M0 的核心目的达成。

截图（Vite 打包产物渲染的真实 `App.vue`）：`shots/m0-vite.png`

---

## 3. 仪器测试

```
adb shell am instrument -w -e class com.alarmhub.app.ShellSmokeTest \
    com.alarmhub.app.test/androidx.test.runner.AndroidJUnitRunner

com.alarmhub.app.ShellSmokeTest:...
Time: 4.44
OK (3 tests)
```

三个用例：

| 用例 | 验证内容 |
|---|---|
| `webViewIsDisplayed` | 原生容器确实显示了 WebView |
| `shellPageLoads` | WebView 停在 Capacitor 本地服务而非 `about:blank` |
| `alarmHubPluginIsRegistered` | `bridge.getPlugin("AlarmHub")` 非空——桥真的接上了 |

注意 `shellPageLoads` 还顺带守住了 PRD 的 **AC-15**：这个 APK **没有申请 `INTERNET` 权限**，页面照样从 APK 自身的 assets 加载（Capacitor 用 `WebViewAssetLoader` 在进程内拦截 `https://localhost`）。Capacitor 默认清单里的 `INTERNET` 已移除，且已确认 `@capacitor/android` 自己不声明它。

---

## 4. 过程中遇到的环境问题（已全部解决）

M0 的价值之一就是把这些提前引爆。四类问题：前两类是**工作区权限**问题，把会话切到完全权限后消除，相关绕行手段已全部删除；后两类是受限沙箱的固有边界，同样在完全权限下消失。

| # | 问题 | 受限沙箱下的临时手段 | 现状 |
|---|---|---|---|
| 1 | 仓库的既有目录树不可写（从模板项目复制而来，ACL 不含会话的沙箱授权项），`npm install` / Gradle 全部失败 | 在 `.m0build/` 镜像中构建，`tools/sync-to-build-mirror.ps1` 同步 | 镜像（793 MB）已删除，脚本已删除，构建回到仓库内 |
| 2 | 删除文件被拒（根目录上有继承的 `Everyone: 拒绝 DeleteSubdirectoriesAndFiles`），模板遗留的 4 个死文件删不掉 | 用注释内容替换成空编译单元 | 4 个文件已**彻底删除** |
| 3 | Vite 构建崩溃：`optimizeSafeRealPathSync()` 在 try/catch 之外调用 `exec("net use")`，受限时 `spawn` 同步抛 EPERM | `tools/vite-sandbox-shim.mjs`，利用 Vite 自己的 EISDIR 逃生分支 | 脚本已删除；`npm run web:build` 直接可用 |
| 4 | `:capacitor-android:validateSigningDebugAndroidTest` 失败（AGP 要写 `<用户目录>/.android/debug.keystore.lock`） | 只构建 app 模块的 androidTest 变体 | `gradle assembleDebugAndroidTest` 全模块直接可用 |

### 4.1 清理后暴露出的一个真实问题（已修）

绕行手段撤掉、改为构建全模块 androidTest 之后，`:capacitor-cordova-android-plugins:checkDebugAndroidTestDuplicateClasses` 失败：

```
Duplicate class kotlin.collections.jdk8.CollectionsJDK8Kt found in modules
  kotlin-stdlib-1.8.22.jar and kotlin-stdlib-jdk8-1.6.21.jar
Duplicate class kotlin.internal.jdk7.JDK7PlatformImplementations found in modules
  kotlin-stdlib-1.8.22.jar and kotlin-stdlib-jdk7-1.6.21.jar
```

模板原本的 `force` 只写在 `app/build.gradle`，够不到 Capacitor 库模块自己的 androidTest classpath；而且只处理了 jdk8，漏了 jdk7。已把规则提到根 `build.gradle` 的 `allprojects`，并补上 `kotlin-stdlib-jdk7`。

### 4.2 最终验证（全部标准命令，无绕行）

```
gradle assembleDebug assembleDebugAndroidTest   BUILD SUCCESSFUL
adb install (app + androidTest)                 Success / Success
adb shell am instrument ... ShellSmokeTest      OK (3 tests)

logcat:
  Registering plugin instance: AlarmHub
  Loading app at https://localhost
  Handling local request: https://localhost/assets/index-CBSZZDja.js
  callback: 104095350, pluginId: AlarmHub, methodName: ping
  Console: {"version":"1.0","sdkInt":36,"appId":"com.alarmhub.app","kotlinVersion":"2.2.21",...}
```

---

## 5. 遗留项（不阻塞 M1）

| 项 | 说明 |
|---|---|
| `Error injecting safe area CSS: Cannot read properties of null (reading 'style')` | Capacitor 注入脚本在 document-start 阶段运行的告警，不在本项目代码内，不影响渲染。**M1 做真实界面时要复查**它是否导致内容压到状态栏下 |
| `mediaPlayback` 前台服务在 API 36 上的启动行为 | 属于 M4，届时实测 |

截图：`.shots/m0-final.png`（当前形态）、`.shots/m0-vite.png`（Vite 打包产物）。

---

## 6. 下一步

M0 的门禁全部通过，可以进入 **M1 · 前端页面效果**（计划里你要求的确认点）：冻结 `AlarmHubBridge` 契约、设计规范、7 个页面接 mock 数据、浏览器与模拟器截图给你确认。
