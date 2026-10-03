import pathlib
p = pathlib.Path("tools/verify-phone.py")
t = p.read_text(encoding="utf-8")

old = '''# (script, label). Each is expected to print "ALL CHECKS PASSED" and exit 0.
TESTS = [
    (".shots/t-multiselect.py", "\u957f\u6309\u6279\u91cf\u52fe\u9009\u5220\u9664"),
    (".shots/t-wheel.py", "\u6eda\u8f6e\u5faa\u73af\u6eda\u52a8"),
    (".shots/t-editor.py", "\u7f16\u8f91\u9875\u9884\u89c8\u4e0e\u65b0\u5efa\u5206\u7ec4"),
]'''
new = '''# (script, label, extra args). Each is expected to print "ALL CHECKS PASSED" and exit 0.
#
# The extra args exist because the scripts grew different CLIs, and the first version of this file
# assumed they were the same: t-multiselect defaults to the `adb input` transport, which **HyperOS
# refuses** (`INJECT_EVENTS`), so on the phone it has to be told to use CDP touch. Spelling that out
# per test is honest; a caller that has to guess is how a transport detail turns into a red test.
TESTS = [
    (".shots/t-multiselect.py", "\u957f\u6309\u6279\u91cf\u52fe\u9009\u5220\u9664", ["--via", "cdp"]),
    (".shots/t-wheel.py", "\u6eda\u8f6e\u5faa\u73af\u6eda\u52a8", []),
    (".shots/t-editor.py", "\u7f16\u8f91\u9875\u9884\u89c8\u4e0e\u65b0\u5efa\u5206\u7ec4", []),
]'''
assert old in t
t = t.replace(old, new)

t = t.replace('    for script, label in TESTS:', '    for script, label, extra in TESTS:')
t = t.replace(
    '        p = run([sys.executable, "-u", str(ROOT / script), device, str(CDP_PORT)], cwd=str(ROOT))',
    '        p = run([sys.executable, "-u", str(ROOT / script), *extra, device, str(CDP_PORT)], cwd=str(ROOT))')
t = t.replace('    all_ok = all(ok for _, ok, _ in results) and intact', '    all_ok = all(ok for _, ok, _ in results) and intact')
p.write_text(t, encoding="utf-8", newline="\n")
print("patched")
