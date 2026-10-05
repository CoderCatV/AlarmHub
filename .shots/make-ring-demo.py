"""Create a demo alarm that rings in N minutes, for a real-device ring test.

    python -u .shots/make-ring-demo.py [minutes] [device]
    python -u .shots/make-ring-demo.py --remove

A one-off with 响铃后删除 (FR-3.6), so dismissing it also deletes it — which is the path whose banner
failed to clear.
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

LABEL = "演示-响铃"


def main():
    # Positionals are `<minutes> [device]`, and either may be omitted — so a non-numeric first argument
    # is the device, not a bad minute count. (`--remove b9026932` used to die on
    # `int('b9026932')`.)
    positional = [a for a in sys.argv[1:] if not a.startswith("--")]
    remove = "--remove" in sys.argv
    minutes = 2
    device = "b9026932"
    for value in positional:
        if value.isdigit():
            minutes = int(value)
        else:
            device = value

    d = ui.Driver(device, 9222, 2.75, 0)
    print(f"device={device} webview pid={d.ensure_forward()}")

    def plugin(expr):
        return d.eval("(async()=>JSON.stringify(" + expr + "))()")

    existing = plugin(
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        f".filter(a=>a.label==={json.dumps(LABEL)}).map(a=>a.id)"
    )
    if isinstance(existing, list) and existing:
        plugin(f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: {json.dumps(existing)}}})")
        time.sleep(1)
        print(f"  removed {len(existing)} earlier demo alarm(s)")

    if remove:
        print("done (nothing created)")
        return 0

    epoch = subprocess.run(["adb", "-s", device, "shell", "date", "+%s"],
                           capture_output=True, text=True).stdout.strip()
    off = subprocess.run(["adb", "-s", device, "shell", "date", "+%z"],
                         capture_output=True, text=True).stdout.strip()
    sign = -1 if off.startswith("-") else 1
    digits = off.lstrip("+-")
    offset_minutes = sign * (int(digits[:2]) * 60 + int(digits[2:4])) if len(digits) >= 4 else 0
    local = (int(epoch) // 60 + offset_minutes + minutes) % (24 * 60)
    hour, minute = local // 60, local % 60

    created = plugin(
        "await window.Capacitor.Plugins.AlarmHub.saveAlarm("
        + json.dumps(
            {"hour": hour, "minute": minute, "label": LABEL, "enabled": True,
             "repeatType": "ONCE", "deleteAfterRing": True},
            ensure_ascii=False,
        )
        + ")"
    )
    alarm_id = created.get("id") if isinstance(created, dict) else None
    time.sleep(2)
    plugin("await window.Capacitor.Plugins.AlarmHub.updateSettings({})")
    time.sleep(2)

    dump = subprocess.run(["adb", "-s", device, "shell", "dumpsys", "notification", "--noredact"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    shown = "id=1002" in dump and LABEL in dump

    print()
    print(f"  闹钟已建好：id={alarm_id}，{hour:02d}:{minute:02d}（{minutes} 分钟后）")
    print(f"  通知栏现在已经出现「即将响铃」: {'是 ✅' if shown else '（再等几秒）'}")
    print()
    print("  请你在手机上：")
    print(f"    1. 等它到点响（{hour:02d}:{minute:02d}）；")
    print("    2. **上滑关掉响铃**；")
    print("    3. 下拉通知栏，看那条「即将响铃」是不是也消失了。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
