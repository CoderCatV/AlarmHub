"""End-to-end test for the two bridge methods that had no caller (docs/交接-剩余工作.md §2 第 8 项).

Two things are verified, both through the **real UI** rather than by calling the bridge directly —
a bridge method that is implemented but unreachable is exactly the thing being fixed here, so calling
it from the console would prove nothing about whether the user can reach it:

1. `batchUpdateAlarms` — the selection bar's 启用 / 停用 buttons.
   The claim under test is not just "the rows changed" but **"one write, not N"**: the whole reason the
   batch method exists is that every write runs `recomputeAll()` over the entire schedule, so a loop
   would sweep the table once per alarm. That is measured from the native log
   (`recomputeAll: N stored, M registered` — one line per call), not inferred.

2. `getHolidayDataInfo` — the 设置 page's 节假日数据 row (PRD FR-6.2 / FR-6.3).

Like the multi-select test, **it only touches rows it created** (label `自测-批改`) and returns the
database to its starting row count, so it is safe against the reporting phone's real alarms.
"""

import argparse
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
ARGS = {}
TEMP_LABEL = "自测-批改"
DELETE = "删除"
# Android's logcat tag for the scheduler (see AlarmScheduler.TAG).
SCHED_TAG = "AlarmHub/Scheduler"


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def touch(d, x, y, hold_ms=60, dx=0, dy=0):
    if ARGS["via"] == "cdp":
        d.eval_touch(x, y, hold_ms, dx, dy)
    else:
        d.tap_css(x, y, hold_ms=hold_ms, drag=(dx, dy))


def db_count(d, where="select count(*) from alarms"):
    return ui.db_scalar(d.device, where)


def plugin(d, expr):
    """Run an **async** bridge call and return its parsed value.

    `JSON.stringify` has to be in the page: the probe's `Runtime.evaluate` has no top-level `await`
    (hence the wrapper), and asking it for a bare object is what `ui-drive.eval()` used to mis-read as
    the string `'{'` (docs/STATUS.md §1.1 #24 — fixed, but a one-line answer stays the safest shape).
    """
    return d.eval(f"(async()=>JSON.stringify({expr}))()")


def alarm_ids_with_label(d, label):
    """Every alarm carrying `label`, in one call.

    Note the shape: `listAlarms()` resolves to an **object** (`{alarms: [...]}`), not a bare array — the
    envelope M6 documented. A first version of this helper called `.map` on the envelope, which threw,
    and the exception was swallowed by the caller's "is there anything to clean up" test: it reported
    zero leftovers while the database had two. A fixture guard that cannot see leftovers is worse than
    no guard, so this returns the list or raises.
    """
    raw = plugin(
        d,
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        f".filter(a => a.label === {json.dumps(label)}).map(a => a.id)",
    )
    if not isinstance(raw, list):
        raise RuntimeError(f"could not read the alarm list for cleanup; got {raw!r}")
    return raw


def delete_alarms(d, ids):
    return plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: {json.dumps(ids)}}})")


def rows_with_label(d, label):
    """Geometry of the rows carrying `label`, in page coordinates."""
    return d.eval(
        "JSON.stringify([...document.querySelectorAll('li.row')].map((r, i) => {"
        "  const main = r.querySelector('.row__main'); const b = main.getBoundingClientRect();"
        "  return { i, label: r.querySelector('.row__label')?.textContent.trim() ?? '',"
        "           x: b.x + b.width / 2, y: b.y + Math.min(28, b.height / 2) };"
        "}).filter(r => r.label === " + json.dumps(label) + "))"
    )


def logcat_clear(d):
    subprocess.run(["adb", "-s", d.device, "logcat", "-c"], capture_output=True)


