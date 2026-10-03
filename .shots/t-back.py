"""Back-navigation test: the system back gesture must walk up UI levels, not close the app.

Three levels, in the order a user meets them:

1. **Selection mode** (long-press) — an annotation on the user's own screenshot said 「左滑也可以起到返回
   作用」, so back must leave selection mode while staying on the list.
2. **A sub-page** (设置, 分组管理, editor) — back must return to the list.
3. **The root** — back must close the app. That is what a user expects on a home screen, and it is the
   reason `MainActivity` does not swallow back unconditionally.

Transport: `adb shell input keyevent KEYCODE_BACK` on the emulator. That is the same event the left-edge
swipe delivers, so it exercises the real path (`MainActivity`'s dispatcher → `webView.canGoBack()` →
`goBack()` → `popstate` → `nav.ts`).
"""

import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

FAILURES = []
PKG = "com.alarmhub.app"


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def focus_app(d) -> str:
    """Bring the app to the front and prove it is the window that will receive the back event.

    `adb shell input keyevent` is delivered to the **foreground** app, not to "the app under test". An
    earlier version of this test pressed back while the *system* Settings app was still on top (left
    there by a previous step), so the event closed a Settings page instead — and the failure looked
    like our back handler misbehaving. Reading the DOM over CDP works even when our app is behind
    another window, which is exactly what made that trap invisible.
    """
    d.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    for _ in range(10):
        top = subprocess.run(
            ["adb", "-s", d.device, "shell", "dumpsys activity activities"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        ).stdout
        line = next((l for l in top.splitlines() if "topResumedActivity" in l), "")
        if PKG in line:
            return line.strip()
        time.sleep(1)
    raise SystemExit(f"the app never came to the front; topResumedActivity={line.strip()!r}")


def press_back(d):
    # Never press back blind: see focus_app.
    focus_app(d)
    d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)


def page(d):
    return d.eval("JSON.stringify(document.querySelector('.bar__title')?.textContent.trim() ?? 'list')")


def main():
    device = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 9222
    d = ui.Driver(device, port, 2.625, 136)
    d.keep_screen_on()
    # A cold start, so this test never inherits a history stack from whatever ran before it.
    d.cold_start()
    print(f"device={device} pid={d.ensure_forward()}")

    print("== level 1: selection mode ==")
    rows = d.rows()
    if rows:
        d.eval_touch(rows[0]["x"], rows[0]["y"], hold_ms=750)
        time.sleep(1.5)
    check("selection mode is on", d.state()["selMode"], True)
    press_back(d)
    st = d.state()
    check("back left selection mode", st["selMode"], False)
    check("and stayed on the list", page(d), "list")
    check("the app is still running", bool(d.pidof()), True)

    print("== level 2: a sub-page ==")
    d.eval("JSON.stringify(document.querySelector('.hero__settings')?.click() ?? null)")
    time.sleep(2)
    check("we are on 设置", page(d), "设置")
    press_back(d)
    check("back returned to the list", page(d), "list")
    check("the app is still running", bool(d.pidof()), True)

    print("== level 2b: two levels deep ==")
    d.eval("JSON.stringify(document.querySelector('.hero__settings')?.click() ?? null)")
    time.sleep(1.5)
    d.eval(
        "JSON.stringify(([...document.querySelectorAll('button')]"
        ".find(b => b.textContent.includes('\u5206\u7ec4\u7ba1\u7406')) || {}).click() ?? null)"
    )
    time.sleep(2)
    check("we are on 分组管理", page(d), "分组管理")
    press_back(d)
    check("one back returns to 设置", page(d), "设置")
    press_back(d)
    check("a second back returns to the list", page(d), "list")

    print("== level 3: the root closes the app ==")
    press_back(d)
    time.sleep(2)
    pid = d.pidof()
    print(f"  pid after back at the root: {pid!r}")
    # `finish()` is asynchronous and a WebView process can linger, so accept either "no pid" or
    # "MainActivity is gone"; the observable promise is that the app left the screen.
    top = run_back(lambda: subprocess.run(
        ["adb", "-s", device, "shell", "dumpsys activity activities | grep topResumedActivity"],
        capture_output=True, text=True, encoding="utf-8", errors="replace").stdout)
    left_screen = PKG not in top
    check("the app left the foreground at the root", left_screen, True)

    print()
    if FAILURES:
        print(f"FAILED ({len(FAILURES)}):")
        for f in FAILURES:
            print("  -", f)
        return 1
    print("ALL CHECKS PASSED")
    return 0


def run_back(fn):
    return fn()


if __name__ == "__main__":
    sys.exit(main())
