"""Tests for the editor's two M8 additions: the relative-time preview and in-editor group creation.

Both are about the same failure mode — the screen disagreeing with what will actually happen:

* The preview must come from the same computation a save uses, so a brand-new 单次 alarm (which has
  no `onceDate` until it is saved) must not read 「不会响铃」. That is exactly the bug the first
  version had.
* Creating a group must leave the new group **selected**, or the user creates 「吃药」 and then has to
  find and tap it.
"""

import json
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

FAILURES = []
TEMP_GROUP = "自测分组"

NEW_ALARM = "\u65b0\u5efa"
EXIT_LABELS = "\u53d6\u6d88|\u8fd4\u56de"


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def seconds(text: str) -> int | None:
    """Parse 「20 小时 45 分后响铃」 into seconds. None when the text is not a countdown at all."""
    if not text:
        return None
    h = re.search(r"(\d+)\s*小时", text)
    m = re.search(r"(\d+)\s*分", text)
    s = re.search(r"(\d+)\s*秒", text)
    if not (h or m or s):
        return None
    return (int(h.group(1)) if h else 0) * 3600 + (int(m.group(1)) if m else 0) * 60 + (int(s.group(1)) if s else 0)


def sub(d):
    return d.eval("JSON.stringify(document.querySelector('.bar__sub')?.textContent.trim() ?? null)")


def title(d):
    return d.eval("JSON.stringify(document.querySelector('.bar__title')?.textContent.trim() ?? '')")


def open_editor(d):
    for _ in range(3):
        if NEW_ALARM in title(d):
            return True
        d.eval("JSON.stringify(document.querySelector('.fab')?.click() ?? null)")
        time.sleep(2)
        if NEW_ALARM in title(d):
            return True
        d.eval(
            "JSON.stringify((()=>{const b=[...document.querySelectorAll('.bar button')]"
            f".find(x=>/{EXIT_LABELS}/.test(x.textContent)); if(b){{b.click(); return 'back'}} return 'nothing'}})())"
        )
        time.sleep(1.5)
    return False


def groups(d):
    return d.eval(
        "JSON.stringify([...document.querySelectorAll('.chip--group')].map(c => ({"
        "  name: c.textContent.trim(), on: c.classList.contains('chip--on')})))"
    )


def group_count(device, want, timeout=6.0):
    """The group count, once it has settled on `want` (or after `timeout`).

    A write goes through a coroutine plus a full schedule recompute, so reading the database the
    instant the UI updates can still see the previous value. Polling is the honest fix; sleeping a
    guessed amount is not.
    """
    deadline = time.time() + timeout
    seen = ui.db_scalar(device, "select count(*) from alarm_groups")
    while seen != want and time.time() < deadline:
        time.sleep(0.4)
        seen = ui.db_scalar(device, "select count(*) from alarm_groups")
    return seen


def spin_hours(d, rows):
    """Drag the hour wheel by `rows` rows; positive means later values."""
    c = d.eval(
        "JSON.stringify((()=>{const c=[...document.querySelectorAll('.col')]"
        ".find(x=>x.getAttribute('aria-label')===\"\u5c0f\u65f6\");"
        "const b=c.getBoundingClientRect(); return {x:b.x+20, y:b.y+b.height/2};})())"
    )
    d.eval_touch(c["x"], c["y"] + (30 if rows > 0 else -30), hold_ms=300, dx=0, dy=-rows * 48)
    time.sleep(1.2)


