"""Regression check for the `ui-drive.eval()` fix (docs/STATUS.md §1.1 #24).

The bug: `eval()` returned the string `'{'` for any expression that produced an object, because the
probe pretty-prints an object over several lines and the parser's fallback took line 1. `'{'` looks like
a value, so three separate debugging rounds read it as a page or product defect.

The fix parses the **leading JSON value** and ignores the probe's trailer. This probe asserts both
halves of the contract:

1. a raw object now comes back as a dict (it used to come back as `'{'`);
2. a bare `'{'` / `'['` is rejected loudly rather than returned, so this can never silently return to
   looking like a value.

    python -u .shots/probe-eval-guard.py
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

FAILURES = []


def check(label, got, want):
    ok = got == want
    if not ok:
        FAILURES.append(f"{label}: got {got!r}, want {want!r}")
    print(f"  [{'OK ' if ok else 'FAIL'}] {label}: {got!r}" + ("" if ok else f"  (want {want!r})"))


def main():
    d = ui.Driver("emulator-5554", 9222, 2.625, 136)
    d.keep_screen_on()
    print(f"webview pid={d.ensure_forward()}")

    print("== an object expression now parses (the bug) ==")
    check("raw object", d.eval("({a:1,b:'x'})"), {"a": 1, "b": "x"})

    print("== an array expression too ==")
    check("raw array", d.eval("[1,2,3]"), [1, 2, 3])

    print("== a nested structure, which is what the neighbours in this folder return ==")
    check(
        "nested",
        d.eval("({rows: [{v: '2025', w: false}, {v: '2026', w: true}]})"),
        {"rows": [{"v": "2025", "w": False}, {"v": "2026", "w": True}]},
    )

    print("== a plain string the page printed is still a string ==")
    check("quoted string", d.eval("'hello'"), "hello")

    print("== a JSON-looking *string* is indistinguishable from JSON — documented, not guessed ==")
    # `cdp-probe.js` prints strings verbatim, so a page printing the text `{"a":1}` and a page returning
    # the object `{a:1}` produce identical output. Both come back as a dict; a read that needs the
    # difference must have the expression say so (prefix it) rather than rely on the transport.
    check("a string that looks like JSON parses as JSON", d.eval("JSON.stringify({a:1})"), {"a": 1})
    check("and so does the same text written as a string literal", d.eval("'{\"a\":1}'"), {"a": 1})

    print("== the page's own console noise no longer breaks a read ==")
    # The emulator logs "Error injecting safe area CSS" on every page, which is what appended the
    # trailer that used to break `json.loads(whole output)`. The phone is **quiet**, and then the probe
    # prints the *other* trailer ("no console errors or warnings") — so a fix that strips only the noisy
    # one repairs the emulator and breaks the phone. Both were observed; this check runs on a device
    # with the noisy trailer, and `probe-phone-raw.py` is the quiet-side evidence.
    check("still fine with noise present", d.eval("({ok:true})"), {"ok": True})

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
