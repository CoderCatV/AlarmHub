#!/usr/bin/env python3
"""One-command verification of the real phone: install, then run every UI test against it.

WHY
---
Verifying on the phone used to be a sequence of hand-typed steps, and each one had a way to go
quietly wrong:

* the APK could be stale (a failed Gradle build leaves the previous one in place), so the test would
  pass against code that is not the code under review;
* `adb forward` names a socket containing the app's pid, so it goes stale whenever the app restarts —
  and on the phone MIUI restarts it on its own;
* the phone's screen turning off suspends the WebView and kills the DevTools socket mid-test;
* and the tests write to the user's real database, so "did this run damage anything" has to be
  answered, not assumed.

Each of those is handled in one place here. `tools/ui-drive.py` holds the transport details; this is
the sequence.

WHAT IT CHECKS
--------------
1. The phone is on adb, and says so plainly if it is not (it is usually a USB-mode problem, not a
   cable problem — Windows still sees the device).
2. The build is current: `gradle assembleDebug` is re-run, and the APK on the device is compared
   **by SHA-256** after installation, not by timestamp.
3. The alarm and group counts before and after, so a test that damages real data is caught.
4. Every UI test, over CDP touch, with its transcript saved under `.shots/`.

Usage:
    python tools/verify-phone.py                 # the whole thing
    python tools/verify-phone.py --skip-build    # reuse the APK already built
    python tools/verify-phone.py --serial XXXX   # a different device
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

APK = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
PKG = "com.alarmhub.app"
CDP_PORT = 9223

# `gradle` is a `.bat` on Windows, and `subprocess` without a shell goes through CreateProcess, which
# does **not** apply PATHEXT — so a bare "gradle" raises FileNotFoundError even though it is on PATH.
# `adb` and `node` are `.exe`, which is why this only showed up here. `shutil.which` does honour
# PATHEXT; the literal path is the last resort.
GRADLE = shutil.which("gradle") or shutil.which("gradle.bat") or r"D:\DevEnv\gradle-8.14.3\bin\gradle.bat"

# (script, label, extra args). Each is expected to print "ALL CHECKS PASSED" and exit 0.
#
# The extra args exist because the scripts grew different CLIs, and the first version of this file
# assumed they were the same: t-multiselect defaults to the `adb input` transport, which **HyperOS
# refuses** (`INJECT_EVENTS`), so on the phone it has to be told to use CDP touch. Spelling that out
# per test is honest; a caller that has to guess is how a transport detail turns into a red test.
TESTS = [
    (".shots/t-multiselect.py", "长按批量勾选删除", ["--via", "cdp"]),
    (".shots/t-wheel.py", "滚轮循环滚动", []),
    (".shots/t-editor.py", "编辑页预览与新建分组", []),
]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="b9026932")
    ap.add_argument("--skip-build", action="store_true")
    args = ap.parse_args()
    device = args.serial

    print("== 1. is the phone on adb? ==")
    out = run(["adb", "devices"]).stdout
    print(out.strip())
    if device not in out:
        # The usual cause is the USB mode, not the cable: Windows lists the device either way.
        usb = run(
            [
                "powershell", "-NoProfile", "-Command",
                "Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | "
                f"Where-Object {{ $_.InstanceId -match '{device.upper()}' }} | "
                "Select-Object -First 1 -ExpandProperty FriendlyName",
            ]
        ).stdout.strip()
        print()
        if usb:
            print(f"Windows 能看到手机（{usb}），但 adb 看不到它。")
            print("请在手机上：① 解锁并允许「USB 调试」弹窗；② 下拉通知栏把 USB 用途改成「传输文件」；")
            print("③ 确认 开发者选项 → USB 调试 还开着。这不是数据线的问题。")
        else:
            print("Windows 也没有看到这台手机 —— 数据线可能真的没插好。")
        return 2

    if not args.skip_build:
        print("\n== 2. build ==")
        p = run([GRADLE, "assembleDebug", "--console=plain"], cwd=str(ROOT / "android"))
        tail = [l for l in p.stdout.splitlines() if l.startswith(("BUILD", "e:"))]
        print("  " + ("\n  ".join(tail[-3:]) or "(no output)"))
        if p.returncode != 0:
            print("  构建失败 —— 不要继续：装在设备上的是上一次的包，测试会验证错的东西。")
            return 1

    want = sha256(APK)
    print(f"\n== 3. install ==")
    print(f"  local apk sha256 = {want}")
    p = run(["adb", "-s", device, "install", "-r", str(APK)])
    print("  " + (p.stdout.strip().splitlines() or [""])[-1])
    if "Success" not in p.stdout:
        print("  安装失败")
        return 1

    # Byte-for-byte, because a timestamp comparison has fooled us before: a failed build leaves the
    # previous APK in place and `lastUpdateTime` then matches the *old* build.
    on_device = run(["adb", "-s", device, "shell", "pm", "path", PKG]).stdout.replace("package:", "").strip()
    pulled = ROOT / ".shots" / "installed-verify.apk"
    run(["adb", "-s", device, "pull", on_device, str(pulled)])
    got = sha256(pulled) if pulled.exists() else ""
    pulled.unlink(missing_ok=True)
    print(f"  on-device sha256  = {got}")
    if got != want:
        print("  !! 设备上的包与本地不一致 —— 测试结果无效")
        return 1
    print("  逐字节一致 ✅")

    print("\n== 4. data before ==")
    before = {
        "alarms": ui.db_scalar(device, "select count(*) from alarms"),
        "groups": ui.db_scalar(device, "select count(*) from alarm_groups"),
    }
    print(f"  {before}")

    print("\n== 5. tests ==")
    # Bring the app up cold before anything reads it. After an install the process does not exist yet,
    # and on this ROM a plain `am start` can leave it running with no Activity in front — in which case
    # there is no WebView, no DevTools socket, and every test fails for a reason that has nothing to do
    # with the code under test.
    ui.Driver(device, CDP_PORT, 2.75, 0).cold_start()
    print(f"  webview sockets after cold start = "
          f"{ui.Driver(device, CDP_PORT, 2.75, 0).webview_sockets()}")
    results = []
    for script, label, extra in TESTS:
        transcript = ROOT / ".shots" / (Path(script).stem.replace("t-", "m8-") + "-phone.txt")
        # -u: Python block-buffers stdout when it is redirected, which made a running test look like a
        # hung one and an empty transcript look like a test that produced nothing.
        p = run([sys.executable, "-u", str(ROOT / script), *extra, device, str(CDP_PORT)], cwd=str(ROOT))
        transcript.write_text(p.stdout + p.stderr, encoding="utf-8", newline="\n")
        ok = "ALL CHECKS PASSED" in p.stdout
        results.append((label, ok, transcript.relative_to(ROOT)))
        print(f"  [{'OK  ' if ok else 'FAIL'}] {label}  ->  {transcript.relative_to(ROOT)}")
        if not ok:
            failed = [l.strip() for l in p.stdout.splitlines() if "[FAIL]" in l]
            for line in failed[:6]:
                print(f"        {line}")

    print("\n== 6. data after ==")
    after = {
        "alarms": ui.db_scalar(device, "select count(*) from alarms"),
        "groups": ui.db_scalar(device, "select count(*) from alarm_groups"),
    }
    print(f"  {after}")
    intact = before == after
    print(f"  用户数据未被测试改动: {'✅' if intact else '❌ ' + str(before) + ' -> ' + str(after)}")

    print("\n== summary ==")
    all_ok = all(ok for _, ok, _ in results) and intact
    for label, ok, path in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {label}  ({path})")
    print("  PASS  用户数据未变" if intact else "  FAIL  用户数据被改动")
    print()
    print("全部通过 ✅" if all_ok else "有失败项 ❌")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
