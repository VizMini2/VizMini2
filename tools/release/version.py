#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""The release version, from the code, for the release workflow (.github/workflows/release.yml).

    python3 tools/release/version.py check [v1.0.0]   # prints the version; exit 1 if the sources disagree
    python3 tools/release/version.py check --lenient  # the same, but only warns (test builds by hand): the desktop's
    python3 tools/release/version.py notes 1.0.0      # that version's CHANGELOG entry (English, then Spanish)

`check` compares the Android versionName (app/build.gradle.kts), the desktop one (vizmini2.version in
gradle.properties), the ROS package's (ros/vizmini2/package.xml) and, if given, the tag; with a tag it also needs
the version's entry in both CHANGELOGs (app/src/main/assets/CHANGELOG.md and CHANGELOG.es.md).
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def versions():
    out = {}
    m = re.search(r'versionName\s*=\s*"([^"]+)"', read("app/build.gradle.kts"))
    out["Android (app/build.gradle.kts versionName)"] = m.group(1) if m else None
    m = re.search(r"^vizmini2\.version=(\S+)", read("gradle.properties"), re.M)
    out["desktop (gradle.properties vizmini2.version)"] = m.group(1) if m else None
    m = re.search(r"<version>([^<]+)</version>", read("ros/vizmini2/package.xml"))
    out["ROS package (ros/vizmini2/package.xml)"] = m.group(1) if m else None
    return out


def changelog_entry(version, spanish=False):
    text = read("app/src/main/assets/CHANGELOG%s.md" % (".es" if spanish else ""))
    m = re.search(r"^## \[%s\][^\n]*\n(.*?)(?=^## \[|\Z)" % re.escape(version), text, re.M | re.S)
    return m.group(1).strip() if m else None


def check(tag, lenient=False):
    found = versions()
    problems = [f"{k}: not found" for k, v in found.items() if v is None]
    values = {v for v in found.values() if v}
    if len(values) > 1:
        problems.append("the versions disagree: " + "; ".join(f"{k} = {v}" for k, v in found.items()))
    version = next(iter(values)) if len(values) == 1 else None
    if tag:
        wanted = tag[1:] if tag.startswith("v") else tag
        if version and wanted != version:
            problems.append(f"the tag {tag} is not the code's version {version}")
        for es in (False, True):
            if not changelog_entry(wanted, es):
                problems.append(f"no [{wanted}] entry in CHANGELOG{'.es' if es else ''}.md")
    if problems:
        for p in problems:
            print(("warning: " if lenient else "") + "version: " + p, file=sys.stderr)
        if not lenient:
            sys.exit(1)
        version = version or found["desktop (gradle.properties vizmini2.version)"] or "0.0.0"
    print(version)


def notes(version):
    en, es = changelog_entry(version), changelog_entry(version, True)
    if not en:
        sys.exit(f"no [{version}] entry in CHANGELOG.md")
    print(en)
    if es:
        print("\n---\n\n**Español**\n\n" + es)
    print("\n---\n\nFiles: `VizMini2-%s-*.apk` (Android 8+, the arm64-v8a one fits almost every phone; universal: all), "
          "`vizmini2_%s_amd64.deb` (Ubuntu 20.04 or newer: `sudo apt install ./vizmini2_%s_amd64.deb`), "
          "`vizmini2-%s-linux-x86_64.tar.gz` (the same app without installing: `vizmini2/bin/vizmini2`), "
          "the manual, and `SHA256SUMS`." % (version, version, version, version))


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "check":
        arg = sys.argv[2] if len(sys.argv) > 2 else None
        check(None if arg == "--lenient" else arg, lenient=arg == "--lenient")
    elif len(sys.argv) == 3 and sys.argv[1] == "notes":
        notes(sys.argv[2])
    else:
        sys.exit(__doc__)
