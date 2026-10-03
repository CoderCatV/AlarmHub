import pathlib
# 1) The multi-select test's own toggle helper delegates to the one that checks its work.
p = pathlib.Path(".shots/t-multiselect.py")
t = p.read_text(encoding="utf-8")
old = '''def foreground_toggle(d):
    """Leave the app and come back, which is what makes the list re-read the database.

    Also a check in its own right: this is the fix for the reported \u300c\u54cd\u5b8c\u4e4b\u540e\u90a3\u6761\u8fd8\u5728\uff0c\u91cd\u542f\u624d\u6d88\u5931\u300d.
    """
    d.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(3)
    d.adb("shell", "am", "start", "-n", "com.alarmhub.app/.MainActivity")
    time.sleep(3)
'''
new = '''def foreground_toggle(d):
    """Leave the app and come back, which is what makes the list re-read the database.

    Also a check in its own right: this is the fix for the reported \u300c\u54cd\u5b8c\u4e4b\u540e\u90a3\u6761\u8fd8\u5728\uff0c\u91cd\u542f\u624d\u6d88\u5931\u300d.

    Delegates to the driver, which verifies the app actually reached the foreground and falls back to a
    cold start when the phone refused the return leg. Without that check the whole rest of the run
    reads a backgrounded app and reports `socket hang up`, which is what happened on the phone.
    """
    return d.foreground_toggle()
'''
assert old in t, "multiselect toggle not found"
p.write_text(t.replace(old, new), encoding="utf-8", newline="\n")
print("t-multiselect patched")

# 2) verify-phone.py: cold-start before the tests, so the first one starts from a known foreground.
p = pathlib.Path("tools/verify-phone.py")
t = p.read_text(encoding="utf-8")
old = '''    print("\\n== 5. tests ==")
    results = []'''
new = '''    print("\\n== 5. tests ==")
    # Bring the app up cold before anything reads it. After an install the process does not exist yet,
    # and on this ROM a plain `am start` can leave it running with no Activity in front \u2014 in which case
    # there is no WebView, no DevTools socket, and every test fails for a reason that has nothing to do
    # with the code under test.
    ui.Driver(device, CDP_PORT, 2.75, 0).cold_start()
    print(f"  webview sockets after cold start = "
          f"{ui.Driver(device, CDP_PORT, 2.75, 0).webview_sockets()}")
    results = []'''
assert old in t, "verify-phone section not found"
p.write_text(t.replace(old, new), encoding="utf-8", newline="\n")
print("verify-phone patched")
