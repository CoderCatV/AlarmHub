import sys, time
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
d.ensure_forward()

print("  起始:", d.eval("(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups.map(g=>g.id+':'+g.name)))()"))
print("  行:", d.eval("JSON.stringify([...document.querySelectorAll('li.item')].map(r=>r.querySelector('.item__name').textContent.trim().replace(/\\s+/g,' ')))"))

print("  点开「测试」行:", d.eval("JSON.stringify((()=>{const r=[...document.querySelectorAll('li.item')].find(x=>x.querySelector('.item__name').textContent.includes('测试')); if(!r) return 'not found'; r.querySelector('.item__main').click(); return 'opened'})())"))
time.sleep(2)
print("  sheet 里的删除按钮:", d.eval("JSON.stringify(document.querySelector('.danger')?.textContent.trim() ?? 'NO .danger')"))

d.eval("JSON.stringify(document.querySelector('.danger')?.click() ?? null)"); time.sleep(1.5)
print("  第一次点后文案:", d.eval("JSON.stringify(document.querySelector('.danger')?.textContent.trim() ?? 'NO .danger')"))
d.eval("JSON.stringify(document.querySelector('.danger')?.click() ?? null)"); time.sleep(2.5)
print("  第二次点后:", d.eval("(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups.map(g=>g.id+':'+g.name)))()"))
print("  sheet 还开着:", d.eval("JSON.stringify(!!document.querySelector('.danger'))"))
