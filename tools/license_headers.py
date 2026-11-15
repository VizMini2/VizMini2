#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Adds (or checks) the license header of the project's own source files.

    python3 tools/license_headers.py           # add the header where it is missing
    python3 tools/license_headers.py --check   # list the files without it (exit code 1 if any): used by the CI

Third-party code keeps its own license and is skipped: the Gradle wrapper, the jros2 fork and the prebuilt Filament
(tools/jros2-16k, tools/filament-desktop/*/ outputs), Material icons (res/drawable*), generated files and build output.
"""
import os
import sys

OWNER = "Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>"
SPDX = "SPDX-License-Identifier: GPL-3.0-only"

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SKIP_DIRS = {"build", ".gradle", ".git", ".idea", ".kotlin", "node_modules", "_build"}
SKIP_PREFIXES = (
    "tools/jros2-16k/classes", "tools/jros2-16k/repo", "tools/jros2-16k/desktop",
    "tools/filament-desktop/out", "app/src/main/res/drawable", "app/src/main/res/mipmap",
    "app/src/main/res/raw", "docs/_build", "app/release", "app/play",
)
SKIP_FILES = {"gradlew", "gradlew.bat", "local.properties"}

SLASH = (".kt", ".kts", ".java", ".c", ".h")
HASH = (".py", ".sh", ".yml", ".yaml")


def header_for(path):
    name = os.path.basename(path)
    if name.endswith(SLASH):
        return f"// {OWNER}\n// {SPDX}\n"
    if name.endswith(HASH) or name == "CMakeLists.txt" or ("/scripts/" in path and path.startswith("ros/") and "." not in name):
        return f"# {OWNER}\n# {SPDX}\n"
    if name.endswith(".xml") and ("/res/layout" in path or "/res/xml" in path or "/res/values" in path or name in ("AndroidManifest.xml", "package.xml")):
        return f"<!-- {OWNER}\n     {SPDX} -->\n"
    return None


def files():
    for d, dirs, names in os.walk(ROOT):
        rel = os.path.relpath(d, ROOT)
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for n in names:
            p = os.path.normpath(os.path.join(rel, n)).replace(os.sep, "/")
            if n in SKIP_FILES or p.startswith(SKIP_PREFIXES) or "/build/" in p or p.startswith("build/"):
                continue
            if header_for(p):
                yield p


def has_header(text):
    return SPDX in text[:600]


def add(path):
    full = os.path.join(ROOT, path)
    text = open(full, encoding="utf-8").read()
    if has_header(text):
        return False
    h = header_for(path)
    lines = text.split("\n", 1)
    first = lines[0]
    rest = lines[1] if len(lines) > 1 else ""
    if first.startswith("<?xml") and not first.rstrip().endswith("?>"):
        # "<?xml ...?><!-- ..." on one line: the header goes right after the declaration.
        end = text.index("?>") + 2
        new = text[:end] + "\n" + h + text[end:]
    elif first.startswith("#!") or first.startswith("<?xml"):
        new = first + "\n" + h + (rest if rest.startswith("\n") or not rest else rest)
    else:
        new = h + ("\n" if h.startswith(("//", "#")) else "") + text
    open(full, "w", encoding="utf-8").write(new)
    return True


def main():
    check = "--check" in sys.argv
    missing = [p for p in files() if not has_header(open(os.path.join(ROOT, p), encoding="utf-8", errors="replace").read())]
    if check:
        for p in missing:
            print("no license header:", p)
        print(f"{len(missing)} file(s) without the license header")
        sys.exit(1 if missing else 0)
    for p in missing:
        add(p)
    print(f"added the license header to {len(missing)} file(s)")


if __name__ == "__main__":
    main()
