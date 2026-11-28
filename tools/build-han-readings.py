#!/usr/bin/env python3
"""Build the lookup from Unicode 15.1 Unihan kMandarin.txt and kHanyuPinyin.txt."""

from collections import defaultdict
from pathlib import Path
import struct
import sys
import unicodedata


def toneless(syllable):
    decomposed = unicodedata.normalize("NFD", syllable.lower())
    return unicodedata.normalize("NFC", "".join(
        c for c in decomposed if not unicodedata.combining(c) or c in "\u0308\u0302"
    ))


def points(field):
    first, _, last = field.partition("..")
    return range(int(first, 16), int(last or first, 16) + 1)


mandarin, hanyu, output = map(Path, sys.argv[1:])
candidates = defaultdict(set)
for row in mandarin.read_text(encoding="utf-8").splitlines():
    code, field = row.split("\t")
    for point in points(code):
        candidates[point].update(map(toneless, field.split()))
for row in hanyu.read_text(encoding="utf-8").splitlines():
    code, field = row.split("\t")
    for point in points(code):
        if point in candidates:
            for entry in field.split():
                candidates[point].update(map(toneless, entry.split(":", 1)[1].split(",")))
entries = sorted((code, next(iter(values))) for code, values in candidates.items() if len(values) == 1)
syllables = sorted({value for _, value in entries})
index = {value: i for i, value in enumerate(syllables)}
with output.open("wb") as file:
    file.write(struct.pack(">HH", len(syllables), len(entries)))
    for value in syllables:
        data = value.encode("utf-8")
        file.write(struct.pack(">H", len(data)))
        file.write(data)
    for code, value in entries:
        file.write(struct.pack(">IH", code, index[value]))
