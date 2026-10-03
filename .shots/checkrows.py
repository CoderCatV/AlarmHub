import pathlib, re
text = pathlib.Path("docs/STATUS.md").read_text(encoding="utf-8")
bad = 0
for n, line in enumerate(text.splitlines(), 1):
    m = re.match(r"^\| (\d+) \|", line)
    if not m:
        continue
    pipes = line.count("|")
    ok = pipes == 6
    if not ok:
        bad += 1
    print(f"  line {n}: id={m.group(1):<4} pipes={pipes} {'OK' if ok else 'SUSPECT'}")
print("suspect rows:", bad)
