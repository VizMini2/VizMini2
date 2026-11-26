# Contributing to VizMini2

Thanks for helping! Bug reports, ideas and pull requests are welcome.

## Before you start

- The code is under the Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)), and so is every
  contribution (section 5 of the license): there is no extra agreement to sign. New source files start with the same
  header as the others:

  ```kotlin
  // Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
  // SPDX-License-Identifier: Apache-2.0
  ```

- Code and comments in English; user-facing texts go to `app/src/main/res/values*/strings*.xml` (English and Spanish).

## Layout of the repository

- `core/`: shared logic (ROS connection, messages, models), plain JVM, tested with JUnit.
- `ui/` and `rviz3d/`: shared Compose screens and the Filament 3D view, compiled by both apps.
- `app/`: the Android app. `desktopApp/`: the desktop app (Linux and Windows; on Linux also
  `ros2 run vizmini2 vizmini2`: the repository is also a ROS 2 package, `package.xml` and `CMakeLists.txt` at its root).
- `tools/`: test servers (fake robot, Nav2, MoveIt...) and build helpers. The native libraries (Fast DDS, Filament) are
  not in git: the build downloads them from the `prebuilt` release, and rebuilt ones are published with
  `tools/prebuilt/publish.sh` (see `tools/prebuilt/README.md`).

## Tests

- JVM: `./gradlew :core:jvmTest testDirectDebugUnitTest lintDirectDebug`.
- End to end against ROS on the PC: `VIZMINI2_E2E=1 ./gradlew :desktopApp:jvmTest` with the servers in
  `tools/e2e/run.sh` (it starts them, runs the tests and stops them).
- On a phone or emulator: device-only tests (demo robot) with `./gradlew connectedDirectDebugAndroidTest`, and the
  ones that need servers on the PC with `tools/pc_tests.sh`.
- The CI (`.github/workflows/`) runs all of the above that do not need a real phone.

## Commit messages

English, `Area: description` (for example `RViz: resizable image panels`).
