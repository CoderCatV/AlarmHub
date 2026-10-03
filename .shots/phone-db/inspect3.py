import subprocess, sqlite3
D="b9026932"; PKG="com.alarmhub.app"
def pull(r,l):
    p=subprocess.run(["adb","-s",D,"exec-out","run-as",PKG,"cat",r],capture_output=True)
    open(l,"wb").write(p.stdout)
for n in ("alarmhub.db","alarmhub.db-wal","alarmhub.db-shm"): pull(f"databases/{n}", n)
con=sqlite3.connect("alarmhub.db"); c=con.cursor()
print("=== 闹钟（数据是否还在）===")
for r in c.execute("select id,hour,minute,repeat_type,once_date,enabled,expired from alarms order by id"):
    print("  ", r)
print("=== 越界行 ===", c.execute("select count(*) from alarms where hour>23 or minute>59").fetchone()[0])
print("=== 分组 ===")
for r in c.execute("select id,name,permanently_disabled,pause_until from alarm_groups order by sort_order"): print("  ", r)
print("=== 设置 ===", tuple(c.execute("select time_format,permission_check_done,default_pause_days from settings").fetchone()))
