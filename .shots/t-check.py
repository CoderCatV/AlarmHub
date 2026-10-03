import sys
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
d.ensure_forward()
print("  你的设置:", d.eval("(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.getSettings()))()"))
print("  当前页面:", d.eval("JSON.stringify(document.querySelector('.bar__title')?.textContent.trim() ?? 'list')"))
print("  设置页上的开关:", d.eval("JSON.stringify([...document.querySelectorAll('input[type=checkbox]')].map(i=>({on:i.checked})))"))
