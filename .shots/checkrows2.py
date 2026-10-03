import pathlib
lines = pathlib.Path("docs/STATUS.md").read_text(encoding="utf-8").splitlines()
for n in (52, 53, 54, 55):   # 0-based -> file lines 53..56 = ids 20..23
    line = lines[n]
    print(f"  line {n+1}: len={len(line):<5} ends_with_pipe={line.rstrip().endswith('|')}  tail=...{line[-45:]}")
