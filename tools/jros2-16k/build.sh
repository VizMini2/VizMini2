#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Rebuilds the native libraries of jros2-android 1.5.1 with 16 KB pages and publishes the resulting AAR
# as us.ihmc:jros2-android:1.5.1-16k in tools/jros2-16k/repo (local Maven repository).
#
# Why: the official AAR ships libfastdds/libfastcdr/libjnifastddsjava aligned to 4 KB. On phones with
# 16 KB pages (Android 15+) the app does not start, and Google Play requires 16 KB since November 2025.
#
# Requirements: NDK r28 or later (export ANDROID_NDK=...), an Android SDK platform, JDK 17+, python3,
# cmake, ninja/make, git, curl, readelf, nm and zip.
# Usage: ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 tools/jros2-16k/build.sh [abi ...]
set -euo pipefail

# Fork VizMini2/jros2 (develop branch): 0c41e6f of ihmcrobotics/jros2 (same native/JNI code as 1.5.1)
# plus our own changes:
# - fab8158: 16 KB patch in scripts/cppbuild.bash, the ROS graph (endpoints, names and IPs of the
#   participants), sample identities to match service responses, unicast peers and the
#   Discovery Server. The new native functions are in the fastddsjavaGraph class, which lives in the app (core).
# - 73445bb: Java classes without APIs that Android lacks at minSdk (FloatBuffer.put(int, FloatBuffer, int, int) is
#   from API 35: without the patch LaserScan, PointCloud2 and maps did not arrive on Android 8-14).
# - 6e2df4a: service replies with their request's related sample identity, whether a writer is matched with a reader,
#   and a reader's GUID.
# The .so files are built here from source; the Java classes the fork changes are compiled against Android and
# replace the ones in the official AAR.
# JROS2_REPO and JROS2_COMMIT can be changed (e.g. a local copy of the fork and a commit not yet pushed).
JROS2_REPO="${JROS2_REPO:-https://github.com/VizMini2/jros2}"
JROS2_COMMIT="${JROS2_COMMIT:-6e2df4ad1452865210b96dea2aeb6dd0ec2fe5d5}"
UPSTREAM_COMMIT=0c41e6f
MIN_SDK=26
VERSION=1.5.1
OUT_VERSION=1.5.1-16k-graph
ABIS=("${@:-arm64-v8a armeabi-v7a x86_64}")
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HERE/work}"
GRAPH_JAVA="$HERE/../../core/src/jvmMain/java/us/ihmc/fastddsjava/natives/fastddsjavaGraph.java"
[ -f "$GRAPH_JAVA" ] || { echo "Missing $GRAPH_JAVA"; exit 1; }
: "${ANDROID_NDK:?Define ANDROID_NDK (NDK r28+)}"
SDK="${ANDROID_HOME:-$(cd "$ANDROID_NDK/../.." && pwd)}"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2> /dev/null | sort -V | tail -1)"
[ -f "$PLATFORM/core-for-system-modules.jar" ] || { echo "Falta una plataforma de Android en $SDK/platforms (sdkmanager \"platforms;android-36\")"; exit 1; }

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d jros2 ]; then
  git clone --filter=blob:none "$JROS2_REPO" jros2
fi
cd jros2
git remote set-url origin "$JROS2_REPO"
git fetch -q origin
# Copy only for building: whatever the previous build left (the jniLibs .so files) is discarded.
git reset -q --hard
git checkout -q "$JROS2_COMMIT"

grep -q "max-page-size=16384" scripts/cppbuild.bash || { echo "cppbuild.bash no lleva el parche de 16 KB (¿commit equivocado?)"; exit 1; }

