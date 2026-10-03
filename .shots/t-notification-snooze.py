"""The ringing notification's 贪睡 line, in all three states (docs/交接-剩余工作.md §2 第 7 项).

The report was that the shade kept advertising 「贪睡 N 分钟（还剩 M 次）」 after 贪睡 was switched off
globally — a line offering something the user had turned off, while the ring page's button was correctly
gone. The fix is not "make the notification check the second switch too" but "merge the two switches
once, where the ring is built" (`RingRequest.from`), so no consumer can forget one.

That makes the notification the thing to look at, not the ring page. It is read out of `dumpsys
notification` with **all UI automation off** (`uiautomator dump`, `dumpsys window`, `input`), because
launching UiAutomation in this Android 16 image wedges `system_server` — and a wedged emulator looks
exactly like a failed test.

The three states, and what each proves:

| state | alarm switch | global switch | notified text |
|---|---|---|---|
| A | on | on | 「贪睡 10 分钟（还剩 3 次）」 — the feature is offered |
| B | on | **off** | just the group name — the bug's exact case |
| C | **off** | on | just the group name — a per-alarm opt-out must not become 「已无贪睡次数」 |

State C is the regression the merge could have introduced: `canSnooze` false with an untouched
allowance used to render 「已无贪睡次数」, which is a different way of saying something untrue.

    python -u .shots/t-notification-snooze.py
"""

import argparse
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
PKG = "com.alarmhub.app"
NOTIF_ID = 1001
LABEL = "自测-通知"
TEMP_LABEL = "自测-可删"


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def adb(device, *args, timeout=60):
    return subprocess.run(
        ["adb", "-s", device, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout
    )


def notif_dump(device):
    return adb(device, "shell", "dumpsys", "notification", "--noredact").stdout


def notif_text(device):
    """`(title, text)` of the ringing notification, scraped out of `dumpsys notification`.

    Deliberately text-level: `dumpsys` prints the extras a map key at a time
    (`android.text=…` / `android.title=…`), which is stable across versions and needs no parser.
    """
    dump = notif_dump(device)
    idx = dump.find(f"pkg={PKG}")
    if idx < 0:
        return None
    segment = dump[idx : idx + 4000]
    text = re.search(r"android\.text=(?:String|CharSequence) \((.*)\)", segment)
    title = re.search(r"android\.title=(?:String|CharSequence) \((.*)\)", segment)
    return (
        title.group(1) if title else None,
        text.group(1) if text else None,
    )


def plugin(d, expr):
    """Evaluate an **async** bridge call in the page and return its value.

    The `async()=>{…}` and the `JSON.stringify` both have to be there, and both have to be in the JS —
    not in this Python. The transport returns a plain string intact but truncates an object to its
    opening brace, so the stringify must happen in the page; and the probe's `Runtime.evaluate` runs
    without top-level `await`, so an `await` needs the wrapper. Writing `JSON.stringify` on the Python
    side instead produced a truncated `"{"`, which looked like a product failure and was a transport
    limitation (the same one that made an earlier probe of the degraded holiday row prove nothing).
    """
    return d.eval(f"(async()=>JSON.stringify({expr}))()")


def list_alarm_pairs(d):
    """Every alarm as `id:label` pairs, so leftovers can be identified without parsing an object."""
    raw = plugin(d, "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms.map(a => a.id + ':' + a.label).join('|')")
    pairs = []
    for item in str(raw).split("|"):
        if ":" in item:
            ident, label = item.split(":", 1)
            try:
                pairs.append((int(ident), label))
            except ValueError:
                pass
    return pairs


def as_dict(value, what=""):
    """The bridge's answer as a dict, with a loud failure when it did not survive the trip."""
    if isinstance(value, dict):
        return value
    try:
        return json.loads(value)
    except (json.JSONDecodeError, TypeError) as e:
        raise RuntimeError(
            f"the bridge answer for {what or 'a call'} did not parse ({e}); raw: {value!r}"
        ) from e


def plugin(d, expr):
    """Evaluate an **async** bridge call in the page and return its value.

    The wrapper is the JS side's job: the probe's `Runtime.evaluate` runs without top-level `await`, so
    an `await` needs the `async()=>` shell. The page also does its own `JSON.stringify`, which keeps the
    answer on one line — that was a workaround for `ui-drive.eval()` reading a multi-line object as the
    string `'{'`. The real bug is fixed (see docs/STATUS.md §1.1 #24, and `tools/ui-drive.py` now parses
    the leading JSON value), but a one-line answer stays the most robust shape to ask for.
    """
    return d.eval(f"(async()=>JSON.stringify({expr}))()")


def list_alarm_pairs(d):
    """Every alarm as `id:label` pairs, so leftovers can be identified without parsing an object."""
    raw = plugin(d, "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms.map(a => a.id + ':' + a.label).join('|')")
    pairs = []
    for item in str(raw).split("|"):
        if ":" in item:
            ident, label = item.split(":", 1)
            try:
                pairs.append((int(ident), label))
            except ValueError:
                pass
    return pairs


def set_alarm_snooze(d, alarm_id, enabled):
    """Flips one alarm's 贪睡 switch.

    Through `batchUpdateAlarms`, not `saveAlarm`: the batch answers with `{"affected":1}` whatever the
    alarm looks like, whereas `saveAlarm` echoes the whole alarm back — long, and the one shape this
    transport has been seen to truncate mid-JSON.
    """
    return as_dict(
        plugin(
            d,
            "await window.Capacitor.Plugins.AlarmHub.batchUpdateAlarms("
            f"{{ids: [{alarm_id}], patch: {{snoozeEnabled: {json.dumps(enabled)}}}}})",
        ),
        "batchUpdateAlarms",
    )["affected"]


def save_alarm(d, **fields):
    payload = json.dumps(fields, ensure_ascii=False)
    return int(as_dict(plugin(d, f"await window.Capacitor.Plugins.AlarmHub.saveAlarm({payload})"), "saveAlarm")["id"])


def delete_alarms(d, ids):
    return as_dict(plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: {json.dumps(ids)}}})"))["affected"]


