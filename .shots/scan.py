import re, pathlib
# Report every line with non-ASCII in the wheel test, plus the classic mojibake signature, so the
# remaining damage is visible rather than guessed at. (Reading with Python, not PowerShell: the
# console's GBK codepage is what caused the damage in the first place.)
p = pathlib.Path(".shots/t-wheel.py")
MOJI = set("锛鍏鏂缂纭纰閫€鍒犻€夊彇鍚庢寜閿欒繑鍥炲凡")  # rare CJK picked up by a UTF-8-as-GBK misread
for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
    if any(ord(c) > 127 for c in line):
        flag = " <-- MOJIBAKE?" if (set(line) & MOJI or "\ufffd" in line) else ""
        print(f"{n:>4}: {line.strip()}{flag}")
