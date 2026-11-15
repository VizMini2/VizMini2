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

rootProject.name = "VizMini2"
include(":app", ":core", ":desktopApp")
 