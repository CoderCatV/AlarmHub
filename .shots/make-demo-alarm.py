"""Create one demo alarm ~25 minutes out, so the user can tap 「关闭闹钟」 on the real notification.

The only thing a test cannot do here: the action's receiver is `exported="false"`, and `adb shell`'s
broadcast is only ever `Enqueued` — never delivered — so "a finger can reach it" needs a finger.

    python -u .shots/make-demo-alarm.py            # create
    python -u .shots/make-demo-alarm.py --remove   # clean up
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

DEVICE = "b9026932"
LABEL = "演示-预告"
MINUTES = 25


def plugin(d, expr):
    return d.eval(f"(async()=>JSON.stringify({expr}))()")


def device_minutes_from_now(device, minutes):
    epoch = subprocess.run(
        ["adb", "-s", device, "shell", "date", "+%s"], capture_output=True, text=True
    ).stdout.strip()
    offset_raw = subprocess.run(
        ["adb", "-s", device, "shell", "date", "+%z"], capture_output=True, text=True
    ).stdout.strip()
    sign = -1 if offset_raw.startswith("-") else 1
    digits = offset_raw.lstrip("+-")
    offset_minutes = sign * (int(digits[:2]) * 60 + int(digits[2:4])) if len(digits) >= 4 else 0
    local = (int(epoch) // 60 + offset_minutes + minutes) % (24 * 60)
    return local // 60, local % 60


def ids_with_label(d, label):
    raw = plugin(
        d,
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        f".filter(a => a.label === {json.dumps(label)}).map(a => a.id)",
    )
    return raw if isinstance(raw, list) else []


def main():
    remove = "--remove" in sys.argv
    d = ui.Driver(DEVICE, 9222, 2.75, 0)
    print(f"phone webview pid={d.ensure_forward()}")

    existing = ids_with_label(d, LABEL)
    if existing:
        print(f"  removing {len(existing)} earlier demo alarm(s)")
        plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: {json.dumps(existing)}}})")
        time.sleep(1)

    if remove:
        print("done (nothing created)")
        return 0

    hour, minute = device_minutes_from_now(DEVICE, MINUTES)
    created = plugin(
        d,
        "await window.Capacitor.Plugins.AlarmHub.saveAlarm("
        + json.dumps(
            {"hour": hour, "minute": minute, "label": LABEL, "enabled": True, "deleteAfterRing": False},
            ensure_ascii=False,
        )
        + ")",
    )
    alarm_id = created.get("id") if isinstance(created, dict) else None
    time.sleep(1)
    # A write re-registers the schedule, and the pre-alert check runs right after — so the notification
    # should already be up. Confirm it rather than telling the user to look for something that may not
    # be there.
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.updateSettings({})")
    time.sleep(2)

    dump = subprocess.run(
        ["adb", "-s", DEVICE, "shell", "dumpsys", "notification", "--noredact"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    shown = "id=1002" in dump and LABEL in dump

    print()
    print(f"  闹钟已建好：id={alarm_id}，{hour:02d}:{minute:02d}（{MINUTES} 分钟后），备注「{LABEL}」")
    print(f"  通知栏已经出现: {'是 ✅' if shown else '还没有（再等几秒/下拉通知栏看看）'}")
    print()
    print("  请你在手机上：")
    print("    1. 下拉通知栏，找到「%02d:%02d 即将响铃」那条；" % (hour, minute))
    print("    2. **直接点这条通知的正文**（不是右边的按钮——这台机器不显示动作按钮）；")
    print("    3. 看那条通知是否消失。")
    print()
    print("  用完告诉我，我会删掉这条闹钟（或你自己跑 --remove）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
