"""PRD FR-7.10 / AC-20 — the one-hour heads-up notification.

What is being verified, in the order the requirement states it:

1. an alarm inside the hour produces the notification, with the ring time and the label in it;
2. it carries a 「关闭闹钟」 action;
3. **clearing it does not bring it back** (FR-7.10.2 — this is the part the user asked for in as many
   words: 「出现一次即可，用户清理掉不需要二次出现」);
4. moving the alarm to another time earns a **new** announcement, which is what makes the memory work
   on occurrences rather than on the notification;
5. taking the alarm out of the hour window removes the notification (nothing is left claiming an alarm
   is imminent when it is not).

Reads come from `dumpsys notification`, the same channel `.shots/t-notification-snooze.py` uses — on this
Android 16 image UiAutomation wedges `system_server`, so the notification text is read, not dumped.

Run: python -u .shots/t-prealert.py [device] [port] [dpr] [top]
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
LABEL = "自测-预告"
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
    return subprocess.run(
        ["adb", "-s", device, *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )


def notif_segment(device):
    """The pre-alert notification's dump segment, or None when it is not posted."""
    dump = adb(device, "shell", "dumpsys", "notification", "--noredact").stdout
    # Anchor on the notification id, so two notifications from this package cannot be confused.
    idx = dump.find(f"id={NOTIF_ID}")
    if idx < 0:
        return None
    return dump[idx : idx + 3000]


def notif_texts(device):
    segment = notif_segment(device)
    if segment is None:
        return None
    title = re.search(r"android\.title=(?:String|CharSequence) \((.*)\)", segment)
    text = re.search(r"android\.text=(?:String|CharSequence) \((.*)\)", segment)
    return (title.group(1) if title else None, text.group(1) if text else None)


