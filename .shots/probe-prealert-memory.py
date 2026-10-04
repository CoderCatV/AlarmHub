"""Why does pausing lose the "already announced" memory on the phone but not on the emulator?

Reads settings.pre_alert_notified_at straight from a pulled copy of the database at each step, so the
answer comes from state rather than from theorising. (Not via `sqlite3` on the device: the phone's shell
has no sqlite3 binary — `run-as ... sqlite3` fails with "inaccessible or not found".)

    python -u .shots/probe-prealert-memory.py [device]
"""

import json
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

LABEL = "自测-记忆"
DEVICE = sys.argv[1] if len(sys.argv) > 1 else "b9026932"
PKG = "com.alarmhub.app"


def plugin(d, expr):
    return d.eval("(async()=>JSON.stringify(" + expr + "))()")


def adb(*args, capture=True, **kw):
    """`capture=False` when stdout is redirected to a file: subprocess forbids both at once."""
    if capture:
        return subprocess.run(["adb", "-s", DEVICE, *args], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", **kw)
    return subprocess.run(["adb", "-s", DEVICE, *args], stderr=subprocess.DEVNULL, **kw)


def device_minutes_from_now(minutes):
    epoch = adb("shell", "date", "+%s").stdout.strip()
    off = adb("shell", "date", "+%z").stdout.strip()
    sign = -1 if off.startswith("-") else 1
    digits = off.lstrip("+-")
    om = sign * (int(digits[:2]) * 60 + int(digits[2:4])) if len(digits) >= 4 else 0
    local = (int(epoch) // 60 + om + minutes) % (24 * 60)
    return local // 60, local % 60


def ids(d, label):
    raw = plugin(
        d,
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        ".filter(a => a.label === " + json.dumps(label) + ").map(a => a.id)",
    )
    return raw if isinstance(raw, list) else []


def memory(tag):
    """The stored instant, read from a pulled copy of the database with the WAL applied."""
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        for suffix in ("", "-wal", "-shm"):
            target = Path(tmp) / ("db" + suffix)
            # The handle is closed before adb returns, or Windows refuses to delete the temp directory
            # ("另一个程序正在使用此文件") when the TemporaryDirectory is cleaned up.
            with open(target, "wb") as sink:
                adb("exec-out", "run-as", PKG, "cat", "databases/alarmhub.db" + suffix,
                    capture=False, stdout=sink)
        con = sqlite3.connect(str(Path(tmp) / "db"))
        try:
            row = con.execute("select pre_alert_notified_at from settings limit 1").fetchone()
        except sqlite3.Error as e:
            print(f"    [{tag}] query failed: {e}")
            return None
        finally:
            con.close()
        value = row[0] if row else None
        print(f"    [{tag}] pre_alert_notified_at = {value}")
        return value


def recompute(d, note):
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.updateSettings({})")
    time.sleep(1.5)
    print(f"  (recomputed: {note})")


def main():
    d = ui.Driver(DEVICE, 9222, 2.75, 0)
    print(f"device={DEVICE} pid={d.ensure_forward()}")

    for old in ids(d, LABEL):
        plugin(d, "await window.Capacitor.Plugins.AlarmHub.deleteAlarms({ids: [" + str(old) + "]})")
    time.sleep(1)

    hour, minute = device_minutes_from_now(20)
    created = plugin(
        d,
        "await window.Capacitor.Plugins.AlarmHub.saveAlarm("
        + json.dumps({"hour": hour, "minute": minute, "label": LABEL, "enabled": True,
                      "deleteAfterRing": False}, ensure_ascii=False)
        + ")",
    )
    alarm_id = created.get("id") if isinstance(created, dict) else None
    print(f"alarm id={alarm_id} at {hour:02d}:{minute:02d} (20 min out)")
    time.sleep(2)
    recompute(d, "create")
    created_memory = memory("after create")

    print("  --- pause ---")
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.setAlarmEnabled({id: " + str(alarm_id) + ", enabled: false})")
    time.sleep(2)
    recompute(d, "pause")
    paused_memory = memory("after pause")

    print("  --- resume ---")
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.setAlarmEnabled({id: " + str(alarm_id) + ", enabled: true})")
    time.sleep(2)
    recompute(d, "resume")
    resumed_memory = memory("after resume")

    dump = adb("shell", "dumpsys", "notification", "--noredact").stdout
    printed = "id=1002" in dump and LABEL in dump
    print(f"  notification present after resume: {printed}")

    print()
    print(f"  VERDICT: memory kept through the pause: {paused_memory == created_memory}")
    print(f"           memory kept through the resume: {resumed_memory == created_memory}")
    print(f"           nothing re-announced: {not printed}")

    print("  --- cleanup ---")
    for i in ids(d, LABEL):
        plugin(d, "await window.Capacitor.Plugins.AlarmHub.deleteAlarms({ids: [" + str(i) + "]})")
    time.sleep(1)
    recompute(d, "cleanup")
    return 0


if __name__ == "__main__":
    sys.exit(main())
