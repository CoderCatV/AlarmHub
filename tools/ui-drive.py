#!/usr/bin/env python3
"""Drive the app's WebView UI: read the DOM over CDP, tap/swipe it through `adb input`.

WHY THIS EXISTS
---------------
Everything before this drove the UI from PowerShell one-liners, and that failed three separate
times, each time costing a debugging round:

* `Get-Content`/`Set-Content` round-tripped a Kotlin file through the GBK console codepage and
  destroyed every Chinese string in it (see docs/STATUS.md §1.2 #7).
* `Select-String` filters silently truncated JSON to its first character, so a tap used a null
  coordinate and a whole test run proved nothing.
* `Tee-Object` in a background job wrote nothing at all.

Also, a device tap needs a coordinate in *device* pixels while the DOM reports CSS pixels, and
that transform (multiply by the device pixel ratio, add the WebView's top offset) was being
retyped every time. Getting it wrong is invisible: the tap just lands somewhere else.

Coordinate model, measured on the dsh_pixel AVD at 1080x2400 with the WebView at [0,136]:
    device_x = css_x * dpr
    device_y = css_y * dpr + webview_top
`--dpr` and `--top` override the defaults. On the real Xiaomi 14 the dpr is 2.75 and the top
offset differs, so pass them explicitly rather than assuming.

USAGE
-----
    python tools/ui-drive.py state
    python tools/ui-drive.py eval "document.querySelectorAll('li.row').length"
    python tools/ui-drive.py rects                    # row centres, in CSS px
    python tools/ui-drive.py tap-row 2
    python tools/ui-drive.py longpress-row 0
    python tools/ui-drive.py swipe 540 1400 540 700 220     # device px, for scrolling
    python tools/ui-drive.py tap 462 844                    # device px, verbatim

Every command prints one JSON object, so callers parse it instead of pattern-matching text.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / "tools" / "cdp-probe.js"


def run(cmd: list[str], binary: bool = False, **kw) -> subprocess.CompletedProcess:
    """Run a command. Text mode by default; `binary=True` for pulling a database file."""
    if binary:
        return subprocess.run(cmd, capture_output=True, **kw)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


def db_scalar(device: str, sql: str, pkg: str = "com.alarmhub.app"):
    """Run one scalar SQL query against the app's database on `device`.

    Two routes, because the two test devices differ:

    * `run-as <pkg> sqlite3` — works on the emulator.
    * pull the file with `exec-out run-as <pkg> cat` and query it locally — the only route on the
      reporting phone, where MIUI answers `run-as: exec failed for sqlite3: Permission denied`.

    The pull has to happen in **binary** mode. Writing it through a PowerShell text pipeline corrupts
    the file (`file is not a database`), which cost a round earlier in this project.
    """
    try:
        out = run(["adb", "-s", device, "shell", f"run-as {pkg} sqlite3 databases/alarmhub.db '{sql};'"])
        if out.returncode == 0 and out.stdout.strip():
            return int(out.stdout.strip())
    except Exception:
        pass

    import sqlite3
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        for suffix in ("", "-wal", "-shm"):
            p = run(
                ["adb", "-s", device, "exec-out", "run-as", pkg, "cat", f"databases/alarmhub.db{suffix}"],
                binary=True,
            )
            blob = p.stdout or b""
            if blob:
                Path(tmp, f"db{suffix}").write_bytes(blob)
        if not Path(tmp, "db").exists():
            raise RuntimeError(f"could not read the database on {device} by either route")
        con = sqlite3.connect(str(Path(tmp, "db")))
        try:
            return con.execute(sql).fetchone()[0]
        finally:
            con.close()


class Driver:
    def __init__(self, device: str, port: int, dpr: float, top: int) -> None:
        self.device = device
        self.port = port
        self.dpr = dpr
        self.top = top
        self._base_port = port
        self._rebuilds = 0
        self._forwarded_pid: str | None = None

    def adb(self, *args: str) -> str:
        p = run(["adb", "-s", self.device, *args])
        if p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {p.stderr.strip()}")
        return p.stdout

    def pidof(self) -> str:
        """The app's pid, or "" when it is not running.

        Its own method because treating a non-zero exit as an error is wrong here: `pidof` **exits 1
        by design** when nothing matches, and wrapping every caller in a try/except is how an
        intentional answer gets mistaken for a broken command.
        """
        p = run(["adb", "-s", self.device, "shell", "pidof com.alarmhub.app"])
        return p.stdout.strip().split()[0] if p.stdout.strip() else ""

    def wait_for_pid(self, seconds: int = 20) -> str:
        """Start the app if needed and return its pid, or "" if it never came up."""
        if self.pidof():
            return self.pidof()
        run(["adb", "-s", self.device, "shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity"])
        for _ in range(seconds):
            time.sleep(1)
            if self.pidof():
                return self.pidof()
        return ""

    def webview_sockets(self) -> int:
        """How many WebView DevTools sockets exist on the device.

        The decisive check for "is the app's UI actually up". A process can be running with its
        Activity never brought to the foreground — on the phone MIUI refuses some return-to-app
        launches — and then the WebView is never created, so there is no socket to forward to and
        every read fails with a confusing `socket hang up`.
        """
        p = run(["adb", "-s", self.device, "shell", "cat /proc/net/unix | grep -c webview_devtools"])
        try:
            return int(p.stdout.strip() or 0)
        except ValueError:
            return 0

    def cold_start(self) -> None:
        """Force-stop and start the app, which is the launch MIUI does not refuse."""
        self.adb("shell", "am", "force-stop", "com.alarmhub.app")
        time.sleep(1)
        self.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
        time.sleep(6)

    def foreground_toggle(self) -> str:
        """Leave the app and come back, so the page re-reads the database.

        Returns which route was used. The settings round trip is preferred because it is the path that
        exercises the `visibilitychange` refresh — the fix for 「响完之后那条还在，重启才消失」 — but on the
        phone MIUI sometimes lets the process start without ever resuming its Activity, which leaves no
        WebView at all. That case is detected and replaced with a cold start rather than being allowed
        to poison every later read.
        """
        self.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(3)
        self.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
        time.sleep(3)
        if self.webview_sockets() == 0:
            self.cold_start()
            return "cold-start"
        return "foreground-toggle"

    def probe_works(self) -> bool:
        """Cheap liveness check for the current forward, with no adb traffic."""
        env = dict(os.environ, CDP_PORT=str(self.port))
        p = run(["node", str(PROBE), "--eval", "'ok'"], env=env, cwd=str(ROOT))
        return "ok" in p.stdout and not self._transport_died(p.stdout + p.stderr)

    def ensure_forward(self) -> str:
        """Point `tcp:<port>` at the app's *current* WebView socket, and prove it answers.

        **The fast path matters.** Re-issuing `adb forward --remove` + `adb forward` for a forward that
        is already working used to produce `socket hang up`, so a working forward is left alone and only
        a failing probe triggers a rebuild.

        The socket name contains the app's pid, so it does have to be rebuilt when the process restarts
        — on the phone that happens on its own (MIUI recycled it once while the screen was off, which
        killed a test half way through).
        """
        if self.probe_works():
            return self._forwarded_pid or "already"

        pid = self.wait_for_pid()
        if not pid:
            raise RuntimeError(f"could not start com.alarmhub.app on {self.device}")
        pid = pid.split()[0]

        # **Always move to a fresh port, including on the first rebuild.**
        #
        # Re-binding the *same* port after `adb forward --remove` reliably produced `socket hang up` on
        # the phone: a fresh TCP connect that the DevTools endpoint accepted and immediately dropped.
        # Neither a settle delay nor retries helped, and the same two commands typed by hand worked —
        # the difference being that by hand the port had no previous forward to tear down. A port is
        # free, so this never reuses one.
        self._rebuilds += 1
        self.port = self._base_port + self._rebuilds
        # Bind, then **prove it works before returning**.
        #
        # Resolving the pid once is not enough right after an install: `adb install` kills the app, and
        # `pidof` can still answer with the dying process's pid for a moment, so the forward binds to a
        # socket that is about to disappear — which shows up as `socket hang up` on the next read. The
        # two tests that ran immediately after the install failed this way while the third, minutes
        # later, passed. Retrying here means a caller never gets a forward that does not work.
        errors = []
        for attempt in range(4):
            pid = self.pidof()
            if not pid:
                self.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
                time.sleep(3)
                continue

            # The socket name is `webview_devtools_remote_<pid>` — **underscore before the pid**.
            #
            # This was a colon for several rounds, and it cost hours: naming an abstract socket that
            # does not exist makes `adb` accept the TCP connection and then drop it, which surfaces as
            # `FAIL: socket hang up`. Fresh ports, settle delays and retry loops were all treating a
            # symptom; the forward was never pointing at the WebView. A hand-typed
            # `adb forward tcp:9223 localabstract:webview_devtools_remote_$pid` worked because the
            # shell built it with the right separator.
            self._rebuilds += 1
            self.port = self._base_port + self._rebuilds
            run(["adb", "-s", self.device, "forward", "--remove", f"tcp:{self.port}"])
            out = run(
                ["adb", "-s", self.device, "forward", f"tcp:{self.port}",
                 f"localabstract:webview_devtools_remote_{pid}"]
            )
            if out.returncode != 0:
                errors.append(f"adb forward failed: {out.stderr.strip()}")
                continue
            self._forwarded_pid = pid
            if self.probe_works():
                return pid
            errors.append(f"attempt {attempt + 1}: bound to pid {pid} but the socket did not answer")
            time.sleep(1.5)

        raise RuntimeError("could not establish a working DevTools forward:\n  " + "\n  ".join(errors))

    def keep_screen_on(self) -> None:
        """Stop the phone from sleeping for the duration of a test.

        Not a nicety: with the screen off, MIUI suspends the WebView and the DevTools socket goes
        away, which surfaces as a test that dies part way through for no visible reason.
        """
        run(["adb", "-s", self.device, "shell", "svc", "power", "stayon", "usb"])

    def _eval_once(self, js: str):
        env = dict(os.environ, CDP_PORT=str(self.port))
        return run(["node", str(PROBE), "--eval", js], env=env, cwd=str(ROOT))

    @staticmethod
    def _transport_died(text: str) -> bool:
        """Whether a probe failure means "the socket moved", not "the page said no".

        `socket hang up` and `no page target` both showed up on the phone when the WebView was
        recycled mid-run, and both are recoverable by re-resolving the pid.
        """
        return any(
            s in text
            for s in ("ECONNREFUSED", "socket hang up", "websocket failed", "no page target", "ECONNRESET")
        )

    def eval(self, js: str, attempts: int = 4):
        """Evaluate JS in the page and return the parsed JSON value.

        Retries with backoff. The phone's WebView socket is genuinely flaky for a second or two after
        anything that changes the window — `svc power stayon usb`, a configuration change, or the app
        being recycled — and a single retry was not enough: a run died on its very first read with
        `socket hang up` twice in a row, while the same read succeeded by hand a minute later.
        """
        last = ""
        for i in range(attempts):
            p = self._eval_once(js)
            text = p.stdout + p.stderr
            out = p.stdout.strip()
            if self._transport_died(text) or not out:
                last = text.strip()
                self.ensure_forward()
                time.sleep(2 + i)
                continue

            # The probe prints the value, then a blank line, then **one of two trailers**: either
            # "--- page console problems ---" plus whatever the app logged, or "no console errors or
            # warnings" when the page was quiet. Cut whichever is present before parsing.
            #
            # Both have to be handled, and getting that wrong is how this was first "fixed": stripping
            # only the noisy trailer fixed the emulator (which always logs a safe-area warning) and
            # broke the phone (which is quiet, so every read came back as raw text and every test died
            # with `string indices must be integers`). See docs/STATUS.md §1.1 #24.
            #
            # Falling back to `splitlines()[0]` used to be the whole story, and it was wrong in a way
            # that poisoned three debugging rounds: the probe pretty-prints an object over several lines,
            # so `json.loads(whole output)` failed on the trailer, the fallback took line 1, and line 1
            # of a pretty-printed object is `{`. Callers got the string `'{'` — a plausible-looking
            # value, so it read as "the page returned {" and sent me looking for a page or product bug.
            # It was the tool, and this is the same class of mistake as the `adb forward` colon in
            # docs/STATUS.md §1.1 #22.
            payload = re.split(
                r"^(?:--- page console problems ---|no console errors or warnings)\s*$",
                out,
                maxsplit=1,
                flags=re.MULTILINE,
            )[0].strip()

            # `json.loads` decides whether this is JSON or a string the page printed. That is
            # **ambiguous by construction** and worth knowing: `cdp-probe.js` prints a string result
            # verbatim (`console.log(value)`), so a page that prints the *text* `{"a":1}` and a page that
            # returns the *object* `{a:1}` produce byte-identical output, and both come back as a dict.
            # It is documented rather than guessed at; if a read ever needs the difference, have the
            # expression wrap its answer in something unmistakable (a prefix) instead of relying on the
            # transport to preserve a type it cannot see.
            try:
                value = json.loads(payload)
            except json.JSONDecodeError:
                value = payload

            if isinstance(value, dict) and "__exception" in value:
                raise RuntimeError(f"the page threw while evaluating: {value['__exception']}\n  for: {js}")
            # A bare `{` or `[` is never a real value: it means the multi-line JSON did not survive the
            # trip. Say so, instead of handing back something that looks like a value.
            if value in ("{", "["):
                raise RuntimeError(
                    f"the probe returned a truncated object/array ({value!r}) for: {js}\n"
                    "  Wrap the expression in JSON.stringify(...) — see docs/STATUS.md §1.1 #24."
                )
            return value
        raise RuntimeError(f"cdp-probe kept failing for: {js}\nlast output: {last}")

    def eval_list(self, js: str, attempts: int = 6, pause: float = 0.4):
        """Evaluate an expression that must yield a **list**, retrying until it does.

        `eval` already retries transport failures and page exceptions. What it cannot know is that a
        *successful* read came back with the wrong shape because the app was busy: a bridge call issued
        while the page is mid-write resolves with `{}` often enough to break fixtures — a test's
        "delete my leftover rows" read returned `{}`, so nothing was deleted, the next run counted four
        rows instead of two, and the failure cascade read like a dozen product defects (found while
        cleaning up fixtures for the FR-7.11 menu test, 2026-10-03).

        Callers that need a list use this instead of `eval`, so the retry lives in one place rather than
        being re-derived (or forgotten) per test.
        """
        last = None
        for i in range(attempts):
            last = self.eval(js)
            if isinstance(last, list):
                return last
            time.sleep(pause * (i + 1))
        raise RuntimeError(
            f"expected a list after {attempts} attempts, got {type(last).__name__}: {last!r}\n  for: {js}"
        )

    def to_device(self, css_x: float, css_y: float) -> tuple[int, int]:
        return round(css_x * self.dpr), round(css_y * self.dpr) + self.top

    def rows(self) -> list[dict]:
        return self.eval(
            "JSON.stringify([...document.querySelectorAll('li.row .row__main')].map((r,i)=>{"
            "const b=r.getBoundingClientRect();"
            "const row=r.closest('li.row');"
            "return {i, x:b.x+b.width/2, y:b.y+Math.min(28,b.height/2),"
            "ticked: !!row.querySelector('.row__tick--on'),"
            "time: row.querySelector('.row__time')?.textContent.trim() ?? ''};}))"
        )

    def state(self) -> dict:
        return self.eval(
            "JSON.stringify({"
            "rows: document.querySelectorAll('li.row').length,"
            "ticked: document.querySelectorAll('.row__tick--on').length,"
            # Identity of each ticked row, not just how many: when a count is unexpected, the useful
            # question is *which* rows were picked, and a bare number cannot answer it.
            "tickedRows: [...document.querySelectorAll('li.row')]"
            "  .filter(r => r.querySelector('.row__tick--on'))"
            "  .map(r => (r.querySelector('.row__time')?.textContent.trim() ?? '?') + ' '"
            "             + (r.querySelector('.row__label')?.textContent.trim() ?? '')),"
            "selMode: !!document.querySelector('.selbar__title'),"
            "selTitle: document.querySelector('.selbar__title')?.textContent.trim() ?? null,"
            "selAction: document.querySelector('.selactions__delete')?.textContent.trim() ?? null,"
            "selDisabled: document.querySelector('.selactions__delete')?.disabled ?? null,"
            "fab: !!document.querySelector('.fab'),"
            "hero: !!document.querySelector('.hero'),"
            "scrim: !!document.querySelector('.scrim'),"
            "page: document.querySelector('.bar__title')?.textContent.trim() ?? 'list'})"
        )

    def eval_touch(self, css_x: float, css_y: float, hold_ms: int = 60, dx: float = 0, dy: float = 0) -> dict:
        """Inject a real touch through the DevTools socket.

        The only transport that works on HyperOS, where the shell has no `INJECT_EVENTS`. It also
        needs no pixel conversion: CDP coordinates are page coordinates.
        """
        env = dict(os.environ, CDP_PORT=str(self.port))
        cmd = [
            "node", str(PROBE), "--touch",
            str(round(css_x)), str(round(css_y)), str(int(hold_ms)),
            str(round(dx)), str(round(dy)),
        ]
        for i in range(4):
            p = run(cmd, env=env, cwd=str(ROOT))
            if self._transport_died(p.stdout + p.stderr) or not (p.stdout + p.stderr).strip():
                # The app was restarted, or the socket is briefly unavailable: re-resolve and retry.
                self.ensure_forward()
                time.sleep(2 + i)
                continue
            if p.returncode not in (0, 2):  # 2 = page logged a console error; the action still happened
                raise RuntimeError(f"touch injection failed: {p.stderr.strip() or p.stdout.strip()}")
            return {"touched": [round(css_x), round(css_y)], "holdMs": hold_ms, "drag": [dx, dy]}
        raise RuntimeError(f"touch injection kept failing at ({css_x}, {css_y})")

    def tap_css(self, css_x: float, css_y: float, hold_ms: int = 50, drag: tuple[float, float] = (0, 0)) -> dict:
        x, y = self.to_device(css_x, css_y)
        if drag != (0, 0) or hold_ms > 120:
            # A 0-distance swipe with a duration is the only way `input` produces a *held* press;
            # `input tap` is always a quick down/up, which no long-press handler can see.
            ex, ey = self.to_device(css_x + drag[0], css_y + drag[1])
            self.adb("shell", "input", "swipe", str(x), str(y), str(ex), str(ey), str(max(hold_ms, 120)))
        else:
            self.adb("shell", "input", "tap", str(x), str(y))
        return {"tapped": [x, y], "holdMs": hold_ms, "drag": list(drag)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", default="emulator-5554")
    ap.add_argument("--port", type=int, default=9222)
    ap.add_argument("--dpr", type=float, default=2.625)
    ap.add_argument("--top", type=int, default=136)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("state")
    sub.add_parser("rects")

    p = sub.add_parser("eval")
    p.add_argument("js")

    p = sub.add_parser("tap-row")
    p.add_argument("index", type=int)

    p = sub.add_parser("longpress-row")
    p.add_argument("index", type=int)
    p.add_argument("--ms", type=int, default=700)

    p = sub.add_parser("tap")
    p.add_argument("x", type=int)
    p.add_argument("y", type=int)

    p = sub.add_parser("swipe")
    p.add_argument("x1", type=int)
    p.add_argument("y1", type=int)
    p.add_argument("x2", type=int)
    p.add_argument("y2", type=int)
    p.add_argument("ms", type=int, default=250)

    p = sub.add_parser("click")
    p.add_argument("selector")

    args = ap.parse_args()
    d = Driver(args.device, args.port, args.dpr, args.top)

    if args.cmd == "state":
        print(json.dumps(d.state(), ensure_ascii=False))
    elif args.cmd == "rects":
        print(json.dumps(d.rows(), ensure_ascii=False))
    elif args.cmd == "eval":
        print(json.dumps(d.eval(args.js), ensure_ascii=False))
    elif args.cmd == "tap-row":
        rows = d.rows()
        if args.index >= len(rows):
            raise SystemExit(f"row {args.index} does not exist ({len(rows)} rows)")
        print(json.dumps({**d.tap_css(rows[args.index]["x"], rows[args.index]["y"]), "row": args.index}, ensure_ascii=False))
    elif args.cmd == "longpress-row":
        rows = d.rows()
        if args.index >= len(rows):
            raise SystemExit(f"row {args.index} does not exist ({len(rows)} rows)")
        print(
            json.dumps(
                {**d.tap_css(rows[args.index]["x"], rows[args.index]["y"], args.ms), "row": args.index, "hold_ms": args.ms},
                ensure_ascii=False,
            )
        )
    elif args.cmd == "tap":
        d.adb("shell", "input", "tap", str(args.x), str(args.y))
        print(json.dumps({"tapped": [args.x, args.y]}))
    elif args.cmd == "swipe":
        d.adb("shell", "input", "swipe", str(args.x1), str(args.y1), str(args.x2), str(args.y2), str(args.ms))
        print(json.dumps({"swiped": [args.x1, args.y1, args.x2, args.y2], "ms": args.ms}))
    elif args.cmd == "click":
        # A DOM click: fine for buttons, but it cannot exercise a long press or a real gesture.
        print(json.dumps(d.eval(f"(()=>{{const e=document.querySelector({json.dumps(args.selector)});"
                                f"if(!e) return 'not found'; e.click(); return 'clicked';}})()"), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
