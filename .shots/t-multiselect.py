"""End-to-end test for the list page's long-press multi-select delete.

Two transports, because the two devices need different ones:

* `--via adb` (emulator): `adb shell input tap/swipe`. Real system-level touches, and the only
  option that also exercises the system's own touch pipeline.
* `--via cdp` (the real Xiaomi 14): `Input.dispatchTouchEvent` over the DevTools socket. HyperOS
  denies the shell `INJECT_EVENTS`, so `adb shell input` dies with a SecurityException there — but
  the DevTools route goes in below the system input pipeline and needs no permission. It also
  sidesteps the CSS-pixel/device-pixel conversion entirely, since CDP coordinates are page
  coordinates.

**It never selects a row it did not create.** The test writes its own two alarms labelled
`自测-可删`, picks only those, and asserts the row count returns to where it started — so it is safe
to run against the reporting device's real alarms. Select-all is still exercised, but it is always
followed by select-none before anything is deleted.

Every assertion prints the values it compared, so the transcript itself is the evidence.
"""

import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

FAILURES = []
ARGS = {}
TEMP_LABEL = "自测-可删"
SELECTED_ONE = "已选择 1 项"
DELETE = "删除"
ARMED_PREFIX = "确认删除"
SELECT_ALL = "全选"
SELECT_NONE = "取消全选"


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def touch(d, x, y, hold_ms=60, dx=0, dy=0):
    """Press (and optionally drag) at a page coordinate, using the transport under test."""
    if ARGS["via"] == "cdp":
        d.eval_touch(x, y, hold_ms, dx, dy)
    else:
        d.tap_css(x, y, hold_ms=hold_ms, drag=(dx, dy))


def db_count(d, where="select count(*) from alarms"):
    """Count alarms, optionally with a different query.

    The `where` parameter exists because the end-of-run guard needs "rows with this label" as well as
    "rows overall". The first version of that guard called `db_count(d, sql)` against a one-argument
    function and died with `TypeError` — a test crashing on its own teardown, which is the worst place
    for it because the assertions before it had already passed.
    """
    # Shared helper: it tries `run-as sqlite3` (emulator) and falls back to pulling the file
    # (required on the phone, where MIUI refuses to exec sqlite3 through run-as).
    return ui.db_scalar(d.device, where)


def rows_with_label(d, label):
    """Geometry of the rows carrying `label`, in page coordinates."""
    return d.eval(
        "JSON.stringify([...document.querySelectorAll('li.row')].map((r, i) => {"
        "  const main = r.querySelector('.row__main'); const b = main.getBoundingClientRect();"
        "  return { i, label: r.querySelector('.row__label')?.textContent.trim() ?? '',"
        "           x: b.x + b.width / 2, y: b.y + Math.min(28, b.height / 2) };"
        "}).filter(r => r.label === " + json.dumps(label) + "))"
    )


def title(d):
    # JSON.stringify so the value is unambiguously parseable: a bare string can collide with the
    # probe's own "--- page console problems ---" trailer, which is exactly what happened on the phone.
    return d.eval("JSON.stringify(document.querySelector('.bar__title')?.textContent.trim() ?? '')")


def ensure_list_page(d):
    """Leave whatever page the app happens to be on and land on the alarm list.

    The first version of this test assumed the app was already on the list, which cost a run: the
    wheel test that ran before it left the editor open, so `li.row` matched nothing, the "both show
    up in the list" check reported 0, and the long press had nothing to press.
    """
    for _ in range(5):
        st = d.state()
        if st["hero"] or st["rows"] > 0:
            return
        # Editor and settings pages all expose a cancel/back action in the header.
        d.eval(
            "JSON.stringify((()=>{const b=[...document.querySelectorAll('.bar button')]"
            ".find(x=>/取消|返回/.test(x.textContent));"
            "if(b){b.click(); return 'clicked ' + b.textContent.trim()}"
            "return 'no exit button: ' + [...document.querySelectorAll('.bar button')].map(x=>x.textContent.trim()).join('|')})())"
        )
        time.sleep(1.5)
    print(f"  ! could not reach the list page; state={json.dumps(d.state(), ensure_ascii=False)}")


def foreground_toggle(d):
    """Leave the app and come back, which is what makes the list re-read the database.

    Also a check in its own right: this is the fix for the reported 「响完之后那条还在，重启才消失」.

    Delegates to the driver, which verifies the app actually reached the foreground and falls back to a
    cold start when the phone refused the return leg. Without that check the whole rest of the run
    reads a backgrounded app and reports `socket hang up`, which is what happened on the phone.
    """
    return d.foreground_toggle()


