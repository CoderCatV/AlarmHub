import pathlib
p = pathlib.Path(".shots/t-editor.py")
t = p.read_text(encoding="utf-8")
old = """    print("== open the editor ==")"""
new = """    # Unconditionally make the page re-read the database first. The store keeps `state.groups` as a
    # cache, and this test deletes its leftovers through the **bridge**, which bypasses the store's own
    # refresh \u2014 so the page kept a group that no longer existed and the create below silently took the
    # "that name already exists, just select it" branch against a deleted id. Leaving and re-entering
    # the app is what triggers the re-read.
    d.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(3)
    d.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
    time.sleep(3)

    print("== open the editor ==")"""
assert old in t
t = t.replace(old, new, 1)
# Drop the now-redundant conditional refresh inside the cleanup branch.
t = t.replace("""        # The page still believes the deleted group exists. Leaving and re-entering the app is what
        # makes it re-read the database (the same `visibilitychange` refresh that fixed the
        # \u300c\u54cd\u5b8c\u4e4b\u540e\u90a3\u6761\u8fd8\u5728\u300d report), and without it the create below silently takes its
        # "that name already exists, just select it" branch.
        d.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(3)
        d.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
        time.sleep(3)
""", "")
p.write_text(t, encoding="utf-8", newline="\n")
print("patched")
