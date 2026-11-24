Building and testing
====================

You need a JDK 21, the Android SDK (for the Android app) and, for the tests against ROS, a ROS 2
installation on the PC. The first build needs internet: Gradle downloads its dependencies and the
prebuilt native libraries. No sudo or Docker is needed for any script.

Building
--------

.. code-block:: bash

   ./gradlew installDirectDebug             # the Android app on a connected phone (debug)
   ./gradlew :desktopApp:run                # the desktop app from the sources
   ./gradlew :desktopApp:packageDeb         # the .deb (desktopApp/build/compose/binaries/main/deb)
   ./gradlew dokkaGenerate                  # API reference in build/dokka/html
   docs/build.sh                            # this manual in docs/_build/site

On Windows (JDK 21), ``./gradlew :desktopApp:run`` and ``./gradlew :desktopApp:packageMsi`` (the
``.msi`` in ``desktopApp/build/compose/binaries/main/msi``, finished by
``tools/desktop-installer/msi-postprocess.ps1``). Each package can only be built on its own system,
and it carries only that system's native libraries.

The Android app has two flavors: *direct* (APK files) and *play* (Google Play). Release builds
need a signing key in ``keystore.properties`` (see ``keystore.properties.example``).

The native libraries (Fast DDS for Android and the desktop, Filament for the desktop) come prebuilt:
they are not in git, and the first build downloads them from the repository's *prebuilt* release
(``tools/prebuilt/prebuilt.txt`` lists them with their SHA-256). To rebuild them:

* the Linux ones (after changing Fast DDS or Filament): ``tools/compat/build-desktop-natives.sh``
  builds them inside an Ubuntu 20.04 root, and ``tools/compat/check-glibc.sh`` checks they still
  run on 20.04;
* the Windows ones: the *Windows natives* workflow (``.github/workflows/windows-natives.yml``)
  builds them, and ``tools/desktop-natives/add-windows.sh`` puts them into the same jars;
* jros2 for Android: ``tools/jros2-16k/build.sh``.

A file rebuilt locally is kept (the build warns that it differs). Then ``tools/prebuilt/publish.sh``
uploads the changed files to the *prebuilt* release and updates ``prebuilt.txt``, which is what you
commit. Never delete old files from that release: older commits need them. See
``tools/prebuilt/README.md``.

Tests
-----

.. list-table::
   :header-rows: 1
   :widths: 45 55

   * - Command
     - What it tests
   * - ``./gradlew :core:jvmTest testDirectDebugUnitTest lintDirectDebug``
     - Unit tests of the shared logic and the Android app, and Android lint.
   * - ``tools/e2e/run.sh``
     - The desktop app end to end against real ROS 2 servers on this PC (fake robot, Nav2,
       MoveIt, ros2_control, video, maps, interactive markers…): it starts them, runs the tests
       and stops them.
   * - ``tools/e2e/android_device_tests.sh``
     - Device tests on a phone or an emulator with the demo robot.
   * - ``tools/pc_tests.sh -b``
     - The phone against the servers on the PC (same network). It saves and restores the
       phone's tabs.
   * - ``tools/compat/try-desktop.sh 22.04 [file.deb]``
     - The app (or installing the ``.deb``) on another Ubuntu release.
   * - ``python3 tools/license_headers.py --check`` and ``python3 tools/check_english_comments.py``
     - Every source file has the license header and English comments.

The test servers in ``tools/`` are also handy by hand: ``python3 tools/fake_robot.py`` publishes
almost every message type the app shows, and ``vizmini2 --showcase`` adds a dashboard made for it.
For the NavMap display, ``tools/navmap_demo.py`` publishes a small two-floor building with live layer
updates and ``tools/navmap_file_pub.py`` any ``.navmap`` file (EasyNavigation's playgrounds have real
ones, such as a multi-storey car park); open them with ``vizmini2 --import tools/navmap_demo.rviz``.
Both need ``navmap_ros_interfaces``: ``source "$(tools/e2e/build_msgs.sh)"`` builds a copy of it.
