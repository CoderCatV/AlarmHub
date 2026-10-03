"""Read the first line of a commit message WITHOUT PowerShell's console codepage in the way.

The shell in this session decodes subprocess output as GBK and non-ASCII comes back as '?',
so "is this commit message mojibake?" cannot be answered through it. This reads git's raw
bytes and reports what they actually are.

    python -u .shots/probe-commit-encoding.py <sha> [<sha> ...]
"""

import subprocess
import sys
import unicodedata


def check(sha):
    raw = subprocess.run(
        ["git", "log", "-1", "--format=%B", sha], capture_output=True
    ).stdout
    first = raw.split(b"\n", 1)[0]
    print(f"=== {sha} ===")
    print(f"  raw bytes : {first[:60]!r}")
    try:
        text = first.decode("utf-8")
        print(f"  as UTF-8  : {text}")
        # Mojibake signature: UTF-8 Chinese bytes decoded as GBK and re-encoded to UTF-8 shows up as
        # characters from the CJK block that make no sense in context (e.g. 宸ュ叿 for 工具).
        suspicious = any("\u4e00" <= ch <= "\u9fff" for ch in text)
        print(f"  has CJK   : {suspicious}")
        if suspicious:
            # The tell-tale: try the round trip that produced it (UTF-8 -> GBK -> UTF-8).
            try:
                repaired = text.encode("gbk", errors="strict").decode("utf-8", errors="strict")
                print(f"  可还原为  : {repaired}   <-- 说明这确实是乱码")
            except Exception:
                print("  可还原为  : 不能还原（说明它本来就是正常中文）")
    except UnicodeDecodeError as e:
        print(f"  as UTF-8  : FAILED ({e})")


if __name__ == "__main__":
    shas = sys.argv[1:] or ["fb5d79c", "8e6508d"]
    for sha in shas:
        check(sha)