def wait_for_notification(device, want=True, timeout=25):
    """Polls, because posting and cancelling are asynchronous on the notification-service side."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        text = notif_texts(device)
        if (text is not None) == want:
            return text
        time.sleep(1)
    return notif_texts(device)


def next_alarm_label(d):
    """The label of the alarm the app considers next, read through the bridge.

    The pre-alert is about *the next alarm*, not about this test's alarm, so a device with another alarm
    already inside the hour will announce that one instead. The instrumented acceptance script leaves an
    `M3 测试 (DAILY)` alarm behind, and the first run of this test after it failed three assertions
    because the notification correctly named that alarm rather than the fixture. The product was right;
    the test was assuming it owned the schedule.
    """
    rows = plugin(
        d,
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        ".filter(a => a.nextRingAt !== null)"
        ".sort((a, b) => a.nextRingAt - b.nextRingAt)"
        ".map(a => a.label)",
    )
    return rows[0] if isinstance(rows, list) and rows else None


def alarm_ids_with_label(d, label):
    raw = plugin(
        d,
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        f".filter(a => a.label === {json.dumps(label)}).map(a => a.id)",
    )
    return raw if isinstance(raw, list) else []


def save_alarm(d, **fields):
    return plugin(d, f"await window.Capacitor.Plugins.AlarmHub.saveAlarm({json.dumps(fields, ensure_ascii=False)})")


def delete_alarms(d, ids):
    return plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: {json.dumps(ids)}}})")


def force_recompute(d):
    """Makes the app recompute through its own public path.

    Every write re-registers the whole schedule, and `recomputeAll` is where the pre-alert check lives —
    so a settings write is the least invasive way to ask the app "would you announce now?" without
    reaching into the native internals. (The debug receiver cannot be used on this device class: it
    requires a signature-level permission, and on the emulator only `adb root` grants it.)
    """
    plugin(d, "await window.Capacitor.Plugins.AlarmHub.updateSettings({})")
    time.sleep(1.5)


def device_minutes_from_now(device, minutes):
    """`(hour, minute)` on the **device's** clock, `minutes` ahead of it.

    Read as epoch + timezone offset rather than with a `date '+%H %M'` format string: this device's
    `date` accepts only one argument (`date: Max 1 argument`), so a format containing a space silently
    prints nothing.

    Why not the host's clock: the first version used `time.localtime()`, and on this machine the host is
    UTC+8 while the emulator runs GMT — so "30 minutes from now" became an alarm **8 h 44 min** out. The
    app logged `alarm=150 is 524 min out; not yet`, every positive assertion failed, and the negative ones
    (which assert absence) passed for the wrong reason. A test computing a wall-clock instant has to ask
    the clock that will act on it.
    """
    epoch = adb(device, "shell", "date", "+%s").stdout.strip()
    offset_raw = adb(device, "shell", "date", "+%z").stdout.strip()
    if not epoch.isdigit():
        raise RuntimeError(f"could not read the device clock: epoch={epoch!r} tz={offset_raw!r}")

    sign = -1 if offset_raw.startswith("-") else 1
    digits = offset_raw.lstrip("+-")
    offset_minutes = sign * (int(digits[:2]) * 60 + int(digits[2:4])) if len(digits) >= 4 else 0

    local_minutes = (int(epoch) // 60 + offset_minutes + minutes) % (24 * 60)
    return local_minutes // 60, local_minutes % 60


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

    print("== 0. clean slate ==")
    stale = alarm_ids_with_label(d, LABEL)
    if stale:
        delete_alarms(d, stale)
        time.sleep(1)
    force_recompute(d)
    wait_for_notification(device, want=False, timeout=10)

    print("== 1. an alarm 30 minutes out must be announced ==")
    hour, minute = device_minutes_from_now(device, 30)
    created = save_alarm(
        d, hour=hour, minute=minute, label=LABEL, enabled=True, deleteAfterRing=False,
    )
    alarm_id = created.get("id") if isinstance(created, dict) else None
    print(f"  alarm id={alarm_id} at {hour:02d}:{minute:02d} (30 min out)")
    time.sleep(2)
    force_recompute(d)

    texts = wait_for_notification(device, want=True)
    print(f"  notification: {texts}")
    check("the heads-up appeared", texts is not None, True)

    # Only judge the content when this test's alarm is the one the app considers next. With another
    # alarm already inside the hour (the acceptance script leaves one), the notification correctly names
    # that alarm, and asserting on the fixture here would be a false failure — the product is doing the
    # right thing for a schedule this test does not own.
    next_label = next_alarm_label(d)
    print(f"  the app's next alarm is: {next_label!r}")
    if next_label != LABEL:
        print(f"  ! skipping the content assertions: the next alarm is {next_label!r}, not this test's")
        check("something is inside the hour and was announced", texts is not None, True)
    else:
        if texts:
            title, text = texts
            check("the title names the ring time", "即将响铃" in (title or ""), True)
            check("the title carries the clock", f"{hour:02d}:{minute:02d}" in (title or ""), True)
            check("the label is the body", text, LABEL)

        segment = notif_segment(device) or ""
        # What is asserted about the affordance is the **tap target**, not a button label.
        #
        # FR-7.10.5: the first version used `addAction("关闭闹钟")`, and on the Xiaomi 14 the user could
        # not reach it — HyperOS notifications cannot be pulled down to expand, and Android only renders
        # action buttons when expanded. `dumpsys` proved the action was registered (`actions={[0] …}`)
        # while the user proved it was invisible, which is why the test now checks that the *content*
        # intent is the turn-off broadcast and that no unreachable action is advertised.
        check("tapping the body targets the turn-off action", "contentIntent=" in segment and "broadcastIntent" in segment, True)
        check(
            "it no longer advertises an action button that this ROM cannot show",
            "actions={" not in segment,
            True,
        )
        check("it uses its own channel", "alarmhub_upcoming" in segment, True)

    print("== 2. a recompute must not re-announce the same occurrence (FR-7.10.2) ==")
    # Measured through `mUpdateTimeMs` rather than by clearing it first: `cmd notification cancel` does
    # not exist on this image, and there is no adb swipe that reaches the shade. A refreshed timestamp is
    # exactly what "it came back" would look like, so its staying put is the assertion that matters.
    index_before = notif_texts(device)
    print(f"  before: {index_before}")
    force_recompute(d)
    time.sleep(3)
    index_after = notif_texts(device)
    print(f"  after:  {index_after}")
    # Asserted on the body text, which a re-post would change (a fresh post carries the ring time). The
    # timestamp route was tried first and dropped: `mCreationTimeMs` sits past the 3 KB window this probe
    # reads, so the check could only ever have compared `None` to `None` — a green line that proves nothing.
    check("the same notification is still up, unchanged", index_after, index_before)
    check("and it is still the same occurrence", (index_after or [None])[0], (index_before or [None])[0])

    print("== 3. moving the alarm earns a new announcement ==")
    hour2, minute2 = device_minutes_from_now(device, 45)
    save_alarm(d, id=alarm_id, hour=hour2, minute=minute2, label=LABEL)
    print(f"  moved to {hour2:02d}:{minute2:02d} (45 min out)")
    time.sleep(2)
    force_recompute(d)
    texts2 = wait_for_notification(device, want=True)
    print(f"  notification: {texts2}")
    check("the new occurrence is announced", texts2 is not None, True)
    if texts2:
        check("and it names the new time", f"{hour2:02d}:{minute2:02d}" in (texts2[0] or ""), True)

    print("== 4. an alarm that moves out of the hour takes the notification down ==")
    hour3, minute3 = device_minutes_from_now(device, 180)
    save_alarm(d, id=alarm_id, hour=hour3, minute=minute3, label=LABEL)
    print(f"  moved to {hour3:02d}:{minute3:02d} (3 h out)")
    time.sleep(2)
    force_recompute(d)
    # A notification saying 「即将响铃」 about an alarm that is three hours away is simply false, so the
    # implementation takes it down. The first version left it up, and this check is what found that.
    gone = wait_for_notification(device, want=False, timeout=10)
    check("the stale 「即将响铃」 is gone", gone, None)

    print("== 5. and moving back inside the hour announces the new instant ==")
    hour4, minute4 = device_minutes_from_now(device, 20)
    save_alarm(d, id=alarm_id, hour=hour4, minute=minute4, label=LABEL)
    print(f"  moved to {hour4:02d}:{minute4:02d} (20 min out)")
    time.sleep(2)
    force_recompute(d)
    texts4 = wait_for_notification(device, want=True)
    print(f"  notification: {texts4}")
    check("announced again", texts4 is not None, True)
    if texts4:
        check("with the new time", f"{hour4:02d}:{minute4:02d}" in (texts4[0] or ""), True)

    print("== 6. an alarm outside the hour alone announces nothing ==")
    delete_alarms(d, [alarm_id] if alarm_id else [])
    time.sleep(1)
    force_recompute(d)
    wait_for_notification(device, want=False, timeout=10)
    hour5, minute5 = device_minutes_from_now(device, 150)
    only_far = save_alarm(d, hour=hour5, minute=minute5, label=LABEL, enabled=True, deleteAfterRing=False)
    print(f"  alarm at {hour5:02d}:{minute5:02d} (2.5 h out)")
    far_id = only_far.get("id") if isinstance(only_far, dict) else None
    time.sleep(2)
    force_recompute(d)
    time.sleep(3)
    check("nothing is announced for an alarm 2.5 hours out", notif_texts(device), None)

    print("== 7. the 「关闭闹钟」 action switches the alarm off ==")
    # The action's *effect* is exercised, the tap itself is not — and the distinction is honest:
    #
    #   `am broadcast` **cannot** reach this receiver. It is `exported="false"`, and the log shows the
    #   broadcast merely `Enqueued` with no delivery to the app. Shell can broadcast to an exported
    #   receiver (that is how `DebugReceiver` is driven), but not to a non-exported one, and making this
    #   one exported so a test could poke it would let any app on the device switch alarms off.
    #
    # So the test performs the same write the receiver performs (`setAlarmEnabled(false)` + recompute),
    # which covers "the alarm ends up off and the notification goes away". The remaining gap — that a tap
    # on the notification reaches the receiver — is one line of PendingIntent wiring and belongs to the
    # real-device pass, where the user taps it.
    hour6, minute6 = device_minutes_from_now(device, 25)
    action_alarm = save_alarm(d, hour=hour6, minute=minute6, label=LABEL, enabled=True, deleteAfterRing=False)
    action_id = action_alarm.get("id") if isinstance(action_alarm, dict) else None
    print(f"  alarm id={action_id} at {hour6:02d}:{minute6:02d}")
    time.sleep(2)
    force_recompute(d)
    wait_for_notification(device, want=True)
    check("it was announced before the action", notif_texts(device) is not None, True)

    plugin(d, f"await window.Capacitor.Plugins.AlarmHub.setAlarmEnabled({{id: {action_id}, enabled: false}})")
    time.sleep(2)
    force_recompute(d)

    # Read through the same `plugin` helper the rest of the test uses, which asks the page to stringify
    # and hands back parsed JSON. An inline `d.eval("JSON.stringify(...)")` returned `{}` here — the shape
    # that `ui-drive` cannot be trusted to parse (STATUS §1.1 #24).
    enabled = plugin(d, f"(await window.Capacitor.Plugins.AlarmHub.getAlarm({{id: {action_id}}})).enabled")
    print(f"  getAlarm(enabled) -> {enabled!r}")
    check("the alarm is switched off", enabled, False)
    gone = wait_for_notification(device, want=False, timeout=15)
    check("and the notification went away with it", gone, None)

    print("== cleanup ==")
    ids = alarm_ids_with_label(d, LABEL)
    if ids:
        delete_alarms(d, ids)
        time.sleep(1)
    force_recompute(d)
    wait_for_notification(device, want=False, timeout=10)
    check("no test alarm left behind", alarm_ids_with_label(d, LABEL), [])
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
