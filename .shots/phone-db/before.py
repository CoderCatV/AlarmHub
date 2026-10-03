import subprocess, sqlite3
D="b9026932"; PKG="com.alarmhub.app"
def pull(r,l):
    p=subprocess.run(["adb","-s",D,"exec-out","run-as",PKG,"cat",r],capture_output=True); open(l,"wb").write(p.stdout)
for n in ("alarmhub.db","alarmhub.db-wal","alarmhub.db-shm"): pull(f"databases/{n}", n)
c=sqlite3.connect("alarmhub.db").cursor()
print("  alarms =", c.execute("select count(*) from alarms").fetchone()[0])
for r in c.execute("select id,hour,minute,repeat_type,label,enabled from alarms order by id"): print("   ", r)
