import sys, time
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
d.ensure_forward()

def snapshot(tag):
    v = d.eval("""JSON.stringify({
      rows: [...document.querySelectorAll('.formrow, .row')].map(r => (r.querySelector('.formrow__label, .row__label') || r).textContent.trim().slice(0,10)).filter(t => /贪睡|音量键/.test(t)),
      volChips: [...document.querySelectorAll('.chip')].map(c => c.textContent.trim()).filter(t => /贪睡|关闭/.test(t)),
      switchOn: (() => { const t = [...document.querySelectorAll('input[type=checkbox], .toggle input, [role=switch]')]; return t.length ? t.map(x => x.checked ?? x.getAttribute('aria-checked')) : 'no switch found' })(),
    })""")
    print(f"  {tag}: {v}")

d.eval("JSON.stringify(document.querySelector('.hero__settings')?.click() ?? null)"); time.sleep(2)
snapshot("初始（贪睡开）")

clicked = d.eval("""JSON.stringify((() => {
  const sw = document.querySelector('[aria-label="贪睡"], input[aria-label="贪睡"], .toggle input');
  if (!sw) return 'no switch';
  sw.click();
  return 'clicked';
})())""")
print("  点开关:", clicked); time.sleep(2.5)
snapshot("贪睡关")

d.eval("""JSON.stringify((() => { const sw = document.querySelector('[aria-label="贪睡"], input[aria-label="贪睡"], .toggle input'); if (sw) sw.click(); return 'restored' })())""")
time.sleep(2)
snapshot("还原")
