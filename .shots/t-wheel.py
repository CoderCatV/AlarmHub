"""Circular-scroll test for the wheel time picker.

What "circular" has to mean, and what each check pins down:

1. 23 is followed by 00, and 00 is preceded by 23 - scrolling across the boundary wraps instead of
   stopping at the end of the list.
2. The user cannot tell that a re-centre happened, so the highlighted row must always be the row in
   the middle of the wheel - that is the value that will be saved.
3. A re-centre must never be mistaken for a scroll: the value must keep changing monotonically while
   the wheel is spun in one direction, never jumping back.
4. Spinning must not be able to run out of list.

Transport is always CDP touch - see `spin` for why that is not merely a convenience.
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

# The two columns, by their aria-label.
HOUR = "\u5c0f\u65f6"
MINUTE = "\u5206\u949f"

# Header titles that mean "the wheel is on screen".
NEW_ALARM = "\u65b0\u5efa"
EDIT_ALARM = "\u7f16\u8f91"

# Header actions that leave the current page.
EXIT_LABELS = "\u53d6\u6d88|\u8fd4\u56de"


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


COLUMN_JS = """
JSON.stringify((() => {
  const c = [...document.querySelectorAll('.col')].find(x => x.getAttribute('aria-label') === %s);
  const cb = c.getBoundingClientRect();
  const items = [...c.querySelectorAll('.item')];
  const mid = cb.y + cb.height / 2;
  const centreOf = (i) => { const b = i.getBoundingClientRect(); return b.y + b.height / 2; };
  const onScreen = (i) => { const y = centreOf(i); return y >= cb.y - 1 && y <= cb.y + cb.height + 1; };
  const selected = items.filter(i => i.getAttribute('aria-selected') === 'true');
  let best = { d: 1e9, t: '', el: null };
  for (const i of items) { const d = Math.abs(centreOf(i) - mid); if (d < best.d) best = { d, t: i.textContent.trim(), el: i }; }
  return {
    rendered: items.length,
    scrollTop: Math.round(c.scrollTop),
    onScreen: items.filter(onScreen).map(i => i.textContent.trim()),
    selectedTotal: selected.length,
    selectedOnScreen: selected.filter(onScreen).map(i => i.textContent.trim()),
    centred: best.t,
    rect: [Math.round(cb.x), Math.round(cb.y), Math.round(cb.width), Math.round(cb.height)],
  };
})())
"""


def col(d, label):
    return d.eval(COLUMN_JS % json.dumps(label))


def spin(d, label, items):
    """Drag the column by `items` rows (positive = later values). One gesture only.

    Transport is CDP touch, not `adb shell input swipe`, for two reasons:

    * `input swipe` interpolates over very few samples, so even a "slow" 48 px drag arrives as one
      large instantaneous delta. Chrome reads that as a flick and the wheel crosses two rows - the
      test then measures the injection tool, not the wheel. CDP touch interpolates over several
      moves, which is what a real finger looks like.
    * It is the only transport available on the phone: HyperOS denies the shell `INJECT_EVENTS`.

    So the same test body runs against the emulator and the real device, and a failure means the
    wheel changed rather than the tooling.

    Sign convention, which this test got wrong once and read as "the wheel went backwards":
    dragging the finger **up** moves the content up, which advances to later values.
    """
    info = col(d, label)
    x, y, _, h = info["rect"]
    cx = x + 20
    cy = y + h / 2
    y0 = cy + (30 if items > 0 else -30)
    d.eval_touch(cx, y0, hold_ms=300, dx=0, dy=-items * 48)
    time.sleep(1.1)


def spin_to(d, label, target, count=24):
    """Spin until the centred row reads `target`, taking the shorter way round the circle.

    This is deliberately a loop of real gestures rather than a programmatic jump: it exercises the
    wrap on the way (there is no direction that hits an end), and it reaches values that are not on
    screen, which a tap cannot do.
    """
    for _ in range(60):
        cur = col(d, label)["centred"]
        if cur == target:
            return
        delta = (int(target) - int(cur)) % count
        if delta > count // 2:
            delta -= count
        step = 1 if delta > 0 else -1
        if abs(delta) >= 4:
            step *= 3
        spin(d, label, step)
    raise SystemExit(f"could not spin to {target}; stuck at {col(d, label)['centred']!r}")


def title(d):
    # JSON.stringify so the value parses unambiguously: a bare string can collide with the probe's
    # own "--- page console problems ---" trailer, which is what happened on the phone.
    return d.eval("JSON.stringify(document.querySelector('.bar__title')?.textContent.trim() ?? '')")


def open_editor(d):
    """Land on the wheel: either the editor is already open, or the list's + opens it."""
    page = title(d)
    if NEW_ALARM in page or EDIT_ALARM in page:
        return page
    for _ in range(3):
        d.eval("JSON.stringify(document.querySelector('.fab')?.click() ?? null)")
        time.sleep(2)
        page = title(d)
        if NEW_ALARM in page or EDIT_ALARM in page:
            return page
        # Not on the list: walk back with whatever exit the header offers.
        d.eval(
            "JSON.stringify((()=>{const b=[...document.querySelectorAll('.bar button')]"
            f".find(x=>/{EXIT_LABELS}/.test(x.textContent)); if(b){{b.click(); return 'back'}} return 'nothing'}})())"
        )
        time.sleep(1.5)
    raise SystemExit(f"could not reach the editor (title={page!r}); the wheel is not on screen")


