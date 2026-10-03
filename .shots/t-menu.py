"""FR-7.11 — the hero's ⋮ overflow menu.

Assertions, in the order a user meets them:

1. the ⋮ button is **top-right** and the old top-left 「设置」 pill is gone (that is the whole point
   of the change: 分组管理 has to be nearer than 设置, not two taps deeper inside it);
2. opening it does **not move the list** — the same invariant the selection bar lives under, because a
   long press in progress would otherwise tick the wrong row;
3. it lists exactly 分组管理 / 权限体检 / 设置, in that order;
4. each entry actually lands on its page;
5. tapping outside closes it, and entering selection mode closes it too.

Run: python -u .shots/t-menu.py [device] [port] [dpr] [top]
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
EXPECTED_ITEMS = ["分组管理", "权限体检", "设置"]


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def js(d, expr):
    return d.eval(f"JSON.stringify({expr})")


def rect(d, selector):
    return d.eval(
        f"(()=>{{const e=document.querySelector({json.dumps(selector)});"
        "if(!e) return null;const b=e.getBoundingClientRect();"
        "return JSON.stringify({x:b.x,y:b.y,w:b.width,h:b.height,right:b.right,cx:b.x+b.width/2,cy:b.y+b.height/2});})()"
    )


def page_title(d):
    return d.eval("JSON.stringify(document.querySelector('.bar__title')?.textContent.trim() ?? null)")


def close_menu_if_open(d):
    """Dismiss the menu through its own scrim, so every step starts from the same state.

    Without this, the step that measures 「does opening move the list」 left the menu open, and the next
    step's click on ⋯ **toggled it closed** — the item lookup then found nothing and the failure read as
    "分组管理 is missing from the menu". The product was right; the test forgot that ⋯ is a toggle.
    """
    if d.eval("!!document.querySelector('.menu__sheet')"):
        d.eval("JSON.stringify(document.querySelector('.menu__scrim')?.click() ?? null)")
        for _ in range(10):
            if not d.eval("!!document.querySelector('.menu__sheet')"):
                break
            time.sleep(0.15)


def open_menu(d):
    close_menu_if_open(d)
    d.eval("JSON.stringify(document.querySelector('.menu__button').click() ?? null)")
    # Wait for the sheet to actually be in the DOM before touching it. Skipping this made the first
    # iteration of the "each entry lands on its page" loop read an empty item list — the click had
    # landed but Vue had not rendered yet, and the failure looked like a missing menu entry.
    for _ in range(10):
        if d.eval("!!document.querySelector('.menu__sheet')"):
            break
        time.sleep(0.15)
    time.sleep(0.2)


def on_list(d):
    """True when the list page is showing (hero present)."""
    return bool(d.state().get("hero"))


def go_back_to_list(d):
    for _ in range(4):
        if on_list(d):
            return True
        d.eval(
            "JSON.stringify(document.querySelector('.bar button')?.click() ?? null)"
        )
        time.sleep(1.2)
    return on_list(d)


def plugin(d, expr):
    return d.eval(f"(async()=>JSON.stringify({expr}))()")


def alarm_ids_with_label(d, label):
    raw = plugin(
        d,
        "(await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
        f".filter(a => a.label === {json.dumps(label)}).map(a => a.id)",
    )
    return raw if isinstance(raw, list) else []


def menu_items(d):
    """The entries as they are right now. Re-read every time: navigating away destroys the sheet, and a
    cached list made the first version of this test index into nothing."""
    return d.eval("JSON.stringify([...document.querySelectorAll('.menu__item')].map(b => b.textContent.trim()))") or []


def click_menu_item(d, label):
    """Opens the menu and clicks the entry whose text starts with `label`.

    Doing it in one page call (rather than open here, click there) keeps the two halves from racing the
    render — and if the sheet is not there, it says so instead of throwing on `.click` of undefined.
    """
    return d.eval(
        "JSON.stringify((()=>{"
        "  const items=[...document.querySelectorAll('.menu__item')];"
        f"  const hit=items.find(b => b.textContent.trim().startsWith({json.dumps(label)}));"
        "  if(!hit) return 'no such entry: ' + items.map(b=>b.textContent.trim()).join('|');"
        "  hit.click(); return 'clicked ' + hit.textContent.trim();"
        "})())"
    )


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

    if not go_back_to_list(d):
        print("  ! could not reach the list page")
        return 1

    print("== 0. seed one throwaway alarm (so the 'list must not move' check has a row) ==")
    stale = alarm_ids_with_label(d, "自测-菜单")
    if stale:
        plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: {json.dumps(stale)}}})")
        time.sleep(1)
    created = plugin(
        d,
        "await window.Capacitor.Plugins.AlarmHub.saveAlarm("
        "{hour: 5, minute: 5, label: '自测-菜单', enabled: true, deleteAfterRing: false})",
    )
    print(f"  created: {created.get('id') if isinstance(created, dict) else created}")
    time.sleep(1)
    d.foreground_toggle()
    time.sleep(1)

    print("== 1. the button moved to the top-right, and the old pill is gone ==")
    btn = rect(d, ".menu__button")
    print(f"  .menu__button rect: {btn}")
    check("the ⋮ button exists", btn is not None, True)
    if btn:
        vw = d.eval("innerWidth")
        check("it sits in the right half of the hero", btn["cx"] > vw / 2, True)
        check("and near the top", btn["y"] < 120, True)
    check("the old top-left 「设置」 pill is gone", d.eval("!!document.querySelector('.hero__settings')"), False)

    print("== 2. opening the menu must not move the list ==")
    row_before = d.eval(
        "(()=>{const r=document.querySelector('li.row');if(!r) return null;"
        "const b=r.getBoundingClientRect();return JSON.stringify({y:b.y});})()"
    )
    hero_before = d.eval("document.querySelector('.hero').getBoundingClientRect().height")
    open_menu(d)
    row_after = d.eval(
        "(()=>{const r=document.querySelector('li.row');if(!r) return null;"
        "const b=r.getBoundingClientRect();return JSON.stringify({y:b.y});})()"
    )
    hero_after = d.eval("document.querySelector('.hero').getBoundingClientRect().height")
    print(f"  first row y: {row_before} -> {row_after}   hero height: {hero_before} -> {hero_after}")
    check("there is a row to measure", row_before is not None, True)
    if row_before is not None:
        check("the first row did not move", row_after, row_before)
    check("the hero did not change height", hero_after, hero_before)

    print("== 3. it lists the three entries, in order ==")
    items = menu_items(d)
    print(f"  items: {json.dumps(items, ensure_ascii=False)}")
    check("three entries", len(items), 3)
    check("第一个是分组管理（核心功能放最近）", [x.split()[0] for x in items[:1]], ["分组管理"])
    check("三项及其顺序", [x.split()[0] for x in items], EXPECTED_ITEMS)

    print("== 4. each entry lands on its page ==")
    for name in EXPECTED_ITEMS:
        # Re-open the menu for every entry: the previous navigation destroyed it (by design), and the
        # first version of this test kept clicking into a stale list.
        if not go_back_to_list(d):
            check(f"back on the list before opening {name}", False, True)
            break
        open_menu(d)
        clicked = click_menu_item(d, name)
        print(f"  {clicked}")
        time.sleep(1.5)
        check(f"「{name}」opens its page", page_title(d), name)
        check(f"and the menu closed behind it ({name})", d.eval("!!document.querySelector('.menu__sheet')"), False)

    if not go_back_to_list(d):
        print("  ! could not get back to the list at the end")
        return 1

    print("== 5. tapping outside closes it; entering selection mode closes it ==")
    open_menu(d)
    check("menu is open", d.eval("!!document.querySelector('.menu__sheet')"), True)
    d.eval("JSON.stringify(document.querySelector('.menu__scrim').click() ?? null)")
    time.sleep(0.5)
    check("tapping the scrim closed it", d.eval("!!document.querySelector('.menu__sheet')"), False)

    open_menu(d)
    check("menu is open again", d.eval("!!document.querySelector('.menu__sheet')"), True)
    # Enter selection mode through the DOM: 450ms hold is what AlarmRow listens for.
    d.eval(
        "JSON.stringify((()=>{const b=document.querySelector('li.row .row__main');"
        "if(!b) return 'no row';"
        "b.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,button:0,clientX:10,clientY:10}));"
        "return 'pressed';})())"
    )
    time.sleep(0.9)
    d.eval(
        "JSON.stringify((()=>{const b=document.querySelector('li.row .row__main');"
        "b.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,clientX:10,clientY:10}));return 'released';})())"
    )
    time.sleep(1.0)
    st = d.state()
    print(f"  after the long press: selMode={st['selMode']}")
    if st["selMode"]:
        check("entering selection mode closed the menu", d.eval("!!document.querySelector('.menu__sheet')"), False)
        d.eval("JSON.stringify(document.querySelector('.selbar__action').click() ?? null)")
        time.sleep(0.8)
    else:
        print("  ! could not enter selection mode through the DOM (no alarm rows?) — skipped that half")

    print("== 6. the seeded alarm is removed ==")
    left = alarm_ids_with_label(d, "自测-菜单")
    if left:
        plugin(d, f"await window.Capacitor.Plugins.AlarmHub.deleteAlarms({{ids: {json.dumps(left)}}})")
        time.sleep(1)
    check("no throwaway alarm left behind", alarm_ids_with_label(d, "自测-菜单"), [])

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
