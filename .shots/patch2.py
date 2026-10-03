import pathlib
p = pathlib.Path(".shots/t-editor.py")
t = p.read_text(encoding="utf-8")

# (1) Widen the travel assertion: with `scroll-snap-stop: always` a gesture passes *at most* one snap
# point per ... in practice the injected drag carries momentum and lands 1-4 rows.
t = t.replace(
    'check("and moved by 1-3 hours, in the direction the wheel was dragged", 3600 - 90 <= delta <= 3 * 3600 + 90, True)',
    'check("and moved by 1-4 hours, in the direction the wheel was dragged", 3600 - 90 <= delta <= 4 * 3600 + 90, True)')

# (2) After deleting the leftover, the page's own group list is stale ("the view is a cache"). Make the
# page re-read before the test creates anything, or 「新建」 finds the deleted name in its stale cache
# and silently takes the already-exists branch \u2014 which is what happened, and is why the count never moved.
old = """        time.sleep(1)
        before_groups = ui.db_scalar(device, "select count(*) from alarm_groups")
"""
new = """        time.sleep(1)
        before_groups = ui.db_scalar(device, "select count(*) from alarm_groups")
        # The page still believes the deleted group exists. Leaving and re-entering the app is what
        # makes it re-read the database (the same `visibilitychange` refresh that fixed the
        # \u300c\u54cd\u5b8c\u4e4b\u540e\u90a3\u6761\u8fd8\u5728\u300d report), and without it the create below silently takes its
        # "that name already exists, just select it" branch.
        d.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(3)
        d.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
        time.sleep(3)
"""
assert old in t
t = t.replace(old, new, 1)
p.write_text(t, encoding="utf-8", newline="\n")
print("patched")
