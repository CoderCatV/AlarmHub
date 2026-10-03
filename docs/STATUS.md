# 当前进度与交接说明

> 这是一个**活文档**，每次暂停工作前更新。恢复工作时先读这一份。

| 项 | 值 |
|---|---|
| 当前里程碑 | **M8 · 真机试用后的五项改进 —— 进行中**（原 M6 基线：单测 102 + 仪测 28 + 调度回归 5/5 全部保持）。第 2 项长按批量删除、第 3 项滚轮循环滚动**已完成并在真机验证**；第 1 项分组管理确认 M1c 已实现，只剩"编辑页直接新建分组"一处缺口。见 [M8-STATUS](M8-STATUS.md)；更早的真机问题见 [手机反馈](手机反馈-滚轮与保存.md) |
| 下一个里程碑 | **M7 · 端到端联调与真机验证**（真机部分照着 [MACHINE-CHECKLIST.md](MACHINE-CHECKLIST.md) 走） |
| 相关文档 | **[继续-明天](继续-明天.md)（下次开工先读这一份）** · [现状存档-关机前](现状存档-关机前.md)（设备与未完成项的现场留档） · [PRD](PRD.md) · [TECH-STACK](TECH-STACK.md) · [DEVELOPMENT-PLAN](DEVELOPMENT-PLAN.md) · [M0](M0-STATUS.md) · [M2](M2-STATUS.md) · [M3](M3-STATUS.md) · [M4](M4-STATUS.md) · [M5](M5-STATUS.md) · [M6](M6-STATUS.md) · [M8](M8-STATUS.md) · [真机上手·零基础](真机上手-零基础.md) · [真机点检清单](MACHINE-CHECKLIST.md) · [手机反馈处理](手机反馈-滚轮与保存.md) · [待用户·真机复验（本轮）](待用户-真机复验（本轮）.md) |

---

## 1. 已完成并验证

| 里程碑 | 内容 | 验证证据 |
|---|---|---|
| **M0** | 工程基线、包名迁移、Kotlin 接入、`kotlin-stdlib` 冲突、前端构建链、最小桥 | [M0-STATUS.md](M0-STATUS.md)：构建 + 装机 + 仪测 `OK (3 tests)` |
| **M1a** | 冻结 `AlarmHubBridge` 契约、设计规范、主列表页、暂停面板 | CDP 实测暂停算法（跳过 7 个周末 → 10月25日恢复）+ `.shots/m1a-*.png` |
| **M1b** | 滚轮时间选择器、编辑页（5 种重复规则 + 铃声/震动/渐强/贪睡/超时/响铃后删除） | CDP 实测保存往返（12 → 13 行）+ `.shots/m1b-*.png` |
| **M1c** | 分组管理（拖拽 + 键盘排序）、设置页、权限体检页、空状态 | CDP 实测两种排序、跳转兜底、导航层级、空状态 + `.shots/m1c-*.png` |
| **M2** | `domain/` 业务规则层（PRD §5 全部规则，纯 Kotlin） | [M2-STATUS.md](M2-STATUS.md)：单测 **102 tests, 0 failures**；无 Android classpath 的纯 JVM 上复跑 `OK (102 tests)` |
| **M3** | Room 数据层 + `AlarmScheduler` + `AlarmReceiver` + 系统事件重注册 | [M3-STATUS.md](M3-STATUS.md)：仪测 26；`tools/m3-acceptance.ps1` 调度场景 **5/5**；`dumpsys alarm` 独立核对主触发确为 `setAlarmClock` |
| **M4** | 响铃链路：前台服务 + 渐强/震动 + 响铃页 + 通知 + 贪睡 + 结束流程 | [M4-STATUS.md](M4-STATUS.md)：仪测 **28 tests**（含 DB v1→v2 真迁移）；锁屏 + 静音下实测响铃；贪睡 1/2→2/2→拒绝；超时 60.004 s 精确停止 |
| **M5** | 权限体检（七项检测）、候选 Intent 序列跳转 + FR-5.3 兜底、主界面常驻警示条、首次启动引导、FR-5.6 文案 | [M5-STATUS.md](M5-STATUS.md)：模拟器上七项判定与四组跳转全部实测留证；强制走兜底验证过；通知走运行时对话框并自动刷新行状态。**「跳转命中」在真机上仍是未验证** |
| **M6** | `AlarmHubPlugin` 契约全量实现（23/23）、前端切真、保存即重算、浏览器降级 | [M6-STATUS.md](M6-STATUS.md)：**真实触摸**点一次暂停 → `run-as` 查库确认 `pause_until` 落库 + `last_trigger_at` 与 `dumpsys alarm` 一并推迟一天；界面新建的闹钟到点真的响（偏差 6 ms）；批量转换数量与界面一致 |
| **M8** | 长按批量勾选删除、滚轮循环滚动、工具链（CDP 触摸注入 / UI 驱动 / 乱码检查） | [M8-STATUS.md](M8-STATUS.md)：真机 28 项断言全绿 ×2；滚轮连走 30 格跨两次边界每一步恰好 +1；过程中抓到"长按会勾错行"（选择栏替换大时钟导致列表位移） |

**用户已确认**：M1a / M1b 的视觉与交互"没问题"。

### 1.1 过程中抓到的真 bug（都**不是**靠看截图发现的）

