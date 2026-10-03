import sys, time
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
d.ensure_forward()
st = d.eval("(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.getSettings()))()")
print("  贪睡全局开关 =", st["snoozeEnabled"])
d.eval("JSON.stringify(document.querySelector('.fab')?.click() ?? null)"); time.sleep(3)
t = d.eval("JSON.stringify((document.querySelector('.page')||document.body).innerText.replace(/\\n+/g,' | '))")
print("  新建页含「贪睡」:", "贪睡" in t)
print("  新建页含「贪睡时长」:", "贪睡时长" in t)
print("  新建页可见文字片段:", t[:260])
