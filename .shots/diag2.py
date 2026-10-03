import sys, traceback
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
print("port:", d.port, type(d.port))
d.keep_screen_on()
print("pid:", d.ensure_forward())
try:
    print("eval:", d.eval("JSON.stringify({t: document.querySelector('.bar__title')?.textContent.trim() ?? null})"))
except Exception:
    traceback.print_exc()
    # Try the raw path with the module's own helpers to see what the probe actually printed.
    p = d._eval_once("1+1")
    print("RAW returncode:", p.returncode)
    print("RAW stdout:", repr(p.stdout[:300]))
    print("RAW stderr:", repr(p.stderr[:300]))