def recompute_all_lines(d):
    """How many `recomputeAll` calls the native code logged since the last clear."""
    out = subprocess.run(
        ["adb", "-s", d.device, "logcat", "-d", "-s", f"{SCHED_TAG}:I"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    lines = [ln for ln in out.splitlines() if "recomputeAll:" in ln]
    for ln in lines:
        print(f"    {ln.strip()}")
    return len(lines)


def foreground_toggle(d):
    return d.foreground_toggle()


def cleanup_leftovers(d):
    """Removes rows from a previous failed run before counting anything, and verifies it worked.

    This used to be a best-effort `if left:` block, and it was not enough: a run that aborts half way
    (an exception in the middle, or a batch that only deleted part of the batch) leaves rows behind, the
    next run's "the database grew by three" then sees more than three, and the failure cascade reads
    like fifteen product defects. It happened twice while writing this test. So: delete, then **assert
    the label is gone**, and stop the run if it is not — a test that starts from an unknown state is
    worse than no test.
    """
    left = alarm_ids_with_label(d, TEMP_LABEL)
    if left:
        print(f"  found {len(left)} leftover throwaway rows; removing them through the bridge")
        print(f"  delete -> {delete_alarms(d, left)}")
        time.sleep(1)
        foreground_toggle(d)
    remaining = db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}'")
    if remaining != 0:
        raise RuntimeError(
            f"{remaining} throwaway row(s) survived cleanup; refusing to run from an unknown state"
        )


def leave_selection_mode(d):
    if d.state()["selMode"]:
        print("  (was in selection mode; leaving it first)")
        d.eval("JSON.stringify(document.querySelector('.selbar__action').click() ?? null)")
        time.sleep(1)


def open_settings(d):
    """Reaches the settings page through the hero's ⋮ menu.

    FR-7.11 moved this entry: the top-left 「设置」 pill is gone, and 分组管理 / 权限体检 / 设置 now live
    behind a ⋮ button top-right. This helper used to click `.hero__settings`, which no longer exists —
    the test then died with `Cannot read properties of null (reading 'click')`, which reads like a broken
    page rather than a moved button.
    """
    d.eval("JSON.stringify(document.querySelector('.menu__button').click() ?? null)")
    for _ in range(10):
        if d.eval("!!document.querySelector('.menu__sheet')"):
            break
        time.sleep(0.15)
    clicked = d.eval(
        "JSON.stringify((()=>{const items=[...document.querySelectorAll('.menu__item')];"
        "const hit=items.find(b => b.textContent.trim().startsWith('设置'));"
        "if(!hit) return 'no 设置 entry'; hit.click(); return 'clicked';})())"
    )
    if clicked != "clicked":
        raise RuntimeError(f"could not open settings from the ⋮ menu: {clicked}")
    time.sleep(1.2)


def back_to_list(d):
    d.eval(
        "JSON.stringify((()=>{const b=[...document.querySelectorAll('.bar button')]"
        ".find(x=>/返回/.test(x.textContent)); if(b){b.click(); return b.textContent.trim()} return null})())"
    )
    time.sleep(1.2)


# ---------------------------------------------------------------------------------------
# 1. batchUpdateAlarms, through the selection bar
# ---------------------------------------------------------------------------------------

def scroll_fixture_into_view(d, label):
    """Puts every fixture row inside the viewport, one at a time.

    The phone's viewport is shorter than the emulator's (its hero is taller and its status bar differs),
    so the third fixture row sat below the fold: `rows_with_label` still returned its DOM rect, but that
    rect is outside the visible area, the injected touch landed on nothing, and the run reported "two
    rows ticked" where three were expected. Scrolling the element itself is the only reliable way to
    make a row touchable — it works the same on both devices and does not depend on how tall the hero
    happens to be.
    """
    d.eval(
        "JSON.stringify([...document.querySelectorAll('li.row')]"
        f".filter(r => r.querySelector('.row__label')?.textContent.trim() === {json.dumps(label)})"
        ".map(r => { r.scrollIntoView({block: 'center'}); return true; }))"
    )
    time.sleep(0.6)


def test_batch(d):
    print("== create three throwaway alarms ==")
    before = db_count(d)
    # 00:0x, so the sort (hour, minute) puts the fixture at the **top** of its group. The first version
    # used 03:00 and 04:00 and assumed all three rows were on screen; they were not (the list is
    # `overflow`-clipped, so the DOM rect of a row below the fold is outside the viewport and the touch
    # lands on nothing). The assertion is the same either way, but the fixture no longer depends on
    # where the device's own alarms happen to fall.
    for hour in (0,):
        for minute in (1, 2, 3):
            # enabled: true explicitly, so the test does not depend on the device's default for new alarms.
            d.eval(
                "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.saveAlarm("
                f"{{hour: {hour}, minute: {minute}, label: {json.dumps(TEMP_LABEL)}, enabled: true}})))()"
            )
    time.sleep(1)
    check("the database grew by three", db_count(d), before + 3)
    foreground_toggle(d)
    temps = rows_with_label(d, TEMP_LABEL)
    check("and all three show up in the list", len(temps), 3)
    visible = d.eval(
        "JSON.stringify([...document.querySelectorAll('li.row')]"
        f".filter(r => r.querySelector('.row__label')?.textContent.trim() === {json.dumps(TEMP_LABEL)})"
        ".map(r => {const b = r.getBoundingClientRect();"
        "  return b.top >= 0 && b.bottom <= innerHeight;}))"
    )
    check("all three are on screen (so a touch can reach them)", visible, [True, True, True])
    check(
        "all three start enabled in the database",
        db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}' and enabled=1"),
        3,
    )

    print("== long press the first one to enter selection mode ==")
    leave_selection_mode(d)
    scroll_fixture_into_view(d, TEMP_LABEL)
    temps = rows_with_label(d, TEMP_LABEL)
    touch(d, temps[0]["x"], temps[0]["y"], hold_ms=750)
    time.sleep(2)
    st = d.state()
    check("selection mode on", st["selMode"], True)
    check("one row ticked", st["ticked"], 1)

    print("== tick the other two ==")
    # Each row is scrolled to the middle before its tap, so this does not depend on all three fitting
    # on screen at once (they do on the emulator and did not on the phone).
    for t in temps[1:]:
        scroll_fixture_into_view(d, TEMP_LABEL)
        temps_now = rows_with_label(d, TEMP_LABEL)
        target = next(x for x in temps_now if x["i"] == t["i"])
        touch(d, target["x"], target["y"])
        time.sleep(0.8)
    check("three rows ticked", d.state()["ticked"], 3)
    check(
        "the bar reports the batch before it runs",
        d.eval("JSON.stringify(document.querySelector('.selactions__count').textContent.trim())"),
        "3 项中 3 项已开启",
    )

    print("== 停用: one write for the whole batch ==")
    logcat_clear(d)
    d.eval(
        "JSON.stringify((()=>{const b=[...document.querySelectorAll('.selactions__batch')]"
        ".find(x=>x.textContent.trim()==='停用'); b.click(); return b.textContent.trim()})())"
    )
    time.sleep(1.5)
    calls = recompute_all_lines(d)
    check("the batch cost exactly one recomputeAll, not three", calls, 1)
    check(
        "all three are now disabled in the database",
        db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}' and enabled=0"),
        3,
    )
    check(
        "and none of them is still enabled",
        db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}' and enabled=1"),
        0,
    )

    print("== the page is still in selection mode, showing the new state ==")
    st = d.state()
    check("still selecting", st["selMode"], True)
    check("the ticks survived the write", st["ticked"], 3)
    check(
        "the count now reads zero of three",
        d.eval("JSON.stringify(document.querySelector('.selactions__count').textContent.trim())"),
        "3 项中 0 项已开启",
    )

    print("== 启用 puts them back ==")
    logcat_clear(d)
    d.eval(
        "JSON.stringify((()=>{const b=[...document.querySelectorAll('.selactions__batch')]"
        ".find(x=>x.textContent.trim()==='启用'); b.click(); return b.textContent.trim()})())"
    )
    time.sleep(1.5)
    check("one recomputeAll again", recompute_all_lines(d), 1)
    check(
        "all three are enabled again",
        db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}' and enabled=1"),
        3,
    )

    print("== the select-all toggle: one click ticks every row, the next clears them ==")
    # The toggle's behaviour is "flip everything", so what one click produces depends on what the store
    # already had ticked — and at this point in the run everything *is* ticked (the batch left the
    # selection intact), which means the first click here **clears**. The earlier version assumed
    # otherwise and reported four failures that were the test's misunderstanding. So: read what the
    # button offers, click it, and assert the promised outcome in that direction.
    toggle_label = "JSON.stringify([...document.querySelectorAll('.selbar__action')].pop().textContent.trim())"

    def toggle():
        d.eval("JSON.stringify([...document.querySelectorAll('.selbar__action')].pop().click() ?? null)")
        time.sleep(1)

    offers = d.eval(toggle_label)
    print(f"  the toggle offers: {offers!r}")
    check("the toggle offers one of its two labels", offers in ("全选", "取消全选"), True)

    toggle()
    st = d.state()
    if offers == "取消全选":
        check("clicking 取消全选 clears every tick", st["ticked"], 0)
    else:
        check("clicking 全选 ticks every rendered row", st["ticked"], st["rows"])
    check(
        "and the toggle now offers the opposite",
        d.eval(toggle_label),
        "全选" if offers == "取消全选" else "取消全选",
    )
    check("and stays in selection mode", st["selMode"], True)

    toggle()  # back the other way
    st = d.state()
    check(
        "the second click does the opposite",
        st["ticked"],
        st["rows"] if offers == "取消全选" else 0,
    )
    check(
        "and the batch buttons are disabled exactly when nothing is ticked",
        d.eval("JSON.stringify([...document.querySelectorAll('.selactions__batch')].map(b => b.disabled))"),
        [True, True] if st["ticked"] == 0 else [False, False],
    )

    print("== ✕ leaves selection mode ==")
    d.eval("JSON.stringify(document.querySelector('.selbar__action').click() ?? null)")
    time.sleep(1)
    check("selection mode off", d.state()["selMode"], False)

    print("== remove the throwaway rows ==")
    # Through the bridge, by id, not through the toolbar: this test's job is the batch toggle and the
    # holiday row, and the toolbar's two-step delete is already covered (on both devices) by
    # `t-multiselect.py`. Cleaning up by id is exact — 00:0x is a legal time for a real alarm too, so
    # "tap three rows and delete them" is the kind of fixture handling that can delete someone else's
    # data, and there is no undo. (The first phone run of this test partly did exactly that: it left a
    # row behind because a tap missed a row that was below the fold.)
    ids = alarm_ids_with_label(d, TEMP_LABEL)
    check("the three fixture rows are the only ones to remove", len(ids), 3)
    print(f"  delete -> {delete_alarms(d, ids)}")
    time.sleep(1)
    check("the throwaway rows are gone", db_count(d), before)
    check(
        "and nothing labelled 自测-批改 is left",
        db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}'"),
        0,
    )
    check(
        "and the device's own alarms are untouched",
        db_count(d, f"select count(*) from alarms where label <> '{TEMP_LABEL}'"),
        before,
    )


