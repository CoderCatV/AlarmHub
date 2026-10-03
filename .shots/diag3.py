import sys, time
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
print("pid:", d.ensure_forward())
for wait in (0, 0.5, 1.5, 3.0):
    time.sleep(wait)
    try:
        print(f"  after +{wait}s: {d.eval('JSON.stringify({ok:1})')}")
        break
    except Exception as e:
        print(f"  after +{wait}s: FAILED ({str(e).splitlines()[-1][:60]})")