def update_settings(d, patch):
    return as_dict(plugin(d, f"await window.Capacitor.Plugins.AlarmHub.updateSettings({json.dumps(patch)})"))["snoozeEnabled"]


def debug_cmd(device, cmd, *extras, timeout=60):
    """Run one of the app's debug commands and return the result string.

    The debug receiver is the only way to start and stop a ring on demand, and it is available here
    because the emulator's adbd runs as root (the receiver demands the signature-level
    `com.alarmhub.app.permission.DEBUG`, which plain `adb shell` does not hold — on the phone this is
    one of the three measured dead ends in docs/MACHINE-CHECKLIST.md §0).
    """
    out = adb(
        device,
        "shell",
        "am",
        "broadcast",
        "-a",
        "com.alarmhub.app.debug.COMMAND",
        "-n",
        f"{PKG}/.debug.DebugReceiver",
        "--es",
        "cmd",
        cmd,
        *extras,
        timeout=timeout,
    )
    m = re.search(r'data="(.*)"', out.stdout)
    return m.group(1) if m else out.stdout.strip()


def ring_now(device, alarm_id, as_snooze=False):
    extras = ["--es", "as", "snooze"] if as_snooze else []
    return debug_cmd(device, "ringnow", "--el", "alarm", str(alarm_id), *extras)


def dismiss_ring(device):
    return debug_cmd(device, "dismiss")


def spend_snooze_allowance(device, alarm_id):
    """Burns the alarm's 贪睡 allowance by setting `snooze_count` to its maximum.

    Done with `sqlite3` through `run-as` rather than by snoozing three times: this test is about what
    the **notification** says once the allowance is gone, and driving three real snoozes (each of which
    waits for its own re-ring) would add half an hour to the run and test the scheduler again instead.
    The emulator allows `run-as sqlite3`; the phone does not (docs/MACHINE-CHECKLIST.md §0), which is
    one more reason this particular test lives on the emulator.
    """
    adb(
        device,
        "shell",
        f"run-as {PKG} sqlite3 databases/alarmhub.db "
        f"\"update alarms set snooze_count = snooze_max_count where id = {alarm_id};\"",
    )
    out = adb(
        device,
        "shell",
        f"run-as {PKG} sqlite3 databases/alarmhub.db "
        f"\"select snooze_count || '/' || snooze_max_count from alarms where id = {alarm_id};\"",
    )
    return out.stdout.strip()


def back_to_list(d):
    d.eval(
        "JSON.stringify((()=>{const b=[...document.querySelectorAll('.bar button')]"
        ".find(x=>/返回/.test(x.textContent)); return b ? (b.click(), 'clicked') : 'none'})())"
    )
    time.sleep(1.2)


def cleanup(d):
    """Removes anything this test (or a previous failed run) left behind."""
    stale = [ident for ident, label in list_alarm_pairs(d) if label in (LABEL, TEMP_LABEL)]
    if stale:
        print(f"  removing {len(stale)} leftover test alarm(s)")
        delete_alarms(d, stale)
        time.sleep(1)


