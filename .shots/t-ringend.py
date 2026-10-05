"""The banner must come down when the alarm rings — FR-7.10.6.

The bug this exists for: the alarm rang at 12:04, the user dismissed it, it was deleted ("ring then
delete"), and 「12:04 即将响铃」 stayed in the notification shade announcing a ring that had already
happened. The log ended at `alarm=90 deleted after ringing` with no pre-alert line after it: the banner's
refresh lived only at the end of `recomputeAll`, and the ring's ending calls `scheduler.cancel(id)` and
returns without a recompute (FR-3.6 / FR-3.7), so nothing ever took it down.

Why this is a separate script from t-prealert.py: that one drives the banner through scheduling changes,
which never ring. This one has to actually **ring**, and the debug channel can do that without waiting —
`ringnow` goes through the same RingRequest validation and the same RingController as a real trigger, and
`dismiss` runs the production ending path, `RingController.finish`. So what is exercised is the real code
minus the clock.

Needs `adb root` (the debug receiver is guarded by a signature-level permission).

    python -u .shots/t-ringend.py [device] [port] [dpr] [top]
"""

import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

FAILURES = []
LABEL = "自测-响铃后"
PKG = "com.alarmhub.app"
NOTIF_ID = 1002


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def plugin(d, expr):
    return d.eval(f"(async()=>JSON.stringify({expr}))()")


