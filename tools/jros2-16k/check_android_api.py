#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Checks that the jros2 Java classes only use Java APIs that exist on Android since minSdk.

jros2 is compiled against Java 17+ and uses methods that Android added much later (for example
FloatBuffer.put(int, FloatBuffer, int, int), from API 35): on older phones they fail with
NoSuchMethodError in the middle of a subscription. It looks at every java.* call in the classes and looks it up
in the SDK's api-versions.xml (also in the parent classes).

Usage: check_android_api.py <folder with the .class files> <api-versions.xml> <minSdk>
Exits with 1 and the list of calls if any does not exist at minSdk.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# What does work even though it is not in minSdk:
# - java.nio.file: added by the app's desugaring (desugar_jdk_libs_nio).
# - StringBuilder: its methods come from AbstractStringBuilder, which is not in api-versions.xml.
ALLOWED = (re.compile(r"^java/nio/file/"), re.compile(r"^java/lang/StringBuilder\."))


def api_level(v) -> int:
    """API level of a "since": newer SDKs also have minor versions ("36.1"), which count as their major one."""
    return int(str(v).split(".")[0])


def main():
    classes_dir, api_xml, min_sdk = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
    api = {}
    for c in ET.parse(api_xml).getroot().findall("class"):
        since = api_level(c.get("since", "1"))
        methods = {m.get("name"): api_level(m.get("since", since)) for m in c.findall("method")}
        parents = [e.get("name") for e in c.findall("extends")] + [e.get("name") for e in c.findall("implements")]
        api[c.get("name")] = (since, methods, parents)

    def level(cls, sig, seen):
        if cls in seen or cls not in api:
            return None
        seen.add(cls)
        since, methods, parents = api[cls]
        if sig in methods:
            return max(since, methods[sig])
        for p in parents:
            found = level(p, sig, seen)
            if found is not None:
                return found
        return None

    names = [str(p.relative_to(classes_dir))[:-6].replace("/", ".") for p in classes_dir.rglob("*.class")]
    out = subprocess.run(["javap", "-c", "-p", "-cp", str(classes_dir)] + names, capture_output=True, text=True, check=True).stdout
    problems = {}
    current = "?"
    header = re.compile(r"^(?:[a-z]+ )*(?:class|interface|enum) ([\w.$]+)")
    call = re.compile(r"(?:Method|InterfaceMethod) (java/[\w/$]+)\.([\w<>$]+):(\([^ ]*)")
    for line in out.splitlines():
        h = header.match(line)
        if h:
            current = h.group(1)
            continue
        for cls, name, desc in call.findall(line):
            ref = f"{cls}.{name}{desc}"
            if any(a.search(ref) for a in ALLOWED):
                continue
            lvl = level(cls, name + desc, set())
            if lvl is None or lvl > min_sdk:
                problems.setdefault((ref, lvl), set()).add(current)

    for (ref, lvl), users in sorted(problems.items()):
        print(f"  API {lvl if lvl is not None else '?'}: {ref}  ({', '.join(sorted(users))})")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
