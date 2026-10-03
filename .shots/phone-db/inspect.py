import sqlite3, textwrap
con = sqlite3.connect("alarmhub.db")
cur = con.cursor()
print("=== alarms ===")
for r in cur.execute("select id,group_id,hour,minute,repeat_type,once_date,enabled,delete_after_ring,expired,last_trigger_at from alarms order by id"):
    print(r)
print("=== 越界行数 (hour>23 or minute>59) ===")
print(cur.execute("select count(*) from alarms where hour>23 or hour<0 or minute>59").fetchone())
print("=== groups ===")
for r in cur.execute("select id,name,pause_until,paused_at,permanently_disabled,sort_order from alarm_groups order by sort_order"):
    print(r)
print("=== settings ===")
for r in cur.execute("select * from settings"):
    print(r)
