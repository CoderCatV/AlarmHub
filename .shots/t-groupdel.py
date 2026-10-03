import sys, time
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
d.ensure_forward()

print("  起始:", d.eval("(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups.map(g=>g.id+':'+g.name+(g.isSystem?'(内置)':''))))()"))

d.eval("JSON.stringify(document.querySelector('.hero__settings')?.click() ?? null)"); time.sleep(2)
d.eval("JSON.stringify(([...document.querySelectorAll('button')].find(b=>b.textContent.includes('分组管理'))||{}).click() ?? null)"); time.sleep(2)
print("  页面:", d.eval("JSON.stringify(document.querySelector('.bar__title')?.textContent.trim())"))
print("  行:", d.eval("JSON.stringify([...document.querySelectorAll('.row, .group')].map(r=>r.textContent.trim().replace(/\\s+/g,' ').slice(0,24)))"))

opened = d.eval("JSON.stringify((()=>{const rows=[...document.querySelectorAll('.row, .group')]; const t=rows.find(r=>r.textContent.includes('测试')); if(!t) return 'not found'; (t.querySelector('button')||t).click(); return 'opened'})())")
print("  点开那个「测试」行:", opened); time.sleep(2)
print("  sheet 里的按钮:", d.eval("JSON.stringify([...document.querySelectorAll('.sheet button, .btn, .danger')].map(b=>b.textContent.trim().slice(0,30)))"))

for n in (1, 2):
    txt = d.eval("JSON.stringify(document.querySelector('.danger')?.textContent.trim() ?? 'NO .danger BUTTON')")
    print(f"  第 {n} 次点删除前按钮文案: {txt}")
    d.eval("JSON.stringify(document.querySelector('.danger')?.click() ?? null)"); time.sleep(2)
print("  之后:", d.eval("(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups.map(g=>g.id+':'+g.name)))()"))
print("  sheet 还开着吗:", d.eval("JSON.stringify(!!document.querySelector('.sheet'))"))
