"""Does the settings page's 节假日数据 row follow the bridge, in **both** states?

The degraded state is the one PRD FR-6.3 exists for: the bundled table ships inside the APK, covers
2025–2026, and one day stops covering the current year, at which point every 节假日/调休 rule silently
falls back to weekday-only. The row has to say so, and has to look different from the normal state.

The state is forced with the **emulator's own clock**, because that is the real lever
(`getHolidayDataInfo` asks `coversYear(currentYear)` on every call):

    adb shell settings put global auto_time 0
    adb shell date 030110002027.00      # → degraded: the table does not cover 2027
    python -u .shots/probe-holiday-degraded.py --expect degraded
    adb shell date <real time>; adb shell settings put global auto_time 1
    python -u .shots/probe-holiday-degraded.py --expect normal

A first attempt patched `Capacitor.Plugins.AlarmHub.getHolidayDataInfo` from the console and expected
the page to show the patched answer. It does not: the page calls the `registerPlugin` proxy, not the
plugin object, and defining a property on that object does not reach the proxy's view of the method.
Worth recording, because "patch the plugin, re-read the page" is the obvious way to test a degraded
branch and it silently proves nothing here — the run looked like a product failure and was a test
failure.

Reads are returned as `|`-delimited **strings**: an earlier version asked for an object and got the string
`'{'` back, which made the probe prove nothing. That was a bug in `ui-drive.eval()` (it fell back to the
first line of the probe's pretty-printed output), now fixed — see docs/STATUS.md §1.1 #24. The delimited
string stayed because it is the shape that cannot be misread.
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

BACK = (
    "JSON.stringify((()=>{"
    "  const b = [...document.querySelectorAll('.bar button')].find(x => /返回/.test(x.textContent));"
    "  if (b) { b.click(); return 'clicked ' + b.textContent.trim(); }"
    "  return 'not on a subpage';"
    "})())"
)

ENTER_SETTINGS = "JSON.stringify(document.querySelector('.hero__settings')?.click() ?? null)"

# One read, one string: the row's value, whether it carries the warning class, the colour it was
# actually painted with, and the hint.
ROW = (
    "JSON.stringify((()=>{"
    "  const r = [...document.querySelectorAll('.row')]"
    "    .find(x => x.querySelector('.row__label')?.textContent.trim() === '节假日数据');"
    "  if (!r) return 'MISSING';"
    "  const m = r.querySelector('.mono');"
    "  return ["
    "    m.textContent.trim(),"
    "    m.classList.contains('mono--warn') ? 'warn' : 'plain',"
    "    getComputedStyle(m).color,"
    "    r.querySelector('.row__hint')?.textContent.trim() ?? '',"
    "  ].join('|');"
    "})())"
)

# What the bridge itself answered — read through the page's own plugin handle, which is the same object
# the page's bridge proxy reads methods from.
BRIDGE = (
    "(async()=>{"
    "  const a = await window.Capacitor.Plugins.AlarmHub.getHolidayDataInfo();"
    "  return 'years=' + a.years.join(',') + ' degraded=' + a.degraded + ' source=' + a.source;"
    "})()"
)

DEVICE_DATE = (
    "(async()=>{"
    "  const a = await window.Capacitor.Plugins.AlarmHub.getHolidayDataInfo();"
    "  const now = new Date();"
    "  return 'deviceYear=' + now.getFullYear() + ' degraded=' + a.degraded;"
    "})()"
)


def read_row(d):
    raw = d.eval(ROW)
    if not isinstance(raw, str) or raw == "MISSING":
        return None
    parts = raw.split("|")
    return {
        "value": parts[0],
        "warn": parts[1] == "warn",
        "colour": parts[2],
        "hint": "|".join(parts[3:]),
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--expect", choices=["degraded", "normal"], required=True)
    p.add_argument("--device", default="emulator-5554")
    args = p.parse_args()

    d = ui.Driver(args.device, 9222, 2.625, 136)
    d.keep_screen_on()
    print(f"device={args.device}  expecting={args.expect}")
    print(f"  webview pid={d.ensure_forward()}")

    print(f"  device/bridge: {d.eval(DEVICE_DATE)}")
    print(f"  bridge answer: {d.eval(BRIDGE)}")

    # Leave and re-enter the page so it mounts and asks the bridge again.
    d.eval(BACK)
    time.sleep(1.2)
    d.eval(ENTER_SETTINGS)
    time.sleep(1.5)
    row = read_row(d)
    print(f"  row: {json.dumps(row, ensure_ascii=False)}")
    d.eval(BACK)

    if row is None:
        print("VERDICT: FAIL (the 节假日数据 row is not on the settings page)")
        return 1

    degraded_expected = args.expect == "degraded"
    problems = []
    if degraded_expected:
        if "本年未覆盖" not in row["value"]:
            problems.append(f"the value should say 本年未覆盖, got {row['value']!r}")
        if row["warn"] is not True:
            problems.append("the value should carry the warning class")
        if row["colour"] != "rgb(255, 181, 71)":
            problems.append(f"expected the warning colour, got {row['colour']}")
    else:
        if "本年未覆盖" in row["value"]:
            problems.append(f"the value should not claim 本年未覆盖, got {row['value']!r}")
        if row["warn"] is not False:
            problems.append("the value should not carry the warning class")
        if row["colour"] != "rgb(147, 163, 184)":
            problems.append(f"expected the plain colour, got {row['colour']}")

    print()
    if problems:
        print("VERDICT: FAIL")
        for x in problems:
            print(f"  - {x}")
        return 1
    print(f"VERDICT: PASS ({args.expect})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
