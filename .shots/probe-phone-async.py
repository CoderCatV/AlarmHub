"""Raw capture of the exact async expression, on the phone, written to a file.

    python -u .shots/probe-phone-async.py
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

EXPRS = [
    "JSON.stringify((async()=>1)())",
    "(async()=>JSON.stringify({a:1}))()",
    "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.listAlarms()))()",
    "(async()=>{const r=await window.Capacitor.Plugins.AlarmHub.listAlarms(); return JSON.stringify({n: r.alarms.length})})()",
]


def main():
    d = ui.Driver("b9026932", 9222, 2.75, 0)
    print(f"webview pid={d.ensure_forward()}")

    lines = []
    for expr in EXPRS:
        p = subprocess.run(
            ["node", str(ROOT / "tools" / "cdp-probe.js"), "--eval", expr],
            capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=str(ROOT),
        )
        lines.append(f"=== {expr}\nreturncode={p.returncode}\nstdout={p.stdout!r}\nstderr={p.stderr!r}\n")

    out = Path(ROOT / ".shots" / "probe-phone-async.txt")
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
