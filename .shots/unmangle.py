import re, pathlib
# The damage: UTF-8 bytes were decoded as GBK (cp936) and re-encoded as UTF-8. Reversing it is
# s.encode('gbk').decode('utf-8') -- lossless unless a byte pair had no GBK mapping, in which case
# the decode produced U+FFFD and the information is gone. Report that case instead of guessing.
for name in (".shots/t-wheel.py", ".shots/t-multiselect.py"):
    p = pathlib.Path(name)
    text = p.read_text(encoding="utf-8")
    runs = re.findall(r"[^\x00-\x7f]+", text)
    bad = []
    for r in runs:
        try:
            fixed = r.encode("gbk").decode("utf-8")
        except Exception as e:
            bad.append((r, type(e).__name__))
            continue
        text = text.replace(r, fixed)
    p.write_text(text, encoding="utf-8", newline="\n")
    print(f"{name}: fixed {len(runs) - len(bad)} Chinese runs, {len(bad)} unrecoverable")
    for r, why in bad[:6]:
        print(f"   unrecoverable: {r!r} ({why})")