for ABI in ${ABIS[@]}; do
  echo "=== Compilando $ABI"
  # Clean environment: if a ROS 2 is sourced, foonathan_memory_vendor finds the system one (x86)
  # and does not build the Android one.
  env -i HOME="$HOME" PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 ANDROID_NDK="$ANDROID_NDK" \
    ANDROID_COMPILE=1 ANDROID_ABI="$ABI" ANDROID_API_LEVEL=26 bash -c 'rm -rf cppbuild && bash scripts/cppbuild.bash' > "$WORK/build-$ABI.log" 2>&1 \
    || { echo "Falló $ABI (ver $WORK/build-$ABI.log)"; exit 1; }
  for f in android/src/main/jniLibs/$ABI/libfastdds.so android/src/main/jniLibs/$ABI/libfastcdr.so android/src/main/jniLibs/$ABI/libjnifastddsjava.so; do
    align=$(readelf -lW "$f" | awk '/LOAD/ {print $NF}' | sort -u)
    [ "$align" = "0x4000" ] || { echo "$f no está alineada a 16 KB ($align)"; exit 1; }
  done
  # Every native method of fastddsjavaGraph (the app's copy) must be in the .so: if one is missing,
  # the app fails with UnsatisfiedLinkError when using it.
  syms=$(nm -D --defined-only "android/src/main/jniLibs/$ABI/libjnifastddsjava.so" | awk '{print $3}')
  for m in $(grep -oP 'native\s+\S+\s+\K\w+(?=\()' "$GRAPH_JAVA"); do
    grep -qx "Java_us_ihmc_fastddsjava_natives_fastddsjavaGraph_$m" <<< "$syms" \
      || { echo "A libjnifastddsjava.so ($ABI) le falta fastddsjavaGraph.$m"; exit 1; }
  done
done

# Repackage the official AAR with the new .so files.
cd "$WORK"
rm -rf aar && mkdir aar
curl -sf -o official.aar "https://robotlabfiles.ihmc.us/repository/us/ihmc/jros2-android/$VERSION/jros2-android-$VERSION.aar"
(cd aar && unzip -q ../official.aar)
for ABI in ${ABIS[@]}; do
  cp -f jros2/android/src/main/jniLibs/$ABI/*.so "aar/jni/$ABI/"
done

# Java classes the fork changes relative to the official jros2 (except fastddsjavaGraph, which the app has). They are
# compiled against the Android classes, as AGP does (against a JDK, FloatBuffer.position(int) would return
# FloatBuffer, which Android does not have), and replace the ones in the official classes.jar.
JAVA_PATCHES=$(cd jros2 && git diff --name-only "$UPSTREAM_COMMIT" "$JROS2_COMMIT" -- 'src/main/java/*.java' | grep -v '/fastddsjavaGraph\.java$' || true)
if [ -n "$JAVA_PATCHES" ]; then
  echo "=== Clases Java del fork"
  # Java image with the Android classes (the same as AGP's jdkImage).
  JV=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -1)
  rm -rf android-jdk && mkdir -p android-jdk/classes
  (cd android-jdk && unzip -q "$PLATFORM/core-for-system-modules.jar" -d classes \
    && { echo "module java.base {"; (cd classes && find . -name '*.class' -printf '%h\n' | sed 's|^\./||; s|/|.|g' | sort -u | sed 's/.*/  exports &;/'); echo "}"; } > module-info.java \
    && javac --system=none --patch-module java.base=classes -d classes module-info.java \
    && jmod create --module-version "$JV" --target-platform linux-amd64 --class-path classes java.base.jmod \
    && jlink --module-path java.base.jmod --add-modules java.base --output image --disable-plugin system-modules \
    && cp "$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/lib/jrt-fs.jar" image/lib/)
  rm -rf patched && mkdir patched
  (cd jros2 && javac --system "$WORK/android-jdk/image" -source 17 -target 17 -nowarn -cp "$WORK/aar/classes.jar" -d "$WORK/patched" $JAVA_PATCHES)
  (cd patched && zip -q -r "$WORK/aar/classes.jar" .)
fi

# No class may use Java APIs that Android lacks at minSdk: they would fail with NoSuchMethodError.
echo "=== APIs de Android (minSdk $MIN_SDK)"
rm -rf classes-check && mkdir classes-check && (cd classes-check && unzip -q ../aar/classes.jar)
python3 "$HERE/check_android_api.py" classes-check "$PLATFORM/data/api-versions.xml" "$MIN_SDK" \
  || { echo "jros2 usa APIs que no hay en Android $MIN_SDK (arriba): hay que parchearlas en el fork"; exit 1; }
DEST="$HERE/repo/us/ihmc/jros2-android/$OUT_VERSION"
mkdir -p "$DEST"
(cd aar && rm -f "$DEST/jros2-android-$OUT_VERSION.aar" && zip -q -r -X "$DEST/jros2-android-$OUT_VERSION.aar" .)
cat > "$DEST/jros2-android-$OUT_VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>us.ihmc</groupId>
  <artifactId>jros2-android</artifactId>
  <version>$OUT_VERSION</version>
  <packaging>aar</packaging>
</project>
POM
echo "Publicado en $DEST"
