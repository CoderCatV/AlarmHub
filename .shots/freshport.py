import sys, time
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
d.keep_screen_on()
print("  ensure_forward ->", d.ensure_forward(), " port now =", d.port)
for i in range(3):
    print(f"  probe {i+1}:", d.eval("JSON.stringify({ok:1})"))
