import sys
sys.path.insert(0, r"D:\Code\DeepSeek\alarm-clock\tools")
from importlib import import_module
ui = import_module("ui-drive")
d = ui.Driver("b9026932", 9223, 2.75, 0)
d.ensure_forward()
t = d.eval("JSON.stringify((document.querySelector('.page')||document.body).innerText.replace(/\\n+/g,' | '))")
print("  设置页可见文字:", t[:400])
for probe in ("贪睡时长", "贪睡次数上限", "再点", "音量键"):
    print(f"  含「{probe}」:", probe in t)
