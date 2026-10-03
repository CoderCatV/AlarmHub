#!/usr/bin/env python3
"""Fail if a source file shows the signature of a UTF-8/GBK round-trip.

WHY THIS EXISTS
---------------
Twice in one session a file was damaged by reading it with `Get-Content` and writing it back with
`Set-Content`. This machine's console codepage is GBK (cp936), so the UTF-8 bytes were decoded as
Chinese and the file was rewritten with that mojibake — four Chinese characters became six unrelated
ones. Where a byte pair had no GBK mapping the character became U+FFFD and the information was lost
for good. Once that cost a whole file rewrite; once, two test scripts.

The rule is "edit files with the editor, never through a shell text pipeline". Easy to state, easy to
forget, so this turns it into a check that takes a second:

    python tools/check-encoding.py

Two signals:

* **U+FFFD**, the replacement character. A decoding failure already happened; nothing produces it on
  purpose.
* **A run of non-ASCII text that round-trips.** This is the principled test, and it replaced a
  "rare characters" list that did not work: the first version listed characters like 板, 归 and 凡 as
  suspicious, and they are ordinary Chinese — `石板` is a colour name in this very project. Rarity
  cannot separate mojibake from real text, because the misread of any given word can land on common
  characters.

  The round-trip can. Legitimate Chinese practically never survives `encode('gbk')` followed by
  `decode('utf-8')`, because that demands the GBK bytes form valid UTF-8 — while mojibake, by
  construction, is *exactly* text for which that transform succeeds and yields the original. It is the
  same transform used to repair the damage, so a file it flags is genuinely recoverable.

Scope is source, docs and tools. The `.shots` directory is excluded: those are console transcripts
written by PowerShell in the system codepage, so they are legitimately not UTF-8, and flagging them
would be reporting the environment rather than a mistake.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Directories that are generated, vendored, or deliberately not UTF-8.
SKIP_PARTS = {
    ".git", "node_modules", "build", ".gradle-home", ".npm-cache", ".shots",
    "assets", "www", "dist", ".idea", ".m0build",
}

SCAN_ROOTS = [
    "web/src",
    "tools",
    "docs",
    "android/app/src",
    "package.json",
    "README.md",
]

TEXT_SUFFIXES = {
    ".py", ".js", ".mjs", ".cjs", ".ts", ".vue", ".kt", ".java", ".json", ".md", ".txt",
    ".css", ".html", ".xml", ".gradle", ".ps1", ".yml", ".yaml", ".properties", ".kts",
}

NON_ASCII_RUN = re.compile(r"[^\x00-\x7f]{2,}")
CJK = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf]")


def recovers(fragment: str) -> str | None:
    """Return the original text if `fragment` is the GBK misread of some Chinese, else None.

    A run of two or more characters is required: a single character can round-trip by coincidence,
    and flagging those produced noise in the first version of this check.
    """
    try:
        original = fragment.encode("gbk").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return None
    # Require the result to look like the Chinese that was damaged, not binary soup.
    return original if CJK.search(original) else None


def offenders(line: str) -> list[str]:
    bad = []
    if "\ufffd" in line:
        bad.append("U+FFFD")
    for run in NON_ASCII_RUN.findall(line):
        original = recovers(run)
        if original:
            bad.append(f"GBK misread of {original!r}")
    return bad


def iter_files():
    for root in SCAN_ROOTS:
        p = ROOT / root
        if p.is_file():
            yield p
        elif p.is_dir():
            for f in sorted(p.rglob("*")):
                if not f.is_file() or f.suffix.lower() not in TEXT_SUFFIXES:
                    continue
                rel = f.relative_to(ROOT)
                if any(part in SKIP_PARTS for part in rel.parts):
                    continue
                yield f


def main() -> int:
    hits = []
    checked = 0
    for f in iter_files():
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError as e:
            hits.append((f.relative_to(ROOT), 0, f"not valid UTF-8 ({e})"))
            continue
        checked += 1
        for n, line in enumerate(text.splitlines(), 1):
            bad = offenders(line)
            if bad:
                hits.append((f.relative_to(ROOT), n, f"{'; '.join(bad)} :: {line.strip()[:90]}"))

    if hits:
        print(f"MOJIBAKE in {len(hits)} place(s) out of {checked} files checked:")
        for rel, n, why in hits:
            print(f"  {rel}:{n}  {why}")
        print("\nRewrite the file with an editor and save it as UTF-8. See docs/STATUS.md 1.2 #7.")
        return 1

    print(f"clean: {checked} files checked, no mojibake")
    return 0


if __name__ == "__main__":
    sys.exit(main())
