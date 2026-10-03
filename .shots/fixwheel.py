import pathlib
# Exact replacements, applied with Python so the file never passes through the GBK console codepage
# again. The damage was a UTF-8 file decoded as cp936; for the em dash the mapping did not exist, so
# what is left is U+FFFD plus a stray '?'.
FIXES = [
    ('"\u0421\u02b1"', '"\u5c0f\u65f6"'),                                  # "Сʱ"      -> "小时"
    ('"\ufffd\ufffd\ufffd\ufffd"', '"\u5206\u949f"'),                       # "����"    -> "分钟"
    ('"\ufffd\u00bd\ufffd"', '"\u65b0\u5efa"'),                            # "�½�"    -> "新建"
    ('\ufffd?', '\u2014'),                                                 # lost em dash
    ('\ufffd', '\u2014'),                                                  # any remnant
]
p = pathlib.Path(".shots/t-wheel.py")
t = p.read_text(encoding="utf-8")
for old, new in FIXES:
    n = t.count(old)
    if n:
        t = t.replace(old, new)
        print(f"  {old!r} -> {new!r}: {n}")
p.write_text(t, encoding="utf-8", newline="\n")
print("remaining U+FFFD:", t.count("\ufffd"))
