// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

// The desktop's stand-in for Filament's android/filament-android/src/main/cpp/nativewindow/Android.cpp, which turns an
// Android Surface into a native window. VizMini2's desktop renders with a headless swap chain and reads the pixels back
// (RvizDesktopScreen), so no Surface is ever passed: these only satisfy the linker (a Windows DLL cannot leave them
// undefined, as a Linux .so does).

#include <jni.h>

extern "C" {

void* getNativeWindow(JNIEnv*, jclass, jobject) {
    return nullptr;
}

jlong createNativeSurface(jint, jint) {
    return 0;
}

void destroyNativeSurface(jlong) {
}

}
