"""What is actually on the phone right now?

    python -u .shots/probe-phone-data.py
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")


def main():
    d = ui.Driver("b9026932", 9222, 2.75, 0)
    print(f"webview pid={d.ensure_forward()}")

    # A `|`-delimited string, not an array of objects: an array of objects is the shape this transport
    # still truncates on this phone (see docs/STATUS.md §1.1 #24), and a probe that returns `{}` would
    # say "no alarms" no matter what is on the device.
    rows = d.eval(
        "JSON.stringify((async()=>{const r=await window.Capacitor.Plugins.AlarmHub.listAlarms();"
        "return r.alarms.map(a => a.id + ' ' + a.hour + ':' + String(a.minute).padStart(2,'0') + ' '"
        "  + (a.label || '(无备注)') + ' enabled=' + a.enabled + ' ' + a.status).join(' || ');})())"
    )
    print("alarms via the bridge:")
    for a in str(rows).split(" || "):
        print(f"  {a}")

    groups = d.eval(
        "JSON.stringify((async()=>{const g=await window.Capacitor.Plugins.AlarmHub.listGroups();"
        "return g.groups.map(x => x.name + '#' + x.id).join(' || ');})())"
    )
    print(f"groups: {groups}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