def main():
    device = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 9222
    # dpr/top only matter for the adb transport; CDP touch works in page coordinates.
    d = ui.Driver(device, port, 2.625, 136)
    d.keep_screen_on()
    print(f"device={device} webview pid={d.ensure_forward()}")
    print(f"page: {open_editor(d)!r}")

    print("== the column is really tripled ==")
    h = col(d, HOUR)
    m = col(d, MINUTE)
    print(f"  hour: rendered={h['rendered']} scrollTop={h['scrollTop']} rect={h['rect']}")
    print(f"  minute: rendered={m['rendered']} scrollTop={m['scrollTop']}")
    check("hour column renders 3 x 24", h["rendered"], 72)
    check("minute column renders 3 x 60", m["rendered"], 180)

    print("== the highlight is on the centred row, exactly once on screen ==")
    print(f"  onScreen={h['onScreen']}")
    check("the value is marked in every copy", h["selectedTotal"], 3)
    check("but only one of those is on screen", len(h["selectedOnScreen"]), 1)
    check("and it is the centred row", h["selectedOnScreen"][0], h["centred"])

    print("== one small drag moves exactly one row ==")
    before = col(d, HOUR)["centred"]
    spin(d, HOUR, 1)
    after = col(d, HOUR)["centred"]
    check("a 48px drag advances exactly one hour", (int(before) + 1) % 24, int(after))

    print("== wrap forward: 23 then one more row ==")
    spin_to(d, HOUR, "23")
    check("spun to 23", col(d, HOUR)["centred"], "23")
    spin(d, HOUR, 1)
    info = col(d, HOUR)
    print(f"  after one row past 23: centred={info['centred']!r} onScreen={info['onScreen']}")
    check("one row past 23 is 00, not a stop", info["centred"], "00")

    print("== wrap backward: 00 then one row back ==")
    spin(d, HOUR, -1)
    info = col(d, HOUR)
    print(f"  after one row before 00: centred={info['centred']!r} onScreen={info['onScreen']}")
    check("one row before 00 is 23, not a stop", info["centred"], "23")

    print("== spinning one way is monotonic and never runs out ==")
    spin_to(d, HOUR, "00")
    seen = []
    for _ in range(10):
        spin(d, HOUR, 1)
        info = col(d, HOUR)
        seen.append(info["centred"])
        dom = info["scrollTop"] / 48
        # A re-centre must not be visible as the column pinned to either end of the tripled list.
        if not (24 <= dom < 48):
            FAILURES.append(f"the column sat outside the middle copy: domIndex={dom}")
        check("still exactly one highlighted row on screen", len(info["selectedOnScreen"]), 1)
    print(f"  values seen while spinning forward: {seen}")
    check("ten single steps from 00", seen, [f"{i % 24:02d}" for i in range(1, 11)])

    print("== a long run of spins crosses the boundary several times ==")
    far = []
    for _ in range(30):
        spin(d, HOUR, 1)
        far.append(col(d, HOUR)["centred"])
    print(f"  last six: {far[-6:]}")
    check(
        "thirty single steps from 10 stayed a clean mod-24 walk",
        far,
        [f"{(11 + i) % 24:02d}" for i in range(30)],
    )

    print("== the minute column wraps too ==")
    spin_to(d, MINUTE, "58", count=60)
    check("spun to 58", col(d, MINUTE)["centred"], "58")
    spin(d, MINUTE, 3)
    info = col(d, MINUTE)
    print(f"  after three rows past 58: centred={info['centred']!r} onScreen={info['onScreen']}")
    check("three rows past 58 is 01", info["centred"], "01")
    spin(d, MINUTE, -3)
    check("and three rows back is 58 again", col(d, MINUTE)["centred"], "58")

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