def run_state(d, name, alarm_snooze, global_snooze, alarm_id):
    print(f"== state {name}: alarm={alarm_snooze} global={global_snooze} ==")
    update_settings(d, {"snoozeEnabled": global_snooze})
    print(f"  alarm patched, affected={set_alarm_snooze(d, alarm_id, alarm_snooze)}")
    time.sleep(0.8)
    ring_result = ring_now(d.device, alarm_id)
    print(f"  ring: {ring_result}")
    if "ringStarted=true" not in ring_result:
        raise RuntimeError(
            f"state {name} could not start a ring, so nothing was verified: {ring_result!r}"
        )
    time.sleep(2.5)
    title, text = notif_text(d.device) or (None, None)
    print(f"  notification: title={title!r} text={text!r}")
    if not title:
        raise RuntimeError(f"state {name} rang but posted no notification to read")
    print(f"  dismiss: {dismiss_ring(d.device).splitlines()[0]}")
    time.sleep(1.5)
    return title, text


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--device", default="emulator-5554")
    p.add_argument("--port", type=int, default=9222)
    p.add_argument("--dpr", type=float, default=2.625)
    p.add_argument("--top", type=int, default=136)
    args = p.parse_args()

    d = ui.Driver(args.device, args.port, args.dpr, args.top)
    print(f"device={args.device}")
    d.keep_screen_on()
    print(f"  webview pid={d.ensure_forward()}")

    cleanup(d)

    print("== create one throwaway alarm, snooze 10 分钟 / 最多 3 次 ==")
    alarm_id = save_alarm(
        d,
        hour=6,
        minute=6,
        label=LABEL,
        enabled=True,
        snoozeEnabled=True,
        snoozeMinutes=10,
        snoozeMaxCount=3,
        # Pinned off on purpose: a one-off alarm defaults to 响铃后删除 whenever the device's
        # 设置 → 新建一次性闹钟默认响铃后删除 is on (it is on this emulator), so the first dismiss
        # deleted the fixture and states B and C rang nothing at all. The run then "passed" the two
        # negative checks for the wrong reason — no notification means no snooze text. A check that
        # passes because the subject is missing is the exact failure mode this project documents.
        deleteAfterRing=False,
    )
    print(f"  alarm id={alarm_id}")
    time.sleep(1)

    try:
        title_a, text_a = run_state(d, "A（两个开关都开）", True, True, alarm_id)
        check("A: the shade offers snooze", "贪睡 10 分钟（还剩 3 次）" in (text_a or ""), True)
        check("A: the group name is still there", (text_a or "").startswith("未分组"), True)

        title_b, text_b = run_state(d, "B（全局关闭）", True, False, alarm_id)
        check("B: no snooze offer while the global switch is off", "还剩" in (text_b or ""), False)
        # The first version of this fix answered 「已无贪睡次数」 here — an exhausted allowance reported on
        # an allowance that was never touched. The message has to name the real reason.
        check("B: and it does not claim the allowance is used up", "已无贪睡次数" in (text_b or ""), False)
        check("B: it says the feature is off", "贪睡已关闭" in (text_b or ""), True)

        title_c, text_c = run_state(d, "C（单条关闭）", False, True, alarm_id)
        check("C: a per-alarm opt-out offers nothing either", "还剩" in (text_c or ""), False)
        check("C: and does not claim the allowance is used up", "已无贪睡次数" in (text_c or ""), False)
        check("C: it says the feature is off", "贪睡已关闭" in (text_c or ""), True)

        # The fourth state, and the reason the third wording exists at all: with the allowance actually
        # spent, 「已无贪睡次数」 must still be what the user reads. Without this check the previous branch
        # could have become unreachable and nothing would have noticed.
        #
        # It has to be observed on a **snooze re-ring** (`--as snooze`), not a fresh one: a fresh ring
        # resets the counter by design (`AlarmReceiver.startRing` → `clearSnoozeCount`), so a fresh ring
        # always has a full allowance and 「还剩 3 次」 is the correct line there. The first version of this
        # check used a fresh ring and reported a failure that was the test's misunderstanding of the
        # product, not a defect.
        print("== state D（次数用完）: 贪睡开着，余额 0，重响 ==")
        update_settings(d, {"snoozeEnabled": True})
        set_alarm_snooze(d, alarm_id, True)
        spent = spend_snooze_allowance(d.device, alarm_id)
        print(f"  snooze_count set to the maximum: {spent}")
        ring_result = ring_now(d.device, alarm_id, as_snooze=True)
        print(f"  ring: {ring_result.splitlines()[0]}")
        time.sleep(2.5)
        title_d, text_d = notif_text(d.device) or (None, None)
        print(f"  notification: title={title_d!r} text={text_d!r}")
        dismiss_ring(d.device)
        time.sleep(1.5)
        check("D: an exhausted allowance is reported as such", "已无贪睡次数" in (text_d or ""), True)
        check("D: not as a switched-off feature", "贪睡已关闭" in (text_d or ""), False)

        check("the title always carries the clock and label", LABEL in (title_a or ""), True)
    finally:
        print("== restore ==")
        update_settings(d, {"snoozeEnabled": True})
        update_settings(d, {"volumeKeyAction": "snooze"})
        cleanup(d)
        back_to_list(d)
        check("no test alarm is left behind", len([1 for _, label in list_alarm_pairs(d) if label in (LABEL, TEMP_LABEL)]), 0)

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