| # | 问题 | 怎么发现的 |
|---|---|---|
| 1 | 每行闹钟的开关**全部消失** —— Vue 把未传入的 Boolean 类型 prop 强制成 `false`，我的 `showSwitch !== false` 因此反了 | DOM 探针（截图里少了开关依然"看起来成立"） |
| 2 | `VISIBLE` 未使用变量 —— 此前 1500+ 行 SFC 代码**完全没有静态检查**，`tsc` 不解析 `.vue` | 补上 `vue-tsc` 后立刻报出 |
| 3 | 分组管理页**整页白屏** —— `watch(..., {immediate:true})` 在 setup 期间同步执行，回调读到尚未初始化的 `const dragIndex`（暂时性死区） | CDP 抓到 `ReferenceError: Cannot access 'i' before initialization` |
| 4 | 设置页"运行环境"在手机上显示成"浏览器预览" —— `ping` 走的是 mock，文案与事实不符 | 看截图时发现（**M6 后自动消失**：`ping` 现在真的打到原生） |
| 5 | 暂停恢复时刻当天 **00:00 响的闹钟被跳过** —— `nextRingAt` 误用了「严格晚于恢复时刻」 | M2 单测 |
| 6 | **暂停期内再次暂停会提前两天恢复** —— 已有 `pauseUntil` 没有约束逐日检查 | M2 单测 |
| 7 | **暂停到期的那一刻被当成一次响铃** —— `AlarmReceiver` 对 `TriggerKind.PAUSE` 也跑 PRD §5.4 校验 | M3 验收 |
| 8 | **`RingForegroundService.stop()` 会反复"启动"服务** —— 用 `startService` 发停止指令 | M4 实测（日志刷屏） |
| 9 | **跳转兜底会把失败报成成功** —— `open()` 返回了兜底页自己的结果，于是 `opened` 永远为 `true`，FR-5.3 那句"没能直接打开"永远不会出现。**恰好是在最需要它的 HyperOS 上** | M5：强制走兜底时输出 `candidates=0` 却 `opened=true`，两者自相矛盾 |
| 10 | 体检页的 FR-5.6 文案写着 `**任何**` —— Markdown 语法混进 `.vue` 模板，用户看到的就是两个星号 | 改 M5 文案时回看整段才发现 |
| 11 | **仪测会删掉用户的数据库** —— `MigrationTest` 的 `@After` 删的是 `AppDatabase.NAME`，也就是应用正在用的那个文件。M6 之前没人写用户数据所以"看起来没问题"；M6 之后等于**跑一次仪测删一次闹钟** | 实测：跑测试前库里有闹钟，跑完只剩播种结果 |
| 12 | `MigrationTest` 里「表应该是空的」这条断言 —— 断言的是**用户有什么数据**，不是 schema。M4 已因同一原因删掉了分组那半句，闹钟那半句留着，因为当时"界面建不出闹钟"这个假设还成立 | M6 第一次真的失败：`no alarm exists yet expected null, but was:<AlarmEntity(id=1, …)>` |
| 13 | `pickRingtone` 的选择器把「跟随系统默认铃声」显示成 **None / Currently set**，也就是宣称这条闹钟静音 | 打开选择器截图时发现 |
| 14 | **时间滚轮在 24 小时制下根本没法选 12 点之后** —— `hourFromIndex()` 把滚轮下标按 12 小时制换算却照用在 24 小时制上：下标 12 → 小时 **0**（所以"只能拖到 11 点"），下标 13~23 → 小时 **25~35**（越界 ⇒ 没有任何一项被选中 ⇒ **整列灰字**）。灰字时保存必失败，因为原生 `LocalTime.of(26,…)` 抛 `DateTimeException`，而旧版本把这个异常吞掉了 —— **"滚轮选不中"和"保存点不动"是同一个 bug 的两半** | **真机首次试用**，而且是靠用户把两张截图（黑字 / 灰字）和"灰字就存不了"这句话联系起来才定位的。M1b 在浏览器里、M6 用 DevTools **都是点数字来选的，从没点过 12 点以后**（§1.4） |
| 15 | **保存失败时界面一声不响** —— 原生的拒绝逃出点击处理函数变成"未处理的 Promise 拒绝"，屏幕无变化、按钮从「保存中」变回「保存」 | 追 #14 时发现的：它正是"保存点不动"看不见原因的原因。修好之后，同一类失败会显示红条（已用"编辑期间闹钟被删掉"实测） |
| 16 | `Alarm` 的 `init` 注释写着「hour/minute 故意不在这里检查，`LocalTime.of` 会大声拒绝」 | **被 #14 证伪**：那个"大声拒绝"发生在写入路径深处，对重复闹钟甚至发生在**行已经插库之后**。已改为构造即 `require`，原文注释连同更正一起写在代码里 |
| 17 | **MIUI 的权限探针在 HyperOS 3 上 100% 失效，体检页给「自启动」报了假红灯** —— 该 ROM 把这些开关注册成**只有编号、没有可查询字符串名**的 AppOps（`dumpsys` 显示 `MIUIOP(10008)`，按名字查报 `Unknown operation string`），而我那九个 `android:auto_start` 之类的候选名字一个都不存在。用户的自启动明明是开的，页面却报 ✕，「2 项待处理」永远清不掉 | **真机首次试用**。修法是改成**按编号查**（反射 hidden 的 `checkOpNoThrow(int,…)`，实测在灰名单里可用）并保留字符串兜底；编号是**实验**定出来的（每 2 秒轮询 `cmd appops get`，用户依次拨三个开关）。详见 [M5-STATUS](M5-STATUS.md) §5.3 |
| 18 | **我自己的工具链 bug：用 PowerShell 往返读写源码，把整个文件的中文毁成乱码** —— `Get-Content`/`Set-Content` 往返时，这台机器的 GBK 控制台代码页把 UTF-8 源码按 GBK 解码，四个汉字变成六个互不相干的字（`tools/check-encoding.py` 里保留了这段变换的可逆判据），而且丢字符不可逆，只能整个文件重写 | 我改 `PermissionInspector.kt` 时踩到（编译报 `Syntax error: Expecting '"'`）。**教训：源码只能用编辑工具读写，绝不要经过 shell 的文本管道**。第二次踩到是在两个测试脚本上，于是加了机械检查 `python tools/check-encoding.py`（判据是"非 ASCII 片段能否 encode('gbk').decode('utf-8') 还原" —— 正常中文几乎必然失败，乱码必然成功），并验证过它对正常中文零误报 |
| 19 | **响铃结束后界面上的那一条不会消失，要重启应用才消失** —— 数据其实是对的（响铃结束流程已经把它从库里删掉/标过期），**是 WebView 这一侧的缓存陈旧**：`store.ts` 按"数据只有一个真源"把列表当缓存，但原生代码会在页面处于后台时改库（响铃结束、通知里的贪睡、过期清扫），而 M5 只在这个时机刷新了**权限**，没刷新**闹钟列表** | **用户在真机上观察到的**：「49分那条记录还在，没删除，重启后才删除」。先在模拟器上复现（后端删 1 条 → 库里 6 条、界面仍 7 行），确认 `visibilitychange` 在 Android WebView 里真的会触发（`visible→hidden→visible`，计数 1→2），再把列表加进那个回调，复测界面变成 6 行 |
| 20 | **删掉正在响的闹钟，声音不会停** —— `RingController.cancelFromOutside()`（"响铃期间闹钟被别处删掉/关掉"这条结束路径）**从来没有被任何地方调用过**。于是用户从列表里删掉正在响的那条之后：声音继续、页面还是够不到、列表里那条也没了 —— **彻底没有任何开关能碰它** | **真机实测**：用户报「一直在响，也找不到关闭按钮」，并说"已经从列表删除了那条 17:09 的"。查库确实找不到 17:09 那条，而声音还在 —— 顺藤摸到 `cancelFromOutside` 零调用。接在插件的 `write {}`（所有写操作的唯一收口）里：写完重算后顺带核对"正在响的那条还该不该响"。模拟器实测：删掉 → `ring ended: CANCELLED` → 服务销毁。**这与 M4-STATUS §4.9 记的 `ringInProgress` 是同一类错误**（文档说能用、实际没接上），而这次害到了真用户 |
| 21 | **响铃页够不到** —— `RingActivity` 声明了 `excludeFromRecents="true"`（M4 的决定："临时页面不该出现在任务切换器里"），而平台又禁止应用在解锁状态下自己把页面拉到前台（M4 §4.3）。两头一夹：**页面存在、但用户怎么点都到不了**，唯一入口变成通知栏里那个「关闭」按钮。再叠加上 `onBackPressed → moveTaskToBack`（返回键把页面推走），用户一旦离开响铃页就再也回不来 | 同上那次实测。日志里 `mainTaskId=2707 isExcludedFromRecents=true cmp=…RingActivity` 是直接证据；应用进前台的时间（17:09:36）与用户去找关闭按钮的时刻吻合。三处一起改：**去掉 `excludeFromRecents`**；**`MainActivity.onResume` 若正在响铃就把响铃页拉起来**（前台→前台的启动不受限制）；**返回键改为不离开响铃页**（并注册在 androidx 的 `onBackPressedDispatcher` 上 —— 实测 Android 16 根本不调用被弃用的 `onBackPressed` 覆盖） |
| 22 | **我自己的工具 bug：把 `adb forward` 的 socket 名写成了冒号而不是下划线** —— `localabstract:webview_devtools_remote:25004` 指向一个**不存在**的抽象 socket。`adb` 会正常接受 TCP 连接然后立刻丢弃，客户端看到的是 `FAIL: socket hang up`。它整整伪装了几轮"环境不稳定"：我为此试过换端口、加稳定延时、加重试、以及"已工作的转发不要去碰"，全部无效 —— 因为转发**从来没有指向过 WebView**。手敲同样命令却能通，只因为 shell 拼的是下划线 | 第 5 轮真机连回来后，`verify-phone.py` 三个测试全红而单独调用却正常，逼我去比对**转发列表原文**，才看见那个冒号。教训：`adb forward --list` 的输出就是"转发到底指向哪里"的唯一真相，报 `socket hang up` 时第一件事应该是看它，而不是怀疑网络或 ROM |
| 23 | **`pidof` 在进程不存在时按设计返回非零码**，而我的 `adb()` 封装把任何非零当失败 | 第 5 轮：`am force-stop` 之后调用立刻抛 `adb shell pidof ... failed`（stderr 为空）。加了专门的 `pidof()` 容忍非零 —— 否则一个**故意的答案**会被当成**命令坏了** |
| 24 | **`ui-drive` 的 `eval()` 会把对象读成 `'{'`** —— 根因**不在** CDP，而在 `eval()` 自己的兜底：解析链最后是"取输出的第一行"，而 `cdp-probe.js` 会**多行美化打印**对象、**再追加自己的尾巴**（`--- page console problems ---` + 应用的控制台噪声）。于是 `json.loads(整段)` 因为尾巴失败 → 兜底取第一行 → **第一行正好是 `{`**。它之所以危险：`'{'` 是个长得像值的值，所以它读起来像"页面返回了 `{`"，也就是页面/产品的 bug。我为此连续三轮判断错误（以为页面有语法错误、以为插件补丁没生效、以为降级分支没实现），**三轮都是工具** | 做 §2 第 7、8 项时踩到（读通知正文、读节假日行的降级态）。**已修**：`eval()` 改成先切掉探针尾巴再解析负载，并且对 `'{'` / `'['` 这种"永远不可能是真值"的结果**显式报错**而不是返回它。验证：`.shots/probe-eval-guard.py`（模拟器） |
| 24b | **修 #24 的第一版只修了一半，而且是到真机上才暴露的** —— 探针的尾巴有**两种**：页面有噪声时是 `--- page console problems ---`，**页面安静时是 `no console errors or warnings`**。我只切了前一种，于是模拟器（每次都报 `Error injecting safe area CSS`）全绿，而手机（安静）**每一次读取都退化成原文**：`verify-phone.py` 三个 UI 测试全红，报的是 `TypeError: string indices must be integers` —— 看起来像产品崩了，其实是读取层 | 第一次真机复验时抓到（同一份代码，模拟器与乱码检查全绿，唯独真机三条全 FAIL）。教训比 #22 更狠：**只在一台设备上验证工具修复，等于没验证** —— 这正是 MACHINE-CHECKLIST 「模拟器能替验什么」那一节的老问题换了个层面。现在两种尾巴都切，模拟器侧有 `.shots/probe-eval-guard.py`、手机侧有 `.shots/probe-phone-raw.py` |
| 25 | **`adb root` 在模拟器上一直可用**，所以 `DebugReceiver` 的调试通道在模拟器上**永远能走**（它要签名级权限 `permission.DEBUG`）。我一度以为"模拟器上也要先 root"是个额外步骤，其实这台 adbd 本来就是 root | 做 §2 第 7 项（通知文案）时发现：`am broadcast` 直接成功，`ringnow` / `dismiss` / `ringstate` 都能用。**这条让"通知正文"这类只能在模拟器上验的东西变得可脚本化** —— 从 `dumpsys notification` 读正文，全程不用 `uiautomator`（在这台 Android 16 上启动 UiAutomation 会把 `system_server` 卡死） |

