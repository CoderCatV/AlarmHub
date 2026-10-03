import subprocess, sqlite3
D="b9021932"; D="b9026932"; PKG="com.alarmhub.app"
def pull(r,l):
    p=subprocess.run(["adb","-s",D,"exec-out","run-as",PKG,"cat",r],capture_output=True); open(l,"wb").write(p.stdout)
for n in ("alarmhub.db","alarmhub.db-wal","alarmhub.db-shm"): pull(f"databases/{n}", n)
con=sqlite3.connect("alarmhub.db"); c=con.cursor()
print(" id | time  | type    | autoStop | snooze | snoozeMax | enabled | lastTrigger")
for r in c.execute("select id,hour,minute,repeat_type,auto_stop_minutes,snooze_count,snooze_max_count,enabled,last_trigger_at from alarms order by id"):
    print(f"  {r[0]:>2} | {r[1]:02d}:{r[2]:02d} | {r[3]:<7} | {r[4]:>8} | {r[5]:>6} | {r[6]:>9} | {r[7]:>7} | {r[8]}")
print("settings.volumeKeyAction =", c.execute("select volume_key_action from settings").fetchone()[0])
print("settings.defaultAutoStop =", c.execute("select default_auto_stop_minutes from settings").fetchone()[0])
