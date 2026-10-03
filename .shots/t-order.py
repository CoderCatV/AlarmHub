import sys, time
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("emulator-5554", 9222, 2.625, 136)
d.ensure_forward()

groups = d.eval("(async()=>JSON.stringify((await window.Capacitor.Plugins.AlarmHub.listGroups()).groups.map(g=>[g.id,g.name,g.sortOrder])))()")
print("  分组管理当前顺序:", groups)
by_name = {g[1]: g[0] for g in groups}
order = [by_name[n] for n in ("吃药", "未分组", "工作日", "节假日") if n in by_name]
print("  改成以「吃药」开头:", order)
d.eval(f"(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.reorderGroups({{orderedIds: {order}}})))()")
time.sleep(1)
d.eval("location.reload()"); time.sleep(6)
d.ensure_forward()

heads = d.eval("JSON.stringify([...document.querySelectorAll('.head__name')].map(h=>h.textContent.trim().replace(/\\s+/g,' ')))")
print("  列表页分组顺序:", heads)

d.eval("JSON.stringify(document.querySelector('.fab')?.click() ?? null)"); time.sleep(3)
chips = d.eval("JSON.stringify([...document.querySelectorAll('.chip--group')].map(c=>c.textContent.trim()+(c.classList.contains('chip--on')?' ←选中':'')))")
print("  编辑页分组顺序:", chips)

# Restore, so the emulator is left as it was found.
d.eval(f"(async()=>JSON.stringify(await window.Capacitor.Plugins.AlarmHub.reorderGroups({{orderedIds: {[by_name[n] for n in ('未分组','工作日','节假日','吃药') if n in by_name]}}})))()")
time.sleep(1)
print("  已还原")
