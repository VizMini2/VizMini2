<!-- Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
SPDX-License-Identifier: Apache-2.0 -->

# Prebuilt binaries

Some files the build needs are built by this project's own scripts, take minutes to hours to rebuild and weigh tens of
megabytes. They are not kept in git (each rebuilt copy would stay in the history for good): they live in the
repository's [`prebuilt` release](https://github.com/VizMini2/VizMini2/releases/tag/prebuilt), and
[`prebuilt.txt`](prebuilt.txt) says, for each one, where it goes, its SHA-256 and its URL.

| File | What it is | Built by |
| --- | --- | --- |
| `tools/jros2-16k/repo/.../jros2-android-1.5.1-16k-graph.aar` | jros2 (Fast DDS) for Android, 16 KB pages | `tools/jros2-16k/build.sh` |
| `tools/jros2-16k/desktop/jros2-desktop.jar` | jros2 (Fast DDS) for the desktop, Linux and Windows | `tools/compat/build-desktop-natives.sh fastdds`, `tools/desktop-natives/add-windows.sh` |
| `tools/filament-desktop/filament-desktop.jar` | Filament's Java bindings for the desktop, Linux and Windows | `tools/compat/build-desktop-natives.sh filament`, `tools/desktop-natives/add-windows.sh` |

## Getting them

Nothing to do: every Gradle build (`settings.gradle.kts`) downloads the ones that are missing or out of date and
checks their SHA-256. Scripts that read them without Gradle call `tools/prebuilt/fetch.sh`, which does the same.

A file rebuilt here (different from the one last downloaded) is never overwritten: the build warns and uses it.

## Publishing a rebuilt one

```bash
tools/prebuilt/publish.sh                                       # every file that changed
tools/prebuilt/publish.sh tools/jros2-16k/desktop/jros2-desktop.jar   # or just this one
git add tools/prebuilt/prebuilt.txt && git commit -m "Natives: ..."
```

It uploads each changed file to the `prebuilt` release under a name with its hash (`jros2-desktop-63879d99.jar`) and
updates its line in `prebuilt.txt`. Old files stay in the release, so older commits still build: **never delete them**.
The release is a pre-release and never the latest one, so it does not get in the way of VizMini2's releases, and the
website is not deployed for it.
