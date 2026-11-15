// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.google.android.filament;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Loads Filament's native libraries on the PC (VizMini2): from the -Dfilament.natives.dir folder if given, or
 * by extracting them from this jar's natives/linux-x86_64/ to ~/.cache/vizmini2/natives. That way java.library.path is not needed.
 */
public final class NativeLoader {
    private NativeLoader() {}

    public static synchronized void load(String name) {
        String file = "lib" + name + ".so";
        String dir = System.getProperty("filament.natives.dir");
        if (dir != null && new File(dir, file).isFile()) { System.load(new File(dir, file).getAbsolutePath()); return; }
        String res = "/natives/linux-x86_64/" + file;
        try (InputStream in = NativeLoader.class.getResourceAsStream(res)) {
            if (in == null) { System.loadLibrary(name); return; }
            byte[] bytes = in.readAllBytes();
            File out = new File(System.getProperty("user.home"), ".cache/vizmini2/natives/" + Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "/" + file);
            if (!out.isFile() || out.length() != bytes.length) {
                out.getParentFile().mkdirs();
                File tmp = new File(out.getPath() + ".tmp");
                Files.write(tmp.toPath(), bytes);
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            System.load(out.getAbsolutePath());
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError("Filament: " + e);
        }
    }
}