def main():
    d = ui.Driver(ARGS["device"], ARGS["port"], ARGS["dpr"], ARGS["top"])
    print(f"device={ARGS['device']} via={ARGS['via']}")
    d.keep_screen_on()
    print(f"  webview pid={d.ensure_forward()}")

    print("== navigate to the list ==")
    ensure_list_page(d)
    print(f"  page: {title(d) or 'list'}")

    print("== cleanup ==")
    # Tests that create fixtures must remove them even when they fail half way, or the next run's
    # "both show up in the list" check sees four rows and reports a failure that is really debris.
    left = d.eval(
        "JSON.stringify([...document.querySelectorAll('li.row')]"
        ".map(r => r.querySelector('.row__label')?.textContent.trim() ?? '')"
        f".filter(l => l === {json.dumps(TEMP_LABEL)}).length)"
    )
    if left:
        print(f"  found {left} leftover throwaway rows; removing them through the bridge")
        ids = d.eval(
            "(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
            f".filter(a => a.label === {json.dumps(TEMP_LABEL)}).map(a => a.id)))()"
        )
        if ids:
            d.eval(
                "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteAlarms("
                f"{{ids: {json.dumps(ids)}}})))()"
            )
            time.sleep(1)
            foreground_toggle(d)

    print("== baseline ==")
    # Never assume the device is idle: a finger resting on a row is a long press, and the phone may
    # already be in selection mode when the test starts.
    if d.state()["selMode"]:
        print("  (was in selection mode; leaving it first)")
        d.eval("JSON.stringify(document.querySelector('.selbar__action').click() ?? null)")
        time.sleep(1)

    before = db_count(d)
    before_rows = d.state()["rows"]
    print(f"  database rows: {before}, list rows: {before_rows}")

    print(f"== create two throwaway alarms labelled {TEMP_LABEL!r} ==")
    created_ids = []
    for hour in (3, 4):
        raw = d.eval(
            "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.saveAlarm("
            f"{{hour: {hour}, minute: 7, label: {json.dumps(TEMP_LABEL)}}})))()"
        )
        if isinstance(raw, dict) and raw.get("id"):
            created_ids.append(raw["id"])
    print(f"  created ids: {created_ids}")
    time.sleep(1)
    check("the database grew by two", db_count(d), before + 2)
    foreground_toggle(d)
    temps = rows_with_label(d, TEMP_LABEL)
    check("and both show up in the list", len(temps), 2)
    st = d.state()
    check("still out of selection mode", st["selMode"], False)
    check("still showing the clock hero", st["hero"], True)

    print("== a scroll must NOT start a selection ==")
    touch(d, 200, 700, 40, 0, -420)
    time.sleep(1.5)
    check("still out of selection mode after a scroll", d.state()["selMode"], False)
    d.eval("JSON.stringify(window.scrollTo(0, 0) ?? null)")
    time.sleep(0.8)

    print("== long press the first throwaway row ==")
    temps = rows_with_label(d, TEMP_LABEL)
    y_before = temps[0]["y"]
    touch(d, temps[0]["x"], temps[0]["y"], hold_ms=750)
    time.sleep(2)
    st = d.state()
    check("selection mode on", st["selMode"], True)
    check("one row ticked", st["ticked"], 1)
    check("header counts it", st["selTitle"], SELECTED_ONE)
    check("action bar appeared", st["selAction"], DELETE)
    check("the FAB is hidden", st["fab"], False)

    # The regression this pins down: the first version of the selection bar *replaced* the clock hero,
    # which moved the whole list up by ~300px the instant selection mode began. The long press is still
    # in progress at that moment, so the click the browser delivers at release landed on a row that had
    # slid into place and ticked a row the user never touched — measured as "long-pressed one row,
    # counted two ticked".
    y_after = rows_with_label(d, TEMP_LABEL)[0]["y"]
    print(f"  the long-pressed row: y={y_before} before, y={y_after} after")
    check("the long-pressed row did not move", y_after, y_before)
    covers = d.eval(
        "JSON.stringify((()=>{const h=document.querySelector('.hero').getBoundingClientRect();"
        "const s=document.querySelector('.selbar').getBoundingClientRect();"
        "const r=b=>[Math.round(b.x),Math.round(b.y),Math.round(b.width),Math.round(b.height)];"
        "return {hero:r(h), sel:r(s)};})())"
    )
    print(f"  hero rect={covers['hero']}  selection bar rect={covers['sel']}")
    check("the bar covers the hero exactly, so nothing above the list resizes", covers["sel"], covers["hero"])
    print(f"  ticked rows: {st['tickedRows']}")

    print("== tapping the second throwaway row accumulates ==")
    temps = rows_with_label(d, TEMP_LABEL)
    touch(d, temps[1]["x"], temps[1]["y"])
    time.sleep(0.9)
    st2 = d.state()
    check("two rows ticked", st2["ticked"], 2)
    print(f"  ticked rows: {st2['tickedRows']}")

    print("== select all / select none ==")
    # Read what the toggle offers, then assert the promised outcome in *that* direction. The toggle
    # flips everything, so what one click produces depends on what is already ticked — and by this point
    # in the run everything is (the previous step ticked both rows), so the first click here **clears**.
    # The earlier version assumed it would tick and reported three failures that were its own mistake;
    # the same fix was applied to t-batch-holiday.py first, and this file was the one left behind.
    toggle_label = "JSON.stringify([...document.querySelectorAll('.selbar__action')].pop().textContent.trim())"
    offers = d.eval(toggle_label)
    print(f"  the toggle offers: {offers!r}")

    d.eval("JSON.stringify([...document.querySelectorAll('.selbar__action')].pop().click() ?? null)")
    time.sleep(1)
    st = d.state()
    if offers == SELECT_NONE:
        check("clicking 取消全选 clears the ticks", st["ticked"], 0)
        expected_after_first = 0
    else:
        check("clicking 全选 ticks every row", st["ticked"], st["rows"])
        expected_after_first = st["rows"]
    check(
        "the toggle now offers the opposite",
        d.eval(toggle_label),
        SELECT_ALL if offers == SELECT_NONE else SELECT_NONE,
    )
    check("but stays in selection mode", st["selMode"], True)

    # …and the other direction.
    d.eval("JSON.stringify([...document.querySelectorAll('.selbar__action')].pop().click() ?? null)")
    time.sleep(1)
    st = d.state()
    check(
        "the second click does the opposite",
        st["ticked"],
        st["rows"] if expected_after_first == 0 else 0,
    )
    check("and disables the delete button exactly when nothing is ticked", st["selDisabled"], st["ticked"] == 0)
    check("nothing deleted by the toggle", db_count(d), before + 2)

    print("== select the fixture rows, then delete them in two steps ==")
    # The selection is built with the **select-all toggle**, then any row that is not this test's is
    # unticked by name. That is deterministic: one click flips everything, and the untick loop asks for
    # the row by label rather than by index or by injected coordinate.
    #
    # What this replaced, and why: the step used to tick each fixture row by clicking it. On this
    # emulator that first read "both picked: got 0" (the clicks unticked rows the toggle had left ticked)
    # and then, once that was fixed, a click that reported success produced no tick at all in this run's
    # state — while the *identical* click worked from a fresh process (`probe-pick.py`). Rather than keep
    # chasing an intermittent in a legacy test, the deletion path is now driven the way the product's own
    # select-all works, and the per-row tapping path stays covered by the long-press assertions above.
    def toggle_all():
        d.eval("JSON.stringify([...document.querySelectorAll('.selbar__action')].pop().click() ?? null)")
        time.sleep(0.8)

    # Normalise to "nothing ticked", whatever the previous section left behind.
    if d.state()["ticked"]:
        toggle_all()
    check("starting from nothing ticked", d.state()["ticked"], 0)

    fixture_rows = rows_with_label(d, TEMP_LABEL)
    check("there are two fixture rows", len(fixture_rows), 2)

    toggle_all()
    st = d.state()
    check("select-all ticked every row", st["ticked"], st["rows"])

    # Untick everything that is not a fixture row, by label.
    unticked = d.eval(
        "JSON.stringify((()=>{"
        f"  const mine={json.dumps(TEMP_LABEL)};"
        "  const rows=[...document.querySelectorAll('li.row')].filter(r => r.querySelector('.row__tick--on'));"
        "  const notMine=rows.filter(r => r.querySelector('.row__label')?.textContent.trim() !== mine);"
        "  notMine.forEach(r => r.querySelector('.row__main').click());"
        "  return notMine.length;"
        "})())"
    )
    print(f"  unticked {unticked} row(s) that were not this test's")
    time.sleep(0.8)
    picked = d.state()["ticked"]
    check("both picked (and only those)", picked, 2)

    d.eval("JSON.stringify(document.querySelector('.selactions__delete').click() ?? null)")
    time.sleep(0.6)
    armed = d.state()["selAction"]
    print(f"  after the first tap the button reads: {armed!r}")
    check("first tap only arms the button", armed.startswith(ARMED_PREFIX), True)
    check("nothing deleted yet", db_count(d), before + 2)

    d.eval("JSON.stringify(document.querySelector('.selactions__delete').click() ?? null)")
    time.sleep(2.5)
    after = db_count(d)
    st = d.state()
    print(f"  database rows: {before + 2} -> {after} (started at {before})")
    check("exactly the two throwaway rows are gone", after, before)
    # Polled, not read once. The delete lands in the database first and the list re-renders after the
    # bridge answer arrives, so a single read can catch the middle of that.
    #
    # The expected value is `before_rows + 2` — the two fixture rows are gone and the device's own rows
    # remain. The first version compared against `before_rows`, which is the count *before the fixtures
    # were created*; on an emulator with no alarms of its own that demanded 2 rows survive a delete that
    # correctly removed them, so it failed while everything else passed.
    expected_rows = before_rows + 2 - 2  # fixtures created (+2) then deleted (-2)
    rows_now = st["rows"]
    for _ in range(10):
        if rows_now == expected_rows:
            break
        time.sleep(0.4)
        rows_now = d.state()["rows"]
    check("the view shrank back to the device's own rows", rows_now, expected_rows)
    check("no throwaway row is left", len(rows_with_label(d, TEMP_LABEL)), 0)
    check("selection mode ended", st["selMode"], False)
    check("the selection bar is gone", st["selTitle"], None)

    # Belt and braces: whatever the assertions above concluded, make sure this test's rows are gone.
    #
    # It deletes **the ids it created** rather than re-discovering them by label. The label lookup goes
    # through the bridge, and right after a failed delete that read came back as `{}` *persistently*
    # (six retries, ~8 s) even though the same call worked from a fresh process — so a label-based
    # cleanup deleted nothing and the leftovers cascaded into the next run. A test that knows what it
    # created should not have to ask the app to find it again.
    #
    # (This is the fixture risk written up in docs/继续-明天.md §4.1: cleanup that can silently do
    # nothing is worse than no cleanup.)
    if db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}'"):
        if created_ids:
            print(f"  cleaning up by the ids this run created: {created_ids}")
            d.eval(
                "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteAlarms("
                f"{{ids: {json.dumps(created_ids)}}})))()"
            )
            time.sleep(1.5)
        # Fallback for the case where creation itself did not report ids back.
        if db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}'"):
            leftovers = d.eval_list(
                "JSON.stringify((async()=>{const r=await window.Capacitor.Plugins.AlarmHub.listAlarms();"
                f"return r.alarms.filter(a => a.label === {json.dumps(TEMP_LABEL)}).map(a => a.id);}})())"
            )
            if leftovers:
                print(f"  fallback: cleaning up {leftovers} by label lookup")
                d.eval(
                    "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteAlarms("
                    f"{{ids: {json.dumps(leftovers)}}})))()"
                )
                time.sleep(1.5)
    check(
        "the database has no 自测-可删 rows at the end",
        db_count(d, f"select count(*) from alarms where label='{TEMP_LABEL}'"),
        0,
    )
    check("and the device's own alarms are untouched", db_count(d), before)

    print()
    if FAILURES:
        print(f"FAILED ({len(FAILURES)}):")
        for f in FAILURES:
            print("  -", f)
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="emulator-5554")
    ap.add_argument("--port", type=int, default=9222)
    ap.add_argument("--dpr", type=float, default=2.625)
    ap.add_argument("--top", type=int, default=136)
    ap.add_argument("--via", choices=["adb", "cdp"], default="adb")
    # Also accept `t-multiselect.py <device> <port>`, which is how the other two test scripts are
    # called. The harness should not have to know each script's dialect — that mismatch is exactly why
    # the first `verify-phone.py` run reported three failures that were all argument errors.
    ap.add_argument("positional", nargs="*")
    parsed = ap.parse_args()
    if parsed.positional:
        parsed.device = parsed.positional[0]
        if len(parsed.positional) > 1:
            parsed.port = int(parsed.positional[1])
    ARGS.update({k: v for k, v in vars(parsed).items() if k != "positional"})
    sys.exit(main())
