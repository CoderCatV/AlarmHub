import subprocess, sqlite3
D="b9026932"; PKG="com.alarmhub.app"
def pull(remote, local):
    p = subprocess.run(["adb","-s",D,"exec-out","run-as",PKG,"cat",remote], capture_output=True)
    open(local,"wb").write(p.stdout)
for n in ("alarmhub.db","alarmhub.db-wal","alarmhub.db-shm"): pull(f"databases/{n}", n)
con = sqlite3.connect("alarmhub.db"); cur = con.cursor()
print("=== 闹钟（按 id）===")
for r in cur.execute("select id,hour,minute,repeat_type,once_date,label,enabled,expired,last_trigger_at from alarms order by id"):
    print("  ", r)
print("=== 越界行 ===", cur.execute("select count(*) from alarms where hour>23 or minute>59").fetchone()[0])
print("=== 已过期（响过且没删）===")
for r in cur.execute("select id,hour,minute,once_date,expired from alarms where expired=1"): print("  ", r)
