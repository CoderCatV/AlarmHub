"""The phone's true database state, read correctly (main .db **plus** -wal and -shm).

Why this file exists: the app runs Room in **WAL mode**, so recent commits live in `alarmhub.db-wal`
until a checkpoint moves them into the main file. A pull of `alarmhub.db` **alone** is therefore a stale
snapshot — which is exactly what a throwaway version of this probe did, and it reported two long-deleted
test rows as still present. (`tools/ui-drive.py:db_scalar()` is *not* affected: its pull path already
pulls all three files, which is worth checking before accusing it.)

Two pull rules, both learned the hard way in this project:
  * **binary mode** (`subprocess` without text=True) — a text pipeline corrupts the file;
  * **all three files** (`.db`, `-wal`, `-shm`) into the same directory, so SQLite can recover the
    committed state on open.

    python -u .shots/probe-phone-db.py            # read only
    python -u .shots/probe-phone-db.py --clean     # delete rows labelled 自测-* only
"""

import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from importlib import import_module  # noqa: E402

ui = import_module("ui-drive")

PKG = "com.alarmhub.app"
DEVICE = "b9026932"
TEST_LABELS = ("自测-可删", "自测-批改", "自测-通知")


def pull_all():
    """Pulls .db, -wal and -shm into `dest` (binary). Returns False if the main file is missing."""
    files = {}
    for suffix in ("", "-wal", "-shm"):
        p = subprocess.run(
            ["adb", "-s", DEVICE, "exec-out", f"run-as {PKG} cat databases/alarmhub.db{suffix}"],
            capture_output=True,
        )
        files[suffix] = p.stdout
    return files


def main():
    clean = "--clean" in sys.argv

    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        files = pull_all()
        if not files[""]:
            print("could not pull the database")
            return 1
        for suffix, blob in files.items():
            (tmpdir / f"alarmhub.db{suffix}").write_bytes(blob)
        print(f"pulled: " + ", ".join(f"db{s}={len(b)}B" for s, b in files.items() if b))

        con = sqlite3.connect(tmpdir / "alarmhub.db")
        # Force SQLite to actually apply the WAL on open (read-only connections can otherwise ignore it).
        con.execute("pragma journal_mode=wal")
        rows = con.execute(
            "select id, hour, minute, label, enabled from alarms order by id"
        ).fetchall()
        print("alarms on the phone (WAL applied):")
        for r in rows:
            kind = "TEST FIXTURE" if r[3] in TEST_LABELS else "NOT A TEST ROW"
            print(f"  id={r[0]} {r[1]}:{r[2]:02d} label={r[3]!r} enabled={r[4]}  [{kind}]")
        groups = con.execute("select count(*) from alarm_groups").fetchone()[0]
        snooze = con.execute("select snooze_enabled from settings").fetchone()[0]
        print(f"groups={groups}  settings.snooze_enabled={snooze}")
        con.close()

    if clean:
        ids = [r[0] for r in rows if r[3] in TEST_LABELS]
        if not ids:
            print("nothing to clean")
            return 0
        d = ui.Driver(DEVICE, 9222, 2.75, 0)
        d.ensure_forward()
        out = d.eval(
            "(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteAlarms("
            f"{{ids: {ids}}})))()"
        )
        print(f"delete {ids} -> {out}")
        # Verify through the app itself, not by re-pulling the file: the app's own view is what matters,
        # and it cannot be stale.
        left = d.eval(
            "(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listAlarms()).alarms"
            ".map(a => a.id + ':' + a.label).join(' | ')))()"
        )
        print(f"the app now lists: {left!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
