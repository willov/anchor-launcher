#!/usr/bin/env python3
# Copyright (C) 2025 Anchor Launcher Contributors
#
# Licensed under the Apache License, Version 2.0.
# See LICENSE for details.
"""Replace "Lawnchair" with "Anchor" inside translated <string> values.

Anchor's English overrides live in res/values/strings.xml, but Lawnchair's translations
(values-*/strings.xml) still name Lawnchair. Re-run this after every upstream merge, since
Lawnchair's Crowdin updates bring the old name back. Only string values are touched, so
copyright headers and XML comments are left alone.

Usage (from the repo root): python3 scripts/rebrand-translations.py
"""

import glob
import re

PATTERNS = [
    "lawnchair/res/values-*/strings.xml",
    "res/values-*/strings.xml",
    "quickstep/res/values-*/strings.xml",
]
STRING_RE = re.compile(r"(<string\b[^>]*>)(.*?)(</string>)", re.S)

total = 0
changed_files = 0
for pattern in PATTERNS:
    for path in sorted(glob.glob(pattern)):
        with open(path, encoding="utf-8") as f:
            text = f.read()
        count = 0

        def replace(match):
            global count
            count += match.group(2).count("Lawnchair")
            return match.group(1) + match.group(2).replace("Lawnchair", "Anchor") + match.group(3)

        new_text = STRING_RE.sub(replace, text)
        if new_text != text:
            with open(path, "w", encoding="utf-8") as f:
                f.write(new_text)
            total += count
            changed_files += 1

print(f"{total} replacements in {changed_files} files")
