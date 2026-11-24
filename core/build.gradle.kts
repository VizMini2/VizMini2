// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

// Logic shared by all platforms (no UI): ROS, TF, dynamic messages, bags...
// For now with a single JVM target, used by both the Android app and the desktop one; iOS
// will add its own (see PLAN_PC_ROS2.md). Whatever doesn't depend on Java goes in commonMain.
plugins {
    id("org.jetbrains.dokka")
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    sourceSets {
        jvmMain.dependencies {
            // jros2 and Zenoh are provided by each app (Android: the AARs; desktop: the jars with their natives).
            compileOnly(files("../tools/jros2-16k/classes/jros2-classes.jar"))
            compileOnly("org.eclipse.zenoh:zenoh-kotlin:1.10.2")
            implementation("com.squareup.okhttp3:okhttp:4.12.1")
            // Lua scripts for computed series (pure Java).
            implementation("org.luaj:luaj-jse:3.0.1")
            // zstd for MCAP bags: each app provides its own (Android: the AAR; desktop: the jar with natives).
            compileOnly("com.github.luben:zstd-jni:1.5.7-28")
            // Android already ships org.json; the desktop adds it.
            compileOnly("org.json:json:20240303")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(files("../tools/jros2-16k/classes/jros2-classes.jar"))
            implementation("org.json:json:20240303")
            implementation("com.github.luben:zstd-jni:1.5.7-28")
        }
    }
}
