#!/bin/bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
# Filament Java bindings for Linux x86_64: the same ones as Android (filament-android and filamat-android),
# compiled against the desktop Filament that build.sh leaves in install/. Without the Android-only files.
# Output: $HERE/filament-desktop.jar, with the classes and the native libraries in natives/linux-x86_64/.
# The classes load the natives with NativeLoader (from -Dfilament.natives.dir or from inside the jar), not with loadLibrary.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
B="${FILAMENT_WORK:-$HOME/.cache/vizmini2-filament}"
export PATH="$B/tools/bin:$PATH"
export LD_LIBRARY_PATH="$B/tools/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
F="$B/filament/android"
I="$B/install"
LIB="$I/lib/x86_64"
[ -d "$LIB" ] || LIB="$I/lib"
O="$B/jni"
rm -rf "$O"; mkdir -p "$O/obj" "$O/classes"

CXXFLAGS=(-std=c++20 -O2 -fPIC -stdlib=libc++ -fno-rtti -I"$I/include" -I"$F" -I"$F/common" -I"$F/filament-android/src/main/cpp"
  -I"$B/filament/libs/utils/include" -I"$B/filament/filament/backend/include" -I"$B/filament/libs/math/include"
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -DFILAMENT_SUPPORTS_OPENGL=1)
LDFLAGS=(-shared -stdlib=libc++ -fuse-ld=lld -L"$B/tools/lib/x86_64-unknown-linux-gnu" -Wl,-rpath,'$ORIGIN' -static-libstdc++)

echo "== filament-jni"
objs=()
for f in "$F"/filament-android/src/main/cpp/*.cpp "$F"/common/*.cpp "$B/filament/filament/backend/src/VirtualMachineEnv.cpp"; do
  case "$(basename "$f")" in FramePacerAndroidJni.cpp) continue;; esac
  o="$O/obj/fil_$(basename "$f" .cpp).o"
  clang++ "${CXXFLAGS[@]}" -c "$f" -o "$o"
  objs+=("$o")
done
clang++ "${LDFLAGS[@]}" -o "$O/libfilament-jni.so" "${objs[@]}" -Wl,--start-group "$LIB"/*.a -Wl,--end-group \
  -Wl,-Bstatic -lc++ -lc++abi -Wl,-Bdynamic -lGL -lX11 -ldl -lpthread -lm

echo "== filamat-jni"
mobjs=()
for f in "$F"/filamat-android/src/main/cpp/*.cpp "$F"/common/NioUtils.cpp; do
  o="$O/obj/mat_$(basename "$f" .cpp).o"
  clang++ "${CXXFLAGS[@]}" -c "$f" -o "$o"
  mobjs+=("$o")
done
clang++ "${LDFLAGS[@]}" -o "$O/libfilamat-jni.so" "${mobjs[@]}" -Wl,--start-group "$LIB"/*.a -Wl,--end-group \
  -Wl,-Bstatic -lc++ -lc++abi -Wl,-Bdynamic -ldl -lpthread -lm

mkdir -p "$O/classes/natives/linux-x86_64"
llvm-strip --strip-unneeded -o "$O/classes/natives/linux-x86_64/libfilament-jni.so" "$O/libfilament-jni.so" 2>/dev/null || strip -o "$O/classes/natives/linux-x86_64/libfilament-jni.so" "$O/libfilament-jni.so"
llvm-strip --strip-unneeded -o "$O/classes/natives/linux-x86_64/libfilamat-jni.so" "$O/libfilamat-jni.so" 2>/dev/null || strip -o "$O/classes/natives/linux-x86_64/libfilamat-jni.so" "$O/libfilamat-jni.so"
# Without a JDK here (the compatibility build in Ubuntu 20.04, tools/compat/build-desktop-natives.sh, only has the JNI
# headers): only the native libraries, which that script puts into the existing jar.
if [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "== natives only (no javac in $JAVA_HOME): $O/classes/natives/linux-x86_64"
  exit 0
fi

echo "== java"
find "$F/filament-android/src/main/java" "$F/filamat-android/src/main/java" -name '*.java' \
  | grep -vE '/(AndroidPlatform21|AndroidPlatform|DisplayHelper|TextureHelper|UiHelper|ChoreographerHelper|FramePacer)\.java$' > "$O/sources.txt"
# androidx annotations: only for compiling (they do not go in the jar; they have CLASS retention).
mkdir -p "$O/stubs/androidx/annotation" "$O/stubclasses"
for a in NonNull Nullable RequiresApi; do printf 'package androidx.annotation;\npublic @interface %s { int value() default 0; int api() default 0; }\n' "$a" > "$O/stubs/androidx/annotation/$a.java"; done
printf 'package androidx.annotation;\npublic @interface IntRange { long from() default Long.MIN_VALUE; long to() default Long.MAX_VALUE; }\n' > "$O/stubs/androidx/annotation/IntRange.java"
printf 'package androidx.annotation;\npublic @interface Size { long value() default -1; long min() default Long.MIN_VALUE; long max() default Long.MAX_VALUE; long multiple() default 1; }\n' > "$O/stubs/androidx/annotation/Size.java"
"$JAVA_HOME/bin/javac" --release 17 -nowarn -d "$O/stubclasses" "$O"/stubs/androidx/annotation/*.java
# System.loadLibrary -> NativeLoader.load (on the PC java.library.path does not need touching).
mkdir -p "$O/patched"
while read -r f; do
  if grep -q 'System.loadLibrary' "$f"; then
    p="$O/patched/$(basename "$f")"; sed 's/System\.loadLibrary(/com.google.android.filament.NativeLoader.load(/' "$f" > "$p"; echo "$p"
  else echo "$f"; fi
done < "$O/sources.txt" > "$O/sources2.txt"
echo "$HERE/NativeLoader.java" >> "$O/sources2.txt"
"$JAVA_HOME/bin/javac" --release 17 -nowarn -cp "$O/stubclasses" -d "$O/classes" @"$O/sources2.txt"
# The other systems' natives already in the jar (Windows: tools/desktop-natives/add-windows.sh) are kept.
if [ -f "$HERE/filament-desktop.jar" ]; then
  ( cd "$O/classes" && unzip -q -n "$HERE/filament-desktop.jar" 'natives/*' -x 'natives/linux-x86_64/*' ) || true
fi
"$JAVA_HOME/bin/jar" cf "$HERE/filament-desktop.jar" -C "$O/classes" .
echo "== listo"
ls -la "$HERE/filament-desktop.jar"
