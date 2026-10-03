import subprocess, sqlite3, sys
D = "b9026932"
PKG = "com.alarmhub.app"

def pull(remote, local):
    # exec-out gives raw bytes; PowerShell's ">" would re-encode them as text (that is what turned
    # the first attempt into "file is not a database").
    p = subprocess.run(["adb", "-s", D, "exec-out", "run-as", PKG, "cat", remote],
                       capture_output=True)
    if p.returncode != 0:
        print("  pull failed:", p.stderr.decode(errors="replace").strip()[:120])
    open(local, "wb").write(p.stdout)
    print(f"  {remote} -> {local}  {len(p.stdout)} bytes")

for name in ("alarmhub.db", "alarmhub.db-wal", "alarmhub.db-shm"):
    pull(f"databases/{name}", name)

con = sqlite3.connect("alarmhub.db")
cur = con.cursor()
print("\n=== alarms ===")
for r in cur.execute("select id,hour,minute,repeat_type,once_date,label,enabled,delete_after_ring,expired from alarms order by id"):
    print("  ", r)
print("=== 越界行数 (hour>23 或 minute>59) ===")
print("  ", cur.execute("select count(*) from alarms where hour>23 or hour<0 or minute>59").fetchone()[0])
print("=== groups ===")
for r in cur.execute("select id,name,pause_until,paused_at,permanently_disabled from alarm_groups order by sort_order"):
    print("  ", r)
print("=== settings ===")
print("  ", tuple(cur.execute("select time_format,theme,permission_check_done,default_pause_days from settings").fetchone() or ()))
