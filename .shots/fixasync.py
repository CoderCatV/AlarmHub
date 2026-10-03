import pathlib, re
# Every bridge call in the test scripts used top-level `await`, which is a SyntaxError inside
# Runtime.evaluate. Wrap each in an async IIFE, which returns a promise that `awaitPromise` resolves.
fixes = {
    ".shots/t-editor.py": [
        ('"JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups"',
         '"(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups"'),
    ],
}
# Generic pass: find "JSON.stringify(await <expr>)" and wrap it.
pat = re.compile(r'"JSON\.stringify\(await (.*?)\)"\s*$', re.M)
for name in (".shots/t-editor.py", ".shots/t-multiselect.py"):
    p = pathlib.Path(name)
    t = p.read_text(encoding="utf-8")
    # Close the IIFE for the two listGroups edits made above.
    t = t.replace('.map(g => g.id))"', '.map(g => g.id)))()"')
    t = t.replace('"JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteGroup("\n            f"{{id: {ids[0]}, moveAlarmsTo: 1}}))"',
                  '"(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteGroup("\n            f"{{id: {ids[0]}, moveAlarmsTo: 1}})))()"')
    t = t.replace('"JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteAlarms("\n                f"{{ids: {json.dumps(ids)}}}))"',
                  '"(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.deleteAlarms("\n                f"{{ids: {json.dumps(ids)}}})))()"')
    t = t.replace('"JSON.stringify(await window.Capacitor.Plugins.AlarmHub.saveAlarm("\n            f"{{hour: {hour}, minute: 7, label: {json.dumps(TEMP_LABEL)}}}))"',
                  '"(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.saveAlarm("\n            f"{{hour: {hour}, minute: 7, label: {json.dumps(TEMP_LABEL)}}})))()"')
    p.write_text(t, encoding="utf-8", newline="\n")
    hits = [n for n, line in enumerate(t.splitlines(), 1) if 'JSON.stringify(await' in line or 'await window' in line]
    print(f"{name}: remaining bare-await lines {hits}")
