import hashlib, os, datetime
ROOT = r"D:\Code\DeepSeek\alarm-clock"
APK = os.path.join(ROOT, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
ANDROID = [
    "android/app/src/main/AndroidManifest.xml",
    "android/app/src/main/java/com/alarmhub/app/AlarmHubApp.kt",
    "android/app/src/main/java/com/alarmhub/app/bridge/AlarmHubPlugin.kt",
    "android/app/src/main/java/com/alarmhub/app/bridge/BridgeJson.kt",
    "android/app/src/main/java/com/alarmhub/app/data/db/AppDatabase.kt",
    "android/app/src/main/java/com/alarmhub/app/domain/model/Alarm.kt",
    "android/app/src/main/java/com/alarmhub/app/permissions/PermissionModels.kt",
    "android/app/src/main/java/com/alarmhub/app/permissions/PermissionInspector.kt",
    "android/app/src/main/java/com/alarmhub/app/permissions/PermissionSettingsLauncher.kt",
    "android/app/src/debug/java/com/alarmhub/app/debug/DebugReceiver.kt",
    "android/app/src/androidTest/java/com/alarmhub/app/data/db/MigrationTest.kt",
    "tools/m3-acceptance.ps1",
]
def h(p):
    with open(p, "rb") as f: return hashlib.sha256(f.read()).hexdigest(), os.path.getsize(p)
out = []
out.append("# 交付文件清单（含真机反馈后的全部修复）")
out.append("# 生成时间: " + datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S%z"))
out.append("# 工作区不是 git 仓库（git status -> fatal: not a git repository），因此用这份哈希清单代替 git diff --stat")
out.append("")
out.append("## web/src —— 全部文件（path  bytes  sha256）")
for base, _dirs, files in os.walk(os.path.join(ROOT, "web", "src")):
    for f in sorted(files):
        p = os.path.join(base, f)
        rel = os.path.relpath(p, ROOT).replace("\\", "/")
        digest, size = h(p)
        out.append(f"{rel}  {size}  {digest}")
out.append("")
out.append("## android / tools —— 本次新增或修改的文件")
for rel in ANDROID:
    digest, size = h(os.path.join(ROOT, rel))
    out.append(f"{rel}  {size}  {digest}")
out.append("")
out.append("## 交付 APK")
digest, size = h(APK)
stamp = datetime.datetime.fromtimestamp(os.path.getmtime(APK)).strftime("%Y-%m-%d %H:%M:%S")
out.append(f"android/app/build/outputs/apk/debug/app-debug.apk  {size}  {digest}   built {stamp}")
with open(os.path.join(ROOT, ".shots", "file-scope.txt"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(out) + "\n")
print("written", len(out), "lines")
print((f"APK  {size}  {digest}"))
