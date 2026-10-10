#!/usr/bin/env python3
"""Turn the interesting part of a build log into a GitHub annotation (so failures are readable without opening raw logs)."""
import re
import sys

path = sys.argv[1]
title = sys.argv[2] if len(sys.argv) > 2 else "Build output"
try:
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
except OSError:
    lines = []

interesting = []
for i, line in enumerate(lines):
    if re.search(r"error:|FAILED|What went wrong|Execution failed|Exception|AAPT|cannot find symbol|AssertionError|Test.*FAILED|BUILD FAILED", line):
        interesting.append(i)

picked = []
seen = set()
for i in interesting[:40]:
    for j in range(max(0, i - 1), min(len(lines), i + 4)):
        if j not in seen:
            seen.add(j)
            picked.append(lines[j])
text = "\n".join(picked) if picked else "\n".join(lines[-40:])
text = text[:3800]
text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print("::error title=%s::%s" % (title, text))
