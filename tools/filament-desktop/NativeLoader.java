// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.google.android.filament;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Loads Filament's native libraries on the PC (VizMini2): from the -Dfilament.natives.dir folder if given, or
 * by extracting them from this jar's natives/&lt;platform&gt;/ (linux-x86_64, windows-x86_64) to the natives folder of the
 * -Dvizmini2.cache.dir cache (the app sets it: %LOCALAPPDATA%\VizMini2 Data\Cache on Windows), or ~/.cache/vizmini2/natives.
 * That way java.library.path is not needed.
 */
public final class NativeLoader {
    private NativeLoader() {}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    private static final boolean WINDOWS = OS.startsWith("windows");
    private static final boolean MAC = OS.startsWith("mac");

    /** The jar's folder for this system, e.g. linux-x86_64 or windows-x86_64. */
    static String platform() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String a = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : arch;
        return (WINDOWS ? "windows-" : MAC ? "macos-" : "linux-") + a;
    }

    public static synchronized void load(String name) {
        String file = WINDOWS ? name + ".dll" : MAC ? "lib" + name + ".dylib" : "lib" + name + ".so";
        String dir = System.getProperty("filament.natives.dir");
        if (dir != null && new File(dir, file).isFile()) { System.load(new File(dir, file).getAbsolutePath()); return; }
        String res = "/natives/" + platform() + "/" + file;
        try (InputStream in = NativeLoader.class.getResourceAsStream(res)) {
            if (in == null) { System.loadLibrary(name); return; }
            byte[] bytes = in.readAllBytes();
            String cache = System.getProperty("vizmini2.cache.dir");
            File base = cache != null ? new File(cache, "natives") : new File(System.getProperty("user.home"), ".cache/vizmini2/natives");
            File out = new File(base, Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "/" + file);
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
