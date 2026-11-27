# Releasing the ROS 2 package

The repository is a ROS 2 package (`package.xml`, `CMakeLists.txt`, `scripts/vizmini2`). ROS's buildfarm turns it
into `ros-<distro>-vizmini2`, installed with `sudo apt install ros-<distro>-vizmini2` and started with
`ros2 run vizmini2 vizmini2`. It is the desktop app's jars run with the system's Java (`default-jdk`, 17 or newer),
for x86-64 only.

## Before each release

- `package.xml` has the version (`python3 tools/release/version.py check vX.Y.Z` checks it) and `CHANGELOG.rst` is
  made from the changelog (`python3 tools/release/version.py rst > CHANGELOG.rst`).
- CI's *ros-package* job builds the Debian package the way the buildfarm does, for Jazzy and Lyrical, and starts it.
  The same by hand:

  ```bash
  docker run --rm -v "$PWD":/src:ro -v /tmp/out:/out ros:jazzy-ros-base bash /src/tools/ros/build-deb.sh
  ```

## Once: rosdistro, the release repository and the first release

1. A PR to https://github.com/ros/rosdistro adding the `doc` and `source` entries, for naming review, to
   `<distro>/distribution.yaml` of each distro (Jazzy, Kilted, Lyrical, Rolling), in alphabetical order. Not Humble:
   Ubuntu 22.04's `default-jdk` is Java 11, and Gradle and the app need 17 or newer (the `.deb` covers Humble's users).

   ```yaml
     vizmini2:
       doc:
         type: git
         url: https://github.com/VizMini2/VizMini2.git
         version: main
       source:
         type: git
         url: https://github.com/VizMini2/VizMini2.git
         version: main
       status: developed
   ```

   Its description: see below.
2. Once it is merged, a release repository in ros2-gbp: an issue in https://github.com/ros2-gbp/ros2-gbp-github-org
   with the *New release team* template (team `vizmini2`, repository `vizmini2` with the link to its `source` entry
   in rosdistro, and the maintainers' GitHub accounts linked to the `<maintainer>` of `package.xml`).
3. Once `https://github.com/ros2-gbp/vizmini2-release` exists, the first release with bloom, for each distro:

   ```bash
   bloom-release --new-track --rosdistro jazzy --track jazzy vizmini2
   ```

   It asks for the release repository (`https://github.com/ros2-gbp/vizmini2-release.git`), the upstream repository
   (`https://github.com/VizMini2/VizMini2.git`), the version (`:{auto}`) and the tag (`v:{version}`), then opens the
   rosdistro PR that adds the `release` entry.
4. Only for Ubuntu on x86-64: a PR to https://github.com/ros2/ros_buildfarm_config adding it, for each distro, to
   `package_blacklist` in `<distro>/release-ubuntu-arm64-build.yaml` and to `package_ignore_list` in
   `<distro>/release-rhel-build.yaml` (RHEL's `java` key is not a JDK 17 or newer):

   ```yaml
   - vizmini2  # its JNI libraries (Fast DDS, Filament) are only built for x86-64
   ```

Later releases: `bloom-release --rosdistro jazzy vizmini2` (and the same for the other distros).

## rosdistro PR description

> Adds VizMini2 (https://github.com/VizMini2/VizMini2, Apache-2.0): a ROS 2 visualization and control app (3D view
> like RViz, dashboards, plots, teleop, topics, parameters, lifecycle, ros2_control, Nav2 and MoveIt tools, bags). It
> is the desktop build of the same app published on Google Play; it talks DDS directly (no bridge on the robot).
>
> - It is written in Kotlin and built with Gradle (the wrapper is in the repository), like rosjava's packages: the
>   only system dependency is `java` (`default-jdk`). Gradle downloads the app's libraries from Maven Central and
>   Google's Maven repository during the build.
> - The package installs the app's jars (about 55 MB: ProGuard drops the classes nothing uses) and a launcher:
>   `ros2 run vizmini2 vizmini2`. It has no compiled code of its own; the JNI libraries in the jars (Fast DDS,
>   Filament) are x86-64 only, so the package should be skipped on arm64 (CMake stops with a clear message there).
> - Tested with bloom + `debian/rules binary` in clean `ros:jazzy-ros-base` and `ros:lyrical-ros-base` containers
>   (CI job *ros-package*).
