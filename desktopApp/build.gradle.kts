// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Desktop VizMini2: the same logic (core) and the same screens (ui) as on Android.
plugins {
    id("org.jetbrains.dokka")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    sourceSets {
        // The shared screens (ui/src/main/kotlin): the same sources the Android app compiles.
        jvmMain.get().kotlin.srcDir("../ui/src/main/kotlin")
        jvmMain.get().kotlin.srcDir("../rviz3d/src/main/kotlin")
        // The app's assets (ROS interfaces, meshes...) on the classpath, under assets/ (see Platform.openAsset).
        jvmMain.get().resources.srcDir(layout.buildDirectory.dir("generated/assets"))
        jvmMain.get().resources.srcDir(layout.buildDirectory.dir("generated/firebase"))
        jvmMain.get().resources.srcDir(layout.buildDirectory.dir("generated/version"))
        jvmMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.desktop.currentOs)
            // Dispatchers.Main on the Swing thread (where Compose for Desktop runs).
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            // Profile QR codes (the same as Android).
            implementation("com.google.zxing:core:3.5.4")
            implementation(files("../tools/jros2-16k/desktop/jros2-desktop.jar"))
            // Filament for PC: the Android bindings compiled for Linux (tools/filament-desktop/build-*.sh).
            implementation(files("../tools/filament-desktop/filament-desktop.jar"))
            implementation("org.eclipse.zenoh:zenoh-kotlin:1.10.2")
            implementation("org.json:json:20240303")
            implementation("com.github.luben:zstd-jni:1.5.7-12")
        }
        // End-to-end tests against real ROS 2 servers (skipped unless VIZMINI2_E2E=1; see tools/e2e/run.sh).
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            // Compose UI tests of the shared screens (they draw them without a window).
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The end-to-end tests need the environment of tools/e2e/run.sh (or of the CI) and can take minutes.
    environment("VIZMINI2_E2E", System.getenv("VIZMINI2_E2E") ?: "")
    environment("VIZMINI2_E2E_DOMAIN", System.getenv("VIZMINI2_E2E_DOMAIN") ?: "77")
    // ConfigInteropTest.writeSamples: where to leave files exported for RViz, Foxglove and PlotJuggler (empty: none).
    environment("VIZMINI2_INTEROP_OUT", System.getenv("VIZMINI2_INTEROP_OUT") ?: "")
    environment("VIZMINI2_INTEROP_IN", System.getenv("VIZMINI2_INTEROP_IN") ?: "")
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    outputs.upToDateWhen { false }
}

val appVersion: String = providers.gradleProperty("vizmini2.version").get()

compose.desktop {
    application {
        mainClass = "com.juancams.rviz2mini.desktop.MainKt"
        // The X11 window class is set to "vizmini2" (so the desktop shows its name and logo): it needs this module open.
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "vizmini2"
            // The account (Firebase REST APIs and the local sign-in page).
            modules("java.net.http", "jdk.httpserver")
            packageVersion = appVersion
            description = "ROS 2 visualization and control"
            vendor = "Juan Carlos Manzanares Serrano"
            linux {
                menuGroup = "Development"
                appCategory = "Development"
                shortcut = true
                // The same maintainer as ros/vizmini2/package.xml.
                debMaintainer = "jc.manzanares.serrano@gmail.com"
                // tools/make_icons.sh (the logo, as on Android).
                iconFile.set(project.file("icons/vizmini2.png"))
            }
        }
    }
}

// apiKey and projectId from app/google-services.json -> firebase.json (the PC account uses the Firebase REST APIs).
// Without that file it is not generated and the desktop app works without an account.
val firebaseConfig by tasks.registering {
    val src = rootProject.file("app/google-services.json")
    val out = layout.buildDirectory.file("generated/firebase/firebase.json")
    inputs.files(src).optional()
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        if (!src.exists()) { f.delete(); return@doLast }
        val json = groovy.json.JsonSlurper().parse(src) as Map<*, *>
        val project = (json["project_info"] as Map<*, *>)["project_id"] as String
        val key = (((json["client"] as List<*>)[0] as Map<*, *>)["api_key"] as List<*>).let { (it[0] as Map<*, *>)["current_key"] as String }
        f.parentFile.mkdirs()
        f.writeText(groovy.json.JsonOutput.toJson(mapOf("apiKey" to key, "projectId" to project)))
    }
}

