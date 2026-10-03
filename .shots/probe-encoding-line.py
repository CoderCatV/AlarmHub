"""Report exactly what check-encoding.py objects to, with correct decoding.

The check reported `docs/STATUS.md:60  GBK misread of '?????????u'` -- the '?' are the console's own
loss (GBK codepage), so the message itself cannot be judged from the terminal. This reads the file
with the file backend and shows both decodings.

    python -u .shots/probe-encoding-line.py docs/STATUS.md 60
"""

import sys
from pathlib import Path


def main():
    path = Path(sys.argv[1])
    line_no = int(sys.argv[2])
    raw = path.read_bytes()
    lines = raw.split(b"\n")
    raw_line = lines[line_no - 1]

    print(f"file: {path}  line {line_no}")
    print(f"  raw bytes  : {raw_line[:90]!r}")
    print(f"  as UTF-8   : {raw_line.decode('utf-8', errors='replace')[:90]}")
    print(f"  as GBK     : {raw_line.decode('gbk', errors='replace')[:90]}")

    # The check-encoding heuristic: does an ASCII-only fragment appear to be a GBK misread of UTF-8?
    # Reproduce its verdict so the reason is visible rather than guessed at.
    text = raw_line.decode("utf-8", errors="replace")
    for i, ch in enumerate(text):
        if ord(ch) > 127:
            continue
        # A run of ASCII followed by... this is where the check looks for a fragment that
        # re-encodes to valid UTF-8 when read as GBK.
    print()
    print("  note: the check looks for NON-ASCII fragments that round-trip through")
    print("        text.encode('gbk').decode('utf-8'). This line was flagged, so it found one.")


if __name__ == "__main__":
    main()