def adb(device, *args):
    return subprocess.run(["adb", "-s", device, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def debug(device, cmd, *extra):
    """Drives the app's own debug channel; the receiver requires adb root."""
    out = adb(device, "shell", "am", "broadcast",
              "-a", "com.alarmhub.app.debug.COMMAND",
              "-n", f"{PKG}/.debug.DebugReceiver",
              "--es", "cmd", cmd, *extra).stdout
    if "result=0" not in out and "Broadcast completed" not in out:
        raise RuntimeError(f"debug {cmd} did not answer: {out.strip()[:200]}")
    return out


def notif_texts(device):
    dump = adb(device, "shell", "dumpsys", "notification", "--noredact").stdout
    idx = dump.find(f"id={NOTIF_ID}")
    if idx < 0:
        return None
    segment = dump[idx : idx + 3000]
    title = re.search(r"android\.title=(?:String|CharSequence) \((.*)\)", segment)
    text = re.search(r"android\.text=(?:String|CharSequence) \((.*)\)", segment)
    return (title.group(1) if title else None, text.group(1) if text else None)


def wait_for_notification(device, want=True, timeout=25):
    deadline = time.time() + timeout
    while time.time() < deadline:
        text = notif_texts(device)
        if (text is not None) == want:
            return text
        time.sleep(1)
    return notif_texts(device)


def device_minutes_from_now(device, minutes):
    epoch = adb(device, "shell", "date", "+%s").stdout.strip()
    off = adb(device, "shell", "date", "+%z").stdout.strip()
    sign = -1 if off.startswith("-") else 1
    digits = off.lstrip("+-")
    om = sign * (int(digits[:2]) * 60 + int(digits[2:4])) if len(digits) >= 4 else 0
    local = (int(epoch) // 60 + om + minutes) % (24 * 60)
    return local // 60, local % 60


def ids_with_label(d, label):
    raw = plugin(
        d,
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        f".filter(a => a.label === {json.dumps(label)}).map(a => a.id)",
    )
    return raw if isinstance(raw, list) else []


def alarm_field(d, alarm_id, field):
    return plugin(d, f"(await window.Capacitor.Plugins.AlarmHub.getAlarm({{id: {alarm_id}}})).{field}")


def main():
    args = sys.argv[1:]
    device = args[0] if len(args) > 0 else "emulator-5554"
    port = int(args[1]) if len(args) > 1 else 9222
    dpr = float(args[2]) if len(args) > 2 else 2.625
    top = int(args[3]) if len(args) > 3 else 136

    d = ui.Driver(device, port, dpr, top)
    print(f"device={device}")
    d.keep_screen_on()
    print(f"  webview pid={d.ensure_forward()}")

    # The debug receiver is behind a signature-level permission: without root, every command below is
    # silently enqueued and never delivered, and this script would report a product bug that is not there.
    if "(root)" not in adb(device, "shell", "id").stdout and "uid=0" not in adb(device, "shell", "id").stdout:
        print("  !! adb is not root — the debug channel cannot be reached; run `adb root` first")
        return 1

    print("== 0. clean slate ==")
    for old in ids_with_label(d, LABEL):
        plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: [{old}]}})")
    time.sleep(1)
    debug(device, "wipe")
    time.sleep(1)
    wait_for_notification(device, want=False, timeout=10)

    print("== 1. a one-off 20 minutes out is announced ==")
    hour, minute = device_minutes_from_now(device, 20)
    created = plugin(
        d,
        "await window.Capacitor.Plugins.AlarmHub.saveAlarm("
        + json.dumps({"hour": hour, "minute": minute, "label": LABEL, "enabled": True,
                      "repeatType": "ONCE", "deleteAfterRing": True}, ensure_ascii=False)
        + ")",
    )
    alarm_id = created.get("id") if isinstance(created, dict) else None
    print(f"  alarm id={alarm_id} at {hour:02d}:{minute:02d}, one-off, delete after ring")
    time.sleep(2)
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.updateSettings({})")
    texts = wait_for_notification(device, want=True)
    print(f"  notification: {texts}")
    check("it is announced", texts is not None, True)
    check("the first line is the alarm's own time", (texts or ("", ""))[0], f"{hour:02d}:{minute:02d} 即将响铃")

    print("== 2. ring it (through the real ring path) ==")
    print(f"  {debug(device, 'ringnow', '--el', 'alarm', str(alarm_id)).strip().splitlines()[-1]}")
    time.sleep(2)
    state = debug(device, "ringstate").strip()
    print(f"  ringstate: {state.splitlines()[-1]}")
    check("it is ringing", "ringing=true" in state, True)

    print("== 3. dismiss it, which deletes it (FR-3.6) and must take the banner down ==")
    dismissed = debug(device, "dismiss").strip()
    for line in dismissed.splitlines():
        print(f"  {line}")
    check("the ring ended", "ringing=false" in dismissed, True)
    check("the alarm was deleted after ringing", "stillExists=false" in dismissed, True)

    gone = wait_for_notification(device, want=False, timeout=20)
    print(f"  notification: {gone}")
    check("the 「即将响铃」 banner is gone", gone, None)

    print("== 4. the same must hold for an alarm that is kept and marked 过期 (FR-3.7) ==")
    # Deliberately a different minute from step 1. Two alarms created seconds apart both "20 minutes out"
    # land on the *same* ring instant, and FR-7.10.2's rule is "one announcement per instant" — so the
    # second one is correctly silent, and asserting otherwise tests my fixture rather than the product.
    # (That collision was the first version's failure here.)
    hour2, minute2 = device_minutes_from_now(device, 21)
    kept = plugin(
        d,
        "await window.Capacitor.Plugins.AlarmHub.saveAlarm("
        + json.dumps({"hour": hour2, "minute": minute2, "label": LABEL, "enabled": True,
                      "repeatType": "ONCE", "deleteAfterRing": False}, ensure_ascii=False)
        + ")",
    )
    kept_id = kept.get("id") if isinstance(kept, dict) else None
    print(f"  alarm id={kept_id} at {hour2:02d}:{minute2:02d}, one-off, kept after ring")
    time.sleep(2)
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.updateSettings({})")
    check("it is announced", wait_for_notification(device, want=True) is not None, True)

    debug(device, "ringnow", "--el", "alarm", str(kept_id))
    time.sleep(2)
    expired = debug(device, "dismiss").strip()
    check("it was kept and marked expired", "stillExists=true" in expired and "expired=true" in expired, True)
    check("its banner is gone too", wait_for_notification(device, want=False, timeout=20), None)

    print("== cleanup ==")
    for i in ids_with_label(d, LABEL):
        plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: [{i}]}})")
    time.sleep(1)
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.updateSettings({})")
    time.sleep(1)
    check("no test alarm left behind", ids_with_label(d, LABEL), [])
    check("and no notification either", notif_texts(device), None)

    print()
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} check(s)")
        for f in FAILURES:
            print(f"  - {f}")
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
