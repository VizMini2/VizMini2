// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

/*
 * libvizmini2-x11.so: calls XInitThreads() when loaded. Xlib requires it before any other call when
 * several threads use X11, and in VizMini2 AWT/Compose and the Filament thread (GLX) do. Java does not call it.
 * libX11 is opened with dlopen: the library does not depend on anything to link.
 * Build: tools/x11-threads/build.sh
 */
#include <dlfcn.h>

__attribute__((constructor)) static void vizmini2_x11_threads(void) {
    void *x11 = dlopen("libX11.so.6", RTLD_NOW | RTLD_GLOBAL);
    if (!x11) return;
    int (*init)(void) = (int (*)(void)) dlsym(x11, "XInitThreads");
    if (init) init();
}