// The version for the window title (vizmini2.version in gradle.properties).
val versionResource by tasks.registering {
    val out = layout.buildDirectory.file("generated/version/vizmini2-version.properties")
    val v = appVersion
    inputs.property("version", v)
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs(); writeText("version=$v\n") } }
}

// Two fixes to the .deb as jpackage writes it (dpkg-deb unpacks and repacks it):
// - Its menu entry is called "vizmini2" and does not say which window is its own: it becomes "VizMini2" with
//   StartupWMClass=vizmini2 (the class the app gives its window), so the desktop shows the name and the logo in the
//   top bar and the dock.
// - Its dependencies are this machine's package names: since Ubuntu 24.04 some end in "t64" (libasound2t64...),
//   which Ubuntu 20.04 and 22.04 do not have; each one also accepts the old name.
// - Its install and remove scripts fail (and so does the installation) where xdg-desktop-menu finds no menu
//   (/etc/xdg/menus), as on a robot's computer without a desktop: then the entry is copied to
//   /usr/share/applications by hand (and removed the same way).
val fixDebDesktopEntry by tasks.registering {
    val entrySrc = "/opt/vizmini2/lib/vizmini2-vizmini2.desktop"
    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    doLast {
        val deb = debDir.get().asFile.listFiles { f -> f.name.endsWith(".deb") }?.maxByOrNull { it.lastModified() } ?: return@doLast
        val work = temporaryDir.resolve("deb").apply { deleteRecursively(); mkdirs() }
        fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).inheritIO().start()
            check(p.waitFor() == 0) { "failed: ${cmd.joinToString(" ")}" }
        }
        run("dpkg-deb", "-R", deb.absolutePath, work.absolutePath)
        val control = work.resolve("DEBIAN/control")
        control.writeText(control.readLines().joinToString("\n") { line ->
            if (!line.startsWith("Depends:")) line
            else "Depends: " + line.removePrefix("Depends:").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(", ") { d -> if (d.endsWith("t64") && '|' !in d) "$d | ${d.removeSuffix("t64")}" else d }
        } + "\n")
        for (script in listOf("postinst", "prerm")) {
            val f = work.resolve("DEBIAN/$script").takeIf { it.exists() } ?: continue
            f.writeText(f.readLines().joinToString("\n") { line ->
                val t = line.trim()
                when {
                    t.startsWith("xdg-desktop-menu install") -> "$t 2> /dev/null || { mkdir -p /usr/share/applications && cp $entrySrc /usr/share/applications/; }"
                    t.startsWith("xdg-desktop-menu uninstall") -> "$t 2> /dev/null || rm -f /usr/share/applications/${entrySrc.substringAfterLast('/')}"
                    else -> line
                }
            } + "\n")
        }
        val entry = work.walkTopDown().firstOrNull { it.name.endsWith(".desktop") && it.parentFile.name == "lib" } ?: return@doLast
        val lines = entry.readLines().filter { !it.startsWith("StartupWMClass=") && !it.startsWith("Keywords=") }
            .map { if (it.startsWith("Name=")) "Name=VizMini2" else if (it.startsWith("Categories=")) "Categories=Development;" else it }
        entry.writeText((lines + listOf("StartupWMClass=vizmini2", "Keywords=ROS;ROS2;robot;RViz;")).joinToString("\n") + "\n")
        run("dpkg-deb", "--root-owner-group", "--build", work.absolutePath, deb.absolutePath)
    }
}
tasks.matching { it.name == "packageDeb" }.configureEach { finalizedBy(fixDebDesktopEntry) }

// app/src/main/assets -> build/generated/assets/assets/ (without duplicating them in the repo).
val copyAssets by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/assets"))
    from("../app/src/main/assets") { into("assets") }
    from("icons/vizmini2.png")
    // Screenshots and videos of the tab catalog (the ones from Android's add screen).
    from("../app/src/main/res/drawable-nodpi") { include("shot_*.webp"); into("catalog") }
    from("../app/src/main/res/raw") { include("clip_*.mp4"); into("catalog") }
    from("../app/src/main/res/raw-es") { include("clip_*.mp4"); into("catalog/es") }
    // The strings: the app's strings.xml files (see core i18n/Strings.kt).
    from("../app/src/main/res") {
        include("values/strings*.xml", "values-*/strings*.xml")
        into("res")
    }
}
tasks.named("jvmProcessResources") { dependsOn(copyAssets, firebaseConfig, versionResource) }
