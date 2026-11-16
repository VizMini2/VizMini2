<p align="center">
  <img src="desktopApp/icons/vizmini2.png" alt="VizMini2 logo" width="140">
</p>

# VizMini2

[![CI](https://github.com/VizMini2/VizMini2/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/VizMini2/VizMini2/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Manual](https://img.shields.io/badge/manual-vizmini2.github.io-0A7BBB)](https://vizmini2.github.io)
<br>
[![ROS 2](https://img.shields.io/badge/ROS%202-Foxy%20%7C%20Humble%20%7C%20Jazzy%20%7C%20Kilted%20%7C%20Lyrical-22314E?logo=ros)](https://vizmini2.github.io/help/requirements.html)
[![Middleware](https://img.shields.io/badge/middleware-Fast%20DDS%20%7C%20Cyclone%20DDS%20%7C%20Zenoh-555)](https://vizmini2.github.io/config/network.html)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://vizmini2.github.io/getting-started/install.html)
[![Ubuntu](https://img.shields.io/badge/Ubuntu-20.04%2B-E95420?logo=ubuntu&logoColor=white)](https://vizmini2.github.io/getting-started/desktop.html)

ROS 2 visualization and control on Android and on the desktop, from one code base: a 3D view like RViz (TF, robot
model, maps, laser, point clouds, cameras, markers, interactive markers, Nav2 waypoints, MoveIt planning...),
dashboards, plots, teleop, topics, parameters, lifecycle, ros2_control controllers, map tools for Nav2 (saving, SLAM
Toolbox, costmaps, keepout and speed zones), alerts on any topic, H.264/H.265 video topics, screen recording, bags
and more. It talks DDS directly (Fast DDS through jros2), and also Zenoh and foxglove_bridge. It imports and exports
RViz 2 files (`.rviz`) and the panel and plot layouts of other ROS tools (`ui/.../interop/ConfigInterop.kt`).

- **Android**: the app on Google Play, or a debug build with `./gradlew installDirectDebug`.
- **Desktop (Linux, Ubuntu 20.04 or newer)**: `./gradlew :desktopApp:run`, the `.deb` from
  `./gradlew :desktopApp:packageDeb`, or as a ROS 2 package: `colcon build` in a workspace with `ros/vizmini2`, then
  `ros2 run vizmini2 vizmini2`. `ffmpeg` is used for H.264/H.265 video and for recording the window.
- **User manual**: `docs/` (Sphinx, English and Spanish): `docs/build.sh`.

## Repository

| Folder | What it is |
| --- | --- |
| `core/` | Shared logic (ROS connection, dynamic messages, models, Nav2/MoveIt clients...), plain JVM, unit tested |
| `ui/`, `rviz3d/` | Shared Compose screens and the Filament 3D view, compiled by both apps |
| `app/` | The Android app |
| `desktopApp/` | The desktop app (Compose for Desktop) and its end-to-end tests |
| `ros/vizmini2/` | The ROS 2 package that builds and starts the desktop app |
| `tools/` | Test servers (fake robot, fake Nav2, MoveIt with the Panda...), CI scripts and build helpers |
| `docs/` | User manual |

## Building and testing

```bash
./gradlew :core:jvmTest testDirectDebugUnitTest lintDirectDebug   # unit tests and lint
./gradlew dokkaGenerate                                            # API reference: build/dokka/html/index.html
tools/e2e/run.sh                                                   # desktop end-to-end tests against ROS 2 servers
tools/e2e/android_device_tests.sh                                  # device tests on a phone or emulator (demo robot)
tools/pc_tests.sh -b                                               # phone tests against servers on this PC
tools/compat/build-desktop-natives.sh                              # rebuild the desktop natives inside Ubuntu 20.04
tools/compat/try-desktop.sh 22.04 [vizmini2_1.0.0_amd64.deb]       # run the app (or install the .deb) on another Ubuntu
```

The desktop app's own native libraries (Fast DDS, Filament, an X11 helper) are built inside Ubuntu 20.04 so they run
on 20.04 and every newer release; `tools/compat/check-glibc.sh` checks it. The scripts need neither sudo nor Docker.

The CI (`.github/workflows/ci.yml`) runs the checks, unit tests, lint, the builds, the documentation, the desktop app
on clean Ubuntu 20.04, 22.04 and 24.04, the desktop end-to-end tests against ROS 2 servers of Humble, Jazzy and
Lyrical (on each one's Ubuntu), and the device tests on an Android emulator.

## Releases

`.github/workflows/release.yml` builds every file of a release on each `v*` tag (or by hand from the Actions tab, to
try it): the signed APKs (one per architecture and the universal one), the `.deb` and a
`.tar.gz` of the desktop app, the manual and the API reference, all checked (the `.deb` is installed and started on
Ubuntu 20.04 and 24.04), and leaves them in a **draft** GitHub release with `SHA256SUMS` and the CHANGELOG entry as
notes. The Google Play bundle (`.aab`) is only a workflow artifact, to upload by hand; nothing goes to Google Play
or Firebase. When you publish that release, `.github/workflows/site.yml` builds the
manual and the API reference from its tag and pushes them to
[VizMini2/VizMini2.github.io](https://github.com/VizMini2/VizMini2.github.io), the website at https://vizmini2.github.io
(secret `SITE_DEPLOY_KEY`: a deploy key with write access on that repository; see the top of the workflow).

To release: the same version in `app/build.gradle.kts` (`versionName`, and a higher `versionCode`), `gradle.properties`
(`vizmini2.version`) and `ros/vizmini2/package.xml`, its entry in both CHANGELOGs (`python3 tools/release/version.py
check v1.2.3` checks all of it), then `git tag v1.2.3 && git push origin v1.2.3`. The signing key goes in the repository
secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD`; the R8 mappings for Crashlytics are kept as a workflow artifact.

## License

Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>

VizMini2 is free software under the **GNU General Public License version 3** ([LICENSE](LICENSE)), with an additional
permission to combine it with Google Play Services and Firebase ([NOTICE](NOTICE)). The name "VizMini2" and its logo
are not covered by the license: forks must use their own. Contributions are welcome under [CLA.md](CLA.md); see
[CONTRIBUTING.md](CONTRIBUTING.md).

If you need VizMini2 under other terms (for example inside a closed product), write to
jc.manzanares.serrano@gmail.com.