另外补了一个**契约漏洞**：原契约没有"选铃声"的方法，而铃声必须走系统选择器才能免掉 `READ_MEDIA_AUDIO` 权限（TECH-STACK §4.6）。已加 `pickRingtone()`。

### 1.1.1 两条更正（交接页 §4 第 2、3 条，已按项目规矩落在纸面上）

**① 平台事实：HyperOS 上 `BOOT_COMPLETED` 是延迟投递的。** 实测重启后约 **60 秒**应用才被拉起、
`SystemEventReceiver` 才跑、注册才回来。**在这 60 秒内到期的闹钟会漏掉。** 这是平台行为，不是我们能修的，
但它决定了"重启后多久才该开始看"——[MACHINE-CHECKLIST](MACHINE-CHECKLIST.md) §B.2 的判读窗口因此从"几秒"改成"至少 90 秒"。

**② 划掉我自己在 M8 期间的一个错误结论：**

> ~~「AC-9 重启后重注册**失败**」~~ —— **这个结论是错的，已作废。**
>
> 真因有两条，都是观测方法的问题，不是产品的问题：
> 1. **观测窗口只有 8 秒**，而注册实际要约 60 秒才全部回来（上面那条平台事实）；
> 2. 第一次测还叠加了我自己 `am force-stop` 造成的 **stopped 状态** —— 处于 stopped 的应用
>    **收不到任何广播，包括开机**，所以那次"没注册"是我自己造出来的。
>
> 更正后的结论：**重启后重注册是好的，只是慢。** 唯一真实的缺口是那 60 秒窗口内到期的闹钟。

