#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""Fails if a source comment looks Spanish (code and comments are in English; user texts live in strings*.xml).

    python3 tools/check_english_comments.py        # used by the CI; exit code 1 and the offending lines if any

Only comments are checked (// /* */ KDoc, # and docstrings, <!-- -->), never string literals. A comment counts as
Spanish when it has two or more common Spanish words, or Spanish punctuation. Lines can opt out with
"lang-ok" (for example a comment quoting a Spanish UI text).
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SKIP_DIRS = {"build", ".gradle", ".git", ".idea", ".kotlin", "_build", "jros2-16k"}
EXTS = (".kt", ".kts", ".java", ".c", ".h", ".py", ".sh", ".yml", ".yaml", ".xml")
WORDS = {"que", "del", "los", "las", "una", "para", "con", "por", "como", "cuando", "está", "están", "hay", "pero",
         "también", "porque", "aquí", "esto", "este", "esta", "todo", "todos", "cada", "desde", "hasta", "sólo",
         "solo", "si", "ya", "muy", "más", "puede", "hace", "otra", "otro", "donde", "entre", "sobre", "tiene"}
TOKEN = re.compile(r"[a-záéíóúñü]+", re.IGNORECASE)


def comments(path, text):
    """(line number, comment text) pairs."""
    out = []
    name = os.path.basename(path)
    if name.endswith(".xml"):
        for m in re.finditer(r"<!--(.*?)-->", text, re.S):
            out.append((text.count("\n", 0, m.start()) + 1, m.group(1)))
        return out
    if "." not in name and text.startswith("#!/bin/sh"):
        name += ".sh"  # extensionless launchers (ros/*/scripts)
    hash_style = name.endswith((".py", ".sh", ".yml", ".yaml"))
    i, n, line = 0, len(text), 1
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1; i += 1; continue
        if hash_style and name.endswith(".py") and text.startswith(('"""', "'''"), i):
            q = text[i:i + 3]; j = text.find(q, i + 3); j = n if j < 0 else j + 3
            out.append((line, text[i + 3:j - 3])); line += text.count("\n", i, j); i = j; continue
        if c in "\"'" and not (c == "'" and not hash_style and name.endswith((".kt", ".kts"))):
            if not hash_style and text.startswith('"""', i):
                j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and text[j] != c and text[j] != "\n":
                    j += 2 if text[j] == "\\" else 1
                j += 1
            line += text.count("\n", i, j); i = j; continue
        if hash_style and c == "#" and not (name.endswith(".sh") and i > 0 and text[i - 1] in "${"):
            j = text.find("\n", i); j = n if j < 0 else j
            out.append((line, text[i + 1:j])); i = j; continue
        if not hash_style and text.startswith("//", i):
            j = text.find("\n", i); j = n if j < 0 else j
            out.append((line, text[i + 2:j])); i = j; continue
        if not hash_style and text.startswith("/*", i):
            j = text.find("*/", i + 2); j = n if j < 0 else j + 2
            out.append((line, text[i + 2:j - 2])); line += text.count("\n", i, j); i = j; continue
        i += 1
    return out


def spanish(comment):
    if "lang-ok" in comment:
        return False
    if "¿" in comment or "¡" in comment:
        return True
    words = [w.lower() for w in TOKEN.findall(comment)]
    return sum(1 for w in words if w in WORDS) >= 2


def main():
    bad = []
    for d, dirs, names in os.walk(ROOT):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for nm in names:
            if not (nm.endswith(EXTS) or (os.path.basename(d) == "scripts" and "." not in nm)) or "/res/values" in d.replace(os.sep, "/"):
                continue
            p = os.path.join(d, nm)
            text = open(p, encoding="utf-8", errors="replace").read()
            for ln, c in comments(p, text):
                for k, part in enumerate(c.split("\n")):
                    if spanish(part):
                        bad.append(f"{os.path.relpath(p, ROOT)}:{ln + k}: {part.strip()[:120]}")
    for b in bad:
        print(b)
    print(f"{len(bad)} comment line(s) look Spanish")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
