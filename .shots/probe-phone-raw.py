"""What does a `state()` read look like on the phone, verbatim?

    python -u .shots/probe-phone-raw.py
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

STATE_JS = (
    "JSON.stringify({"
    "rows: document.querySelectorAll('li.row').length,"
    "hero: !!document.querySelector('.hero'),"
    "page: document.querySelector('.bar__title')?.textContent.trim() ?? 'list'})"
)


def main():
    d = ui.Driver("b9026932", 9222, 2.75, 0)
    d.keep_screen_on()
    print(f"webview pid={d.ensure_forward()}")

    p = d._eval_once(STATE_JS)
    print(f"returncode={p.returncode}")
    print(f"stdout repr:\n{p.stdout!r}")
    print(f"stderr repr:\n{p.stderr!r}")

    value = d.eval(STATE_JS)
    print(f"eval -> {type(value).__name__}: {value!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