> 这条错误结论**只存在于会话记录里，没有写进任何文档**（全库核对过：`docs/` 下没有一处把它当结论），
> 所以这里不是划掉一句话，而是**把更正补记在案** —— 否则下一个会话读不到"我错过一次、错在哪"，
> 只会在 §B.2 里看到一条没有解释的"等 90 秒"。这条更正随交接页 §4 第 3 条一起做。
>
> **仍然待真机复验**：AC-9 本身（重启 → 90 秒后注册全部回来、且接下来能正常响）要在手机上走一遍才算数，
> 见 [MACHINE-CHECKLIST](MACHINE-CHECKLIST.md) §B.2。

### 1.2 平台陷阱（不是产品的错，但每次都能骗人半天）

| # | 陷阱 | 这次在哪一层遇到 |
|---|---|---|
| 1 | **`--ei` 是 Int，`getLongExtra` 静默返回默认值** | M4 的 `DebugReceiver`（[M4-STATUS](M4-STATUS.md) §4.6） |
| 2 | **`PluginCall.getLong` 对 `Int` 类型的 extra 返回默认值**（`PluginCall.java:196`）—— 同一个坑换了**另一层** | M6 的桥接层（[M6-STATUS](M6-STATUS.md) §3.2） |
| 3 | **`JSONObject.put(key, null)` 会删掉这个键**，JS 侧拿到 `undefined` 而不是 `null`，而 `x !== null` 对 `undefined` 成立 | M6（[M6-STATUS](M6-STATUS.md) §3.3） |
| 4 | **Capacitor 的插件调用无法 resolve 成裸数组**（信封永远是 `JSObject`） | M6（[M6-STATUS](M6-STATUS.md) §3.1） |
| 5 | **API 30+ 的包可见性**：不声明 `<queries>` 就拉不起 MIUI 的私有设置页，而失败的样子和"MIUI 改了组件名"一模一样 | M5（[M5-STATUS](M5-STATUS.md) §4.4） |
| 6 | **MIUI 的私有开关是 AppOps 不是权限**，而且 **HyperOS 3 把它们改成了只有编号、没有名字**（`MIUIOP(10008)`），按名字查必然失败 | M5（[M5-STATUS](M5-STATUS.md) §4.3 / §5.3） |
| 7 | **这台机器的控制台是 GBK 代码页**：`Get-Content` 读 UTF-8 源码会得到乱码，写回就毁文件。任何"读进来改一改再写回去"的源码操作都不能走 shell 文本管道 | 改 `PermissionInspector.kt` 时踩到（[STATUS](#11-过程中抓到的真-bug都不是靠看截图发现的) #18） |

### 1.3 一条方法论教训（M3 与 M4 的主要时间开销）

M3 和 M4 的验收脚本合计给出**十次假失败**，每一次产品都是对的，是脚本在说谎。两类根因：

1. **解析 adb/logcat 文本**：CRLF 混进字段、logcat 长行折行、PowerShell 截断多行结果、轮询读到上一条命令的残留标记、`logcat -t 300` 的行数窗口在 21 562 行里覆盖不到几秒。
2. **"没匹配到"被静默当成"没发生"**：`Wait-Until` 会吞掉谓词里的异常，所以**未定义函数**或**写错的模式**都表现为 `False`，而不是报错。M4 的场景 1 因此连续三轮报"没响"，而日志里明明有 `ring started for alarm=21`——真因是我的模式写成了 `alarm=21 ring started`（词序不对）。

结论已写进脚本头部：**验收脚本也会说谎，每条断言都要能对着原始日志核对**；状态读取走应用写的文件（`run-as` 读），日志读取走时间窗口，模式取自日志原文。

**M5/M6 新增第 3 类**：**断言"用户数据是空的"**。仪测跑在应用自己的进程里、用的是应用自己的数据文件，
所以「表是空的」断言的是用户干了什么，不是 schema。这类错误在 M6 之前一直潜伏着——因为界面根本写不了库——
M6 一接通就立刻爆了（§1.1 #11、#12）。

---

## 2. 下一步：M7 · 端到端联调与真机验证

> **先读 [交接-剩余工作.md](交接-剩余工作.md)。** 那一页是自包含的：当前已验证的数字、剩余工作、谁来做、
> 怎么判定、本会话新增功能的验证状态、以及三条实测过的真机限制。
>
> **更正（交接页 §4 第 1 条，已完成）**：下面这张表原来有**三行已经过时**，它把三件**已经做完并在真机验证过**
> 的事列成"还没做"。三行都已划掉并注明结论，见本表下方。**不要再照旧版安排工作。**

M5 / M6 之后还差什么：

| # | 事项 | 为什么还没做 |
|---|---|---|
| ~~1~~ | ~~**AK-14 的「跳转命中」**——小米 14 / HyperOS 上自启动与后台弹出界面页是否还在这几个组件名下~~ **→ 已定论：三个候选全部命中** | **本条已过时，更正于真机实测**：`AutoStartManagementActivity` / `APP_PERM_EDITOR` / `OtherPermissionsActivity` 三个都命中，见 [M5-STATUS](M5-STATUS.md) §5.3。原文保留在左列，是因为"当年为什么没做"这件事本身不该被抹掉 |
| ~~2~~ | ~~**MIUI 三项的 AppOps 探针**是否能在这个 HyperOS 版本上读到~~ **→ 已定论：读得到（按编号 + 反射），并修掉了一个假红灯** | **本条已过时**：HyperOS 3 把这些开关注册成只有编号的 AppOps（`MIUIOP(10008)`），按名字查必然失败；改成**按编号 + 反射 `checkOpNoThrow`** 后七项全部判定出来，并因此发现原来的「自启动」红灯是**假的**。见 [M5-STATUS](M5-STATUS.md) §5.3 |
| 3 | 授予 `SYSTEM_ALERT_WINDOW` 后，**解锁状态能否自动全屏** | M4 只证明了"未授予时拉不起来" |
| 4 | AC-2 / AC-3 / AC-4 / AC-5 / AC-9 / AC-10 / AC-12 / AC-13 的真机部分 | [MACHINE-CHECKLIST.md](MACHINE-CHECKLIST.md) §B |
| 5 | AC-11 锁屏全屏响铃（真机） | 模拟器已验（M4），真机需复现 |
| 6 | AC-17（200 个闹钟滚动） | 未做。`recomputeAll` 是全量清扫，200 行时值得实测一次耗时 |
| 7 | AC-16 深浅色截图对比（逐页） | 未做 |
| 8 | 震动听感、渐强曲线 | 模拟器无法证实，真机项 |
| 9 | ~~收尾打磨：`DebugReceiver` 的去留、契约里的两个死 API、页面栈~~ **两个死 API 已接线** | 见 [M6-STATUS](M6-STATUS.md) §7 与下面的 §4。`batchUpdateAlarms` 现在是多选栏的「启用 / 停用」，`getHolidayDataInfo` 是设置页「关于」里的「节假日数据」一行（[交接](交接-剩余工作.md) §7.3）；`DebugReceiver` 与页面栈仍未定 |
| 10 | **滚轮的"速度上限"版本**（快速甩动仍能跨多格、但平滑落在最近一格） | 现在是一次手势最多一格刻度：不会选错，但跨大范围要多滑几次。等用户在分钟列上试过再定，见 [手机反馈](手机反馈-滚轮与保存.md) §6 |
| ~~11~~ | ~~**保存失败的三种可能**还没定论~~ **→ 已定论：就是滚轮 12/24 混用那一个 bug** | **本条已过时**：不是"三种可能待定"，真因是 `hourFromIndex()` 把滚轮下标按 12 小时制换算却照用在 24 小时制上（§1.1 #14）；用户的手机正是 24 小时制。修完之后同一类失败会显示红条（§1.1 #15），所以"看不到原因"这一半也一并解决了。见 [手机反馈-滚轮与保存.md](手机反馈-滚轮与保存.md) §4 |

> 上表第 1、2、11 行是**被证伪的旧结论**：原文划掉保留、右列写明更正与出处 —— 项目规矩是"被证伪的说法要划掉并注明更正，不能静默改写"。
> 第 4 行里的 AC-9 另有更正（重启后重注册**不是失败**，是 HyperOS 延迟投递），见 [交接-剩余工作.md](交接-剩余工作.md) §2.1 与 [M8-STATUS](M8-STATUS.md) §10.1。

> 这一步的检测逻辑在模拟器上可验证，但**跳转能否命中只有真机能证明**。届时需要你配合。

---

## 3. 恢复开发环境

每次新会话都要设一次环境变量，其余按需：

```powershell
# 1. 环境变量（必须）
. .\tools\env.ps1

# 2. 模拟器（若已停止）
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd dsh_pixel -no-window `
    -gpu swiftshader_indirect -no-snapshot -no-boot-anim -no-audio

# 3. 前端开发服务器（浏览器实时预览，若已停止）
npm run web:dev          # http://127.0.0.1:5173/  —— 浏览器里**自动**用 mock

# 4. 类型检查 + 构建 + 装机
npm run web:typecheck    # vue-tsc，检查 .vue 的 script 与模板
npm run web:build
npx --no-install cap sync android
cd android; gradle :app:assembleDebug --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 5. 纯逻辑单测（秒级，不需要设备）
cd android; gradle :app:testDebugUnitTest --console=plain

# 6. 仪测（Room / 调度 / 迁移；connectedAndroidTest 在沙箱里跑不了，必须走 adb）
gradle :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.alarmhub.app.data.AlarmRepositoryTest `
    com.alarmhub.app.test/androidx.test.runner.AndroidJUnitRunner

# 7. 端到端验收（调度 5 个场景，约 6 分钟；会真的等到点）
.\tools\m3-acceptance.ps1

# 8. 手动响铃（调试通道，需要 debug 构建）
$peer = 'com.alarmhub.app/.debug.DebugReceiver'
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd inject --ei at 30 --es type DAILY
adb shell run-as com.alarmhub.app cat files/debug-result.txt     # -> alarmId=N
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd ringnow --el alarm N
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd ringstate
adb shell run-as com.alarmhub.app cat files/debug-result.txt

# 9. 权限体检的诊断通道（M5 新增，真机点检靠它）
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd permissions
adb shell run-as com.alarmhub.app cat files/debug-result.txt
adb shell am broadcast -a com.alarmhub.app.debug.COMMAND -n $peer --es cmd permissionjump --es key autostart
adb shell run-as com.alarmhub.app cat files/debug-result.txt

# 10. 直接查数据库（M6 之后的界面操作都可以用它核对）
adb shell "run-as com.alarmhub.app sqlite3 -header -column databases/alarmhub.db 'select id,name,pause_until from alarm_groups;'"
```

> ⚠️ 第 10 条的引号形式是有讲究的：`adb shell run-as … sqlite3 db "select …"` 会让 adb 把引号吃掉，
> 报 `incomplete input`。要用**外层单引号、内层交给设备的 shell**。

> 第 7、8、9 步需要 **debug 构建**：验收脚本读应用私有目录里的结果文件（`run-as` 只在 debug 可用），
> 而调试通道（`DebugReceiver` + `permission.DEBUG`）只声明在 `src/debug/AndroidManifest.xml` 里，
> **release 构建中完全不存在**（已验证）。

### 3.1 M6 之后前端怎么选实现

| 场景 | 走哪个实现 | 怎么判断 |
|---|---|---|
| 装进手机/模拟器 | **原生**（`AlarmHubPlugin`） | 主界面右上角**没有**「演示数据」角标；设置页「运行环境」显示「本机数据库」 |
| 浏览器 `npm run web:dev` | **mock** | 角标「演示数据」；设置页显示「演示数据（未接入原生）」 |
| 容器内想临时看 mock | 构建时 `VITE_BRIDGE=mock` | 同上，角标会出现 |

mock 仍然保留两个**仅供审阅**的开关（原生构建里 `usingMock` 为 false）：

```js
// 通过 CDP 执行
window.__alarmhubMock                                   // 直接操作 mock 数据
sessionStorage.setItem('alarmhub:scenario', 'empty')    // 然后 location.reload() 看空状态
sessionStorage.removeItem('alarmhub:scenario')          // 恢复演示数据
```

### 3.2 两个环境前提

- **文件沙箱必须是「完全权限」。** 本仓库的既有目录树（`android/`、`node_modules/` 等）不带受限沙箱的写入授权，受限模式下 `npm install` 和 Gradle 都会失败。详见 [TECH-STACK](TECH-STACK.md) §6.2。
- **模拟器复现不了 HyperOS 的后台管控。** 该风险只能在用户的小米 14 真机上验证（M5 / M7）。

### 3.3 上一轮会话改过的模拟器设置（**本次已还原**）

M4 为了测「静音下仍响铃」把铃声模式改成了 SILENT 且没有还原。M5 开始时已改回，M6 期间又因为验证
「到点真的响」拨过设备时钟，**也已还原**：

```powershell
adb shell settings get global mode_ringer    # 2 = NORMAL（已还原）
adb shell settings get global auto_time      # 1（已还原，实测回到真实时间并触发 TIME_SET 重算）
adb shell settings get system volume_alarm   # 6 = 最大（M4 留下，未动）
adb shell settings get global zen_mode       # 0 = 关闭
```

若后续测试依赖"响铃模式"，先 `adb shell cmd audio set-ringer-mode NORMAL`。

### 3.4 写验收脚本时已经踩过的坑（不要重踩）

`tools/m3-acceptance.ps1` 在 M3/M4 期间累计给出**十次假失败，产品每次都是对的**。根因只有两类，都已修，但重新改这个脚本时极易复发：

| 坑 | 后果 | 现在的做法 |
|---|---|---|
| **`Wait-Until` 会吞掉谓词里的异常** | 函数**未定义**或**模式写错**都只表现为 `False`，于是「没匹配到」= 「没发生」，看起来像产品缺陷 | 改脚本后先把每条模式**对着实时日志**（`adb logcat -d \| Select-String`）验证一遍再跑 |
| 解析 adb / logcat 的**文本** | CRLF 混进字段、logcat 长行折行、PowerShell 截断多行 `data=` 结果、`logcat -t N` 的行数窗口在 2 万行里只覆盖几秒 | 状态读应用写的文件（`run-as`）；日志读用**时间窗口**（`logcat -T <设备epoch>.000`）；模式取自日志原文（**词序**要对，例如 `ring started for alarm=21`，不是 `alarm=21 ring started`） |

**M5 / M6 追加第 3 条**：**不要断言"用户数据是空的"**。仪测与应用共用进程和数据文件，
这类断言测的是用户干了什么而不是代码；M6 一接通界面写库就立刻爆了（§1.1 #11、#12）。
要断言 schema，就让 SQL 去 prepare 那一列（`cursor.getColumnIndexOrThrow`），别去数行。

### 1.4 模拟器能替我验什么、不能替我验什么（真机试用后的校准）

用户第一次在小米 14 上试用就抓到 #14。回头看，这不是"模拟器不够真"，而是**我的验证动作选错了**：

| 验证手段 | 覆盖到的 | **漏掉的** |
|---|---|---|
| 浏览器预览 | 布局、样式 | 触摸、滚动惯性 |
| CDP（DevTools 协议） | DOM、事件处理、往返数据 | **真实手势**：`element.click()` 不产生滚动惯性 |
| `adb shell input tap` | 真实触摸点按 | 拖动 / 滑动 |
| **`adb shell input swipe`** | **真实拖动手势** | 这一条我直到用户反馈后才第一次用 |

滚轮这种"只对手势有意义"的控件，用点击去验等于没验——它能点、能选中、数据也对，就是**拖不动**。
结论：**凡是靠手势才存在的交互，验收必须用手势**（`input swipe` / `input draganddrop`），
点击只能证明另一半。

**再加第 2 条**：**"改完之后从没走到过的分支"等于没改过**。#14 的 `hourFromIndex` 只在下标 ≥ 12 时出错，
而我历次验证里选的小时**从没超过 11**（浏览器、CDP、`input swipe` 全都一样）。
所以验收一个函数时，真正要问的是"**输入空间里哪一段我一次都没碰过**"，而不是"我跑了几个用例"。
现在这条也写进了 [MACHINE-CHECKLIST](MACHINE-CHECKLIST.md) 的判读原则。

一句话：**验收脚本也会说谎，每条断言都要能对着原始日志核对。**

---

## 4. 悬置的设计问题

M1a/M1b 的三条视觉项已降级为可选打磨（用户已答复"页面样式没问题"），真要在意就等 M7 真机一起看。

| # | 问题 | 位置 | 状态 |
|---|---|---|---|
| 1 | 行高偏大，一屏只看得到 4 个闹钟 | `components/AlarmRow.vue` + `tokens.css` 的 `--fs-clock` | 可选打磨 |
| 2 | 悬浮「+」按钮在滚动中会盖住分组头部的开关 | `pages/AlarmListPage.vue` 的 `.fab` | 可选打磨 |
| 3 | 分组整组开关＝永久停用，「暂停」按钮＝临时暂停，区分是否够清楚 | `components/GroupSection.vue` | 可选打磨 |

**M4 新增**：解锁状态下应用无法自己拉起响铃页（平台限制）—— ~~M5 的权限体检要针对它引导 `SYSTEM_ALERT_WINDOW` 与 `USE_FULL_SCREEN_INTENT`~~
→ **M5 的更正（已完成）**：两条权限都已声明并纳入体检页；非 MIUI 上「后台弹出界面」这一行直接映射到
`SYSTEM_ALERT_WINDOW`（[M5-STATUS](M5-STATUS.md) §3.2）。**授予之后行为是否改善仍待真机验证。**
原文保留在上面，是因为"M5 要做"与"M5 做完了"是两件事。

**M5 新增一条**：

| # | 问题 | 位置 | 状态 |
|---|---|---|---|
| 4 | 从主界面警示条进体检页后，「返回」回到的是**设置页**而不是主界面 —— `state/nav.ts` 是单路由，没有真正的历史栈 | `App.vue` 的 `PermissionsPage` 分支 | 待定（[M5-STATUS](M5-STATUS.md) §7 第 6 条） |

**M6 新增两条**：

| # | 问题 | 位置 | 状态 |
|---|---|---|---|
| 5 | ~~契约里 `getHolidayDataInfo` 与 `batchUpdateAlarms` 没有任何页面调用~~ **→ 已接线（不再待定）** | `web/src/bridge/types.ts` | 已接：多选栏「启用 / 停用」走 `batchUpdateAlarms`；设置页「节假日数据」走 `getHolidayDataInfo`。模拟器实测见 [交接](交接-剩余工作.md) §7.3 |
| 6 | 仪测与被测应用共用数据目录（"删库"已修，但 `AppDatabase.build(context)` 在别处仍指生产库名） | `androidTest/` | M7 打磨 |

---

## 5. 代码地图

```
web/src/
  bridge/    types.ts（冻结的跨语言契约，M6 未改）· mock.ts（浏览器预览用）· index.ts（选实现
             + 容器内的数组信封适配器）
  state/     store.ts（单一响应式状态 + 权限探针的共享状态）· nav.ts（手写页面栈）
  styles/    tokens.css（设计变量，深浅两套）· base.css
  utils/     format.ts（显示格式化）· color.ts（ARGB ↔ CSS）
  components/ ToggleSwitch · AlarmRow · GroupSection · PauseSheet
              WheelTimePicker · FormRow · PermissionBanner · PermissionGuide
  pages/     AlarmListPage · AlarmEditorPage · GroupsPage · SettingsPage · PermissionsPage

android/app/src/main/
  assets/holidays.json        2025 / 2026 法定节假日与调休（数据与代码分离，年度替换只需换这个文件）
  java/com/alarmhub/app/
    domain/                   纯 Kotlin，零 Android 依赖，全部有单测（M2 产出）
      TimeSource.kt           时钟注入缝；全项目只有 TimeSource.system 一处读系统时间
      DomainServices.kt       给 M3 用的便捷入口（注入 TimeSource 的规则调用）
      model/                  Alarm · Group · Settings · RepeatType · WeekdayMask
                              AlarmRule · AlarmSchedule（PRD §4）
      calendar/               HolidayCalendar 接口 · Holidays 数据 · Bundled/WeekdayOnly/Chained
                              HolidayDataParser（JSON → Holidays，PRD FR-6）
      json/                   MiniJson（手写的极小 JSON 读取器，避免 org.json 打进单测）
      schedule/               ScheduleMath（时间换算 + 星期掩码位序）
                              RepeatRule（PRD §5.2 + 保存期校验）
                              NextRingCalculator（PRD §5.1 / §5.5）
                              PauseResolver（PRD §5.3）
                              RingTimeValidator（PRD §5.4）
    data/                    Room 数据层（M3 产出）
      db/Entities.kt          alarm_groups · alarms · settings（含 last_trigger_at）
      db/AlarmDao.kt          DAO；PRD FR-1.4 的跨表移动走 @Transaction
      db/AppDatabase.kt       WAL + 类型转换器 + schema 导出到 app/schemas（已提交）
                              build(context, name = NAME) —— name 是给仪测用的草稿库（M6）
      Mappers.kt              领域 ↔ 实体的唯一转换点（顶层扩展函数）
      BuiltInGroups.kt        三个内置分组的固定 id + 幂等播种
      AlarmRepository.kt      唯一读写入口；暂停/停用/批量转换
    alarm/                   M3 产出
      AlarmScheduler.kt       主触发 setAlarmClock / 冗余前沿 / pauseUntil 恢复触发 / 贪睡重排
      AlarmReceiver.kt        触发分流：PAUSE→重算，SNOOZE→重响，MAIN/PRE→§5.4 校验后响铃
      SystemEventReceiver.kt  BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED / 包更新 → 全量重算
    ring/                    M4 产出
      RingController.kt       响铃状态机：五条结束路径的唯一收敛点 + 响铃后收尾
      RingForegroundService.kt mediaPlayback 前台服务，持有播放器与超时定时器
      RingActivity.kt         响铃页（showWhenLocked + turnScreenOn），只渲染不决策
      RingNotifications.kt    独立高优先级渠道 + fullScreenIntent + 通知内「关闭」
      RingActionReceiver.kt   通知里的关闭 / 贪睡
      RingRequest.kt          触发瞬间的状态快照（响铃期间用户改闹钟也不影响本页）
      media/AlarmAudioPlayer.kt  USAGE_ALARM 播放 + 渐强 + 震动
    permissions/             M5 产出
      PermissionModels.kt     PermissionKey（线上名字）/ PermissionStatus / ProbeResult
      PermissionInspector.kt  七项判定（三态：已满足 / 未开启 / 本机不适用）+ MIUI AppOps 探针
      PermissionSettingsLauncher.kt  每项一串有序候选 Intent + FR-5.3 兜底
    bridge/                  M6 产出
      AlarmHubPlugin.kt       契约 23 个方法的唯一实现；写操作统一 write { } 后全量重算
      BridgeJson.kt           领域 ↔ JSON 的唯一转换点（num() / putNullable() 等陷阱的收口处）
    AlarmHubApp.kt            进程装配：DB / Repository / 节假日 / Scheduler / 响铃渠道 + 节假日元信息
android/app/src/debug/       调试专用源集：DebugReceiver（含 M5 的 permissions / permissionjump）
                             + permission.DEBUG + 其 manifest —— release 构建里完全不存在（已验证）
tools/       env.ps1（环境变量）· cdp-probe.js（DOM 探针）· m3-acceptance.ps1（端到端验收）
android/app/schemas/         Room 导出的 schema JSON（已提交，供未来迁移核对）
```

**三条不可违反的架构不变量**（详见 [TECH-STACK](TECH-STACK.md) §4.1）：业务规则只在原生 `domain/` 实现一份；响铃链路不依赖 WebView；数据只有一个真源。

> M6 之后第 3 条有了新的含义：**界面写的就是原生库**。所以「页面上看到的」与「`run-as` 查出来的」
> 必须是同一件事——这也正是 M6 硬验收的验法。

验证手段现在有四种，按被验证的对象分工：

| 对象 | 手段 | 命令 |
|---|---|---|
| 业务规则（PRD §5 全部） | JVM 单元测试，秒级、无需设备 | `cd android; gradle :app:testDebugUnitTest --console=plain` |
| 数据层与调度记账 | 仪测（真 SQLite / 真 Room） | 见 §3 第 6 步 |
| 「系统是否在正确的分钟唤醒应用」 | 模拟器端到端验收 + `dumpsys alarm` 独立核对 | `.\tools\m3-acceptance.ps1` |
| 界面与交互、以及**界面是否真的写了库** | 模拟器截图 + CDP DOM 探针 + **`run-as` 直接查 SQLite** | 见 §3 第 8~10 步 |