# ---------------------------------------------------------------------------------------
# 2. getHolidayDataInfo, on the settings page
# ---------------------------------------------------------------------------------------

def test_holiday_row(d):
    print("== the 节假日数据 row on the settings page ==")
    reported = d.eval(
        "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.getHolidayDataInfo()))()"
    )
    print(f"  bridge says: {json.dumps(reported, ensure_ascii=False)}")

    open_settings(d)
    displayed = d.eval(
        "JSON.stringify([...document.querySelectorAll('.row')].map(r => ({"
        "  label: r.querySelector('.row__label')?.textContent.trim() ?? '',"
        "  value: r.querySelector('.mono')?.textContent.trim() ?? null,"
        "  hint: r.querySelector('.row__hint')?.textContent.trim() ?? null,"
        "})).filter(r => r.label === '节假日数据'))"
    )
    print(f"  page shows: {json.dumps(displayed, ensure_ascii=False)}")
    check("the row is on the page", len(displayed), 1)
    if displayed:
        years = reported["years"]
        want_years = f"{years[0]}–{years[-1]} 年" if len(years) > 1 else f"{years[0]} 年"
        expected = want_years + (" · 本年未覆盖" if reported["degraded"] else "")
        check("the value matches what the bridge reported", displayed[0]["value"], expected)
        check("the data source is shown as the hint", displayed[0]["hint"], reported["source"])
    back_to_list(d)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--device", default="emulator-5554")
    p.add_argument("--port", type=int, default=9222)
    p.add_argument("--dpr", type=float, default=2.625)
    p.add_argument("--top", type=int, default=136)
    p.add_argument("--via", choices=["adb", "cdp"], default="adb")
    ARGS.update(vars(p.parse_args()))

    d = ui.Driver(ARGS["device"], ARGS["port"], ARGS["dpr"], ARGS["top"])
    print(f"device={ARGS['device']} via={ARGS['via']}")
    d.keep_screen_on()
    print(f"  webview pid={d.ensure_forward()}")

    cleanup_leftovers(d)
    leave_selection_mode(d)

    test_batch(d)
    test_holiday_row(d)

    print()
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} check(s)")
        for f in FAILURES:
            print(f"  - {f}")
        sys.exit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
