"""Why does tools/check-encoding.py not flag a file that contains mojibake?

    python -u .shots/probe-check-logic.py
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, "tools")
from importlib import import_module  # noqa: E402

ce = import_module("check-encoding")

MOJI = "宸ュ叿涓庤"
# NOTE: that sample is TRUNCATED (its last character is half of a two-character pair), which is why it
# does not round-trip and the checker ignores it. The first version of this probe used it and concluded,
# wrongly, that the checker was broken. A complete corruption of `工具与` is `宸ュ叿涓庤`.
FULL = "宸ュ叿涓庤"
print(f"sample      : {MOJI!r}")
print(f"utf-8 bytes : {MOJI.encode('utf-8')!r}")
try:
    back = MOJI.encode("gbk").decode("utf-8")
    print(f"round trip  : {back!r}")
except Exception as e:
    print(f"round trip  : <{type(e).__name__}: {e}>   <- truncated sample, correctly ignored")

print()
print(f"complete    : {FULL!r}")
try:
    back = FULL.encode("gbk").decode("utf-8")
    print(f"round trip  : {back!r}  <- this is the shape the checker looks for")
except Exception as e:
    print(f"round trip  : <{type(e).__name__}: {e}>")

print(f"recovers()  : {ce.recovers(FULL)!r}")

line = f"export const probe = '{FULL}'"
print(f"\nline        : {line!r}")
print(f"offenders() : {ce.offenders(line)!r}")

print(f"\nNON_ASCII_RUN.findall: {ce.NON_ASCII_RUN.findall(line)!r}")

print("\n--- what the file on disk actually holds ---")
p = Path("web/src/state/enc_probe.ts")
if p.exists():
    raw = p.read_bytes()
    print(f"bytes: {raw!r}")
    print(f"text : {raw.decode('utf-8', errors='replace')!r}")
else:
    print("(probe file not present; expected, it is deleted after each test)")