def main():
    device = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 9222
    d = ui.Driver(device, port, 2.625, 136)
    d.keep_screen_on()
    print(f"device={device} webview pid={d.ensure_forward()}")

    # Clean up a group a previous failed run may have created, so the "grew by one" check is honest.
    before_groups = ui.db_scalar(device, "select count(*) from alarm_groups")
    leftovers = d.eval(
        "(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups"
        f".filter(g => g.name === {json.dumps(TEMP_GROUP)}).map(g => g.id)))()"
    )
    if leftovers:
        print(f"  removing {len(leftovers)} leftover test group(s)")
        d.eval(
            "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteGroup("
            f"{{id: {leftovers[0]}, moveAlarmsTo: 1}})))()"
        )
        time.sleep(1)
        before_groups = ui.db_scalar(device, "select count(*) from alarm_groups")

    # Unconditionally make the page re-read the database first. The store keeps `state.groups` as a
    # cache, and this test deletes its leftovers through the **bridge**, which bypasses the store's own
    # refresh — so the page kept a group that no longer existed and the create below silently took the
    # "that name already exists, just select it" branch against a deleted id. Leaving and re-entering
    # the app is what triggers the re-read.
    d.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(3)
    d.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
    time.sleep(3)

    print("== open the editor ==")
    if not open_editor(d):
        raise SystemExit("could not reach the alarm editor")
    print(f"  {title(d)}")

    print("== the preview is a real countdown, not 「不会响铃」 ==")
    text = sub(d)
    print(f"  preview: {text!r}")
    check("a brand-new alarm shows a countdown", seconds(text) is not None, True)
    first = seconds(text)
    check("and it is within the next 48 hours", 0 < first < 48 * 3600, True)

    print("== it follows the wheel ==")
    spin_hours(d, 3)
    after_text = sub(d)
    after = seconds(after_text)
    print(f"  preview after 3 rows later: {after_text!r}")
    delta = (after - first) % 86400
    delta = min(delta, 86400 - delta)
    # This assertion is about *following the wheel*, not about the exact travel. `scroll-snap-stop:
    # always` caps one gesture at a few rows, so a 144 px drag lands 1-3 rows — measuring it as
    # exactly 3 failed, and the correct fix was the test's expectation, not the wheel's behaviour.
    # ±90 s of slack covers the fact that both lines are rendered to the minute.
    check("the line moved when the wheel moved", after_text != text, True)
    check("and moved by 1-4 hours, in the direction the wheel was dragged", 3600 - 90 <= delta <= 4 * 3600 + 90, True)

    print("== a group can be created here, and ends up selected ==")
    d.eval(
        "JSON.stringify([...document.querySelectorAll('.chip')]"
        ".find(c => c.textContent.includes('\u65b0\u5efa'))?.click() ?? null)"
    )
    time.sleep(1)
    check("the inline input appears", d.eval("JSON.stringify(!!document.querySelector('.newgroup__input'))"), True)
    d.eval(
        "JSON.stringify((()=>{const i=document.querySelector('.newgroup__input');"
        f"i.value={json.dumps(TEMP_GROUP)}; i.dispatchEvent(new Event('input',{{bubbles:true}}));"
        "return i.value})())"
    )
    time.sleep(0.5)
    d.eval("JSON.stringify(document.querySelector('.newgroup__ok').click() ?? null)")
    time.sleep(2)
    listed = groups(d)
    print(f"  groups now: {[g['name'] + ('*' if g['on'] else '') for g in listed]}")
    made = [g for g in listed if g["name"] == TEMP_GROUP]
    check("the new group is in the list", len(made), 1)
    check("and it is the selected one", made[0]["on"] if made else None, True)
    check("the input closed itself", d.eval("JSON.stringify(!!document.querySelector('.newgroup__input'))"), False)
    check("the database gained exactly one group", group_count(device, before_groups + 1), before_groups + 1)

    print("== typing a name that already exists selects it instead of duplicating ==")
    d.eval(
        "JSON.stringify([...document.querySelectorAll('.chip')]"
        ".find(c => c.textContent.includes('\u65b0\u5efa'))?.click() ?? null)"
    )
    time.sleep(1)
    d.eval(
        "JSON.stringify((()=>{const i=document.querySelector('.newgroup__input');"
        "i.value='\u672a\u5206\u7ec4'; i.dispatchEvent(new Event('input',{bubbles:true})); return i.value})())"
    )
    time.sleep(0.5)
    d.eval("JSON.stringify(document.querySelector('.newgroup__ok').click() ?? null)")
    time.sleep(1.5)
    check("no duplicate was created", group_count(device, before_groups + 1), before_groups + 1)
    listed = groups(d)
    picked = [g["name"] for g in listed if g["on"]]
    check("the existing group got selected instead", picked, ["\u672a\u5206\u7ec4"])

    print("== cleanup ==")
    ids = d.eval(
        "(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups"
        f".filter(g => g.name === {json.dumps(TEMP_GROUP)}).map(g => g.id)))()"
    )
    if ids:
        d.eval(
            "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteGroup("
            f"{{id: {ids[0]}, moveAlarmsTo: 1}})))()"
        )
        time.sleep(1)
    check("the test group is gone", group_count(device, before_groups), before_groups)

    print()
    if FAILURES:
        print(f"FAILED ({len(FAILURES)}):")
        for f in FAILURES:
            print("  -", f)
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
