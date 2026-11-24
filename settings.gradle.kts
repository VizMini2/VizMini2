// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jros2-android 1.5.1 rebuilt for 16 KB pages (tools/jros2-16k/build.sh)
        maven { url = uri("${rootDir}/tools/jros2-16k/repo") }
        maven {
            url = uri("https://robotlabfiles.ihmc.us/repository/")
        }
        maven {
            url = uri("https://nexus.ihmc.us/repository/maven-releases/")
        }
    }
}

// The binaries built by this project's own scripts are not kept in git: each one in tools/prebuilt/prebuilt.txt is
// downloaded to its path when it is missing or out of date (the same as tools/prebuilt/fetch.sh, for scripts). One
// rebuilt here (different from the one last downloaded) is kept, with a warning; tools/prebuilt/publish.sh publishes it.
run {
    fun sha256(f: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    val stateDir = java.io.File(settingsDir, "tools/prebuilt/.state")
    java.io.File(settingsDir, "tools/prebuilt/prebuilt.txt").readLines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { line ->
            val (path, want, url) = line.split(Regex("\\s+"))
            val f = java.io.File(settingsDir, path)
            val state = java.io.File(stateDir, path.replace("/", "__") + ".sha256")
            if (f.isFile) {
                val have = sha256(f)
                if (have == want) {
                    if (!state.isFile || state.readText().trim() != want) { stateDir.mkdirs(); state.writeText("$want\n") }
                    return@forEach
                }
                if (have != (if (state.isFile) state.readText().trim() else "")) {
                    println("w: $path is not the one in tools/prebuilt/prebuilt.txt (rebuilt here?): kept. " +
                        "To publish it: tools/prebuilt/publish.sh $path")
                    return@forEach
                }
            }
            println("Downloading $path")
            f.parentFile.mkdirs()
            val part = java.io.File(f.path + ".part")
            try {
                val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            } catch (e: java.io.IOException) {
                part.delete()
                throw GradleException("Could not download $path from $url (offline?): $e", e)
            }
            val have = sha256(part)
            if (have != want) {
                part.delete()
                throw GradleException("$url has sha256 $have, not $want (tools/prebuilt/prebuilt.txt)")
            }
            if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
            stateDir.mkdirs()
            state.writeText("$want\n")
        }
}

rootProject.name = "VizMini2"
include(":app", ":core", ":desktopApp")
 