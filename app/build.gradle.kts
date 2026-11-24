// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper

plugins {
    id("org.jetbrains.dokka")
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("com.google.gms.google-services")
    // Crash reports (Firebase Crashlytics).
    id("com.google.firebase.crashlytics")
}

/**
 * zenoh-kotlin uses java.lang.ref.Cleaner, which on Android only exists since API 33: in the io.zenoh classes it is
 * replaced with com.juancams.rviz2mini.compat.Cleaner (same API: create, register and Cleanable.clean), which works
 * from the app's minSdk. Other classes are left untouched. It lives here and not in buildSrc because it needs to see the
 * Android plugin classes (and its ASM).
 */
abstract class ZenohCleanerRemap : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        ClassRemapper(nextClassVisitor, SimpleRemapper(mapOf(
            "java/lang/ref/Cleaner" to "com/juancams/rviz2mini/compat/Cleaner",
            "java/lang/ref/Cleaner\$Cleanable" to "com/juancams/rviz2mini/compat/Cleaner\$Cleanable",
        )))

    override fun isInstrumentable(classData: ClassData): Boolean = classData.className.startsWith("io.zenoh.")
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(ZenohCleanerRemap::class.java, InstrumentationScope.ALL) {}
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}

android {
    namespace = "com.juancams.rviz2mini"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.juancams.vizmini2"
        minSdk = 26
        targetSdk = 36
        // Still without version 1.0: 0.MINOR.PATCH (see assets/CHANGELOG.md).
        versionCode = 22
        versionName = "1.0.0"

        testInstrumentationRunner = "com.juancams.rviz2mini.TestRunner"

        // Empty by default (debug): no signature check. The release fills it in below.
        buildConfigField("String", "EXPECTED_CERT_SHA256", "\"\"")
    }

    // Release signing: keystore.properties at the root (outside the repo) with storeFile, storePassword,
    // keyAlias and keyPassword. Without it, the release is signed with the debug key (for testing only).
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) create("release") {
            val p = Properties().apply { keystoreFile.inputStream().use { load(it) } }
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    // SHA-256 of the certificate the release is signed with, so the app checks at startup
    // that it hasn't been repackaged and signed with another key (see access/Integrity.kt). It is read from the
    // keystore at build time; if there is no release keystore, it stays empty and the check is disabled.
    fun certSha256(name: String): String {
        val cfg = signingConfigs.findByName(name) ?: return ""
        val store = cfg.storeFile ?: return ""
        if (!store.exists()) return ""
        return try {
            val ks = KeyStore.getInstance(cfg.storeType ?: KeyStore.getDefaultType())
            store.inputStream().use { ks.load(it, cfg.storePassword?.toCharArray()) }
            val cert = ks.getCertificate(cfg.keyAlias) ?: return ""
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            logger.warn("No se pudo leer el certificado de firma '$name': ${e.message}")
            ""
        }
    }
    val releaseCertSha256 = certSha256("release")

    // One APK per architecture to distribute outside the store (the arm64-v8a one works for almost
    // any current phone and is less than half the size), plus the universal one with all of them.
    splits {
        abi {
            // Only for the APKs: the Google Play .aab already splits by architecture and doesn't allow this split.
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // The app refuses to start if the signature doesn't match (repackaging check).
            buildConfigField("String", "EXPECTED_CERT_SHA256", "\"$releaseCertSha256\"")
            // Crashlytics uploads R8's mapping after each release build; -Pvizmini2.uploadMapping=false skips it
            // (the release workflow: the mapping is kept as a workflow artifact to upload by hand).
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = providers.gradleProperty("vizmini2.uploadMapping").orNull != "false"
            }
        }
    }
    // Screens shared with the desktop (Compose): the same sources in both apps.
    sourceSets.getByName("main").kotlin.srcDir("../ui/src/main/kotlin")
    sourceSets.getByName("main").kotlin.srcDir("../rviz3d/src/main/kotlin")
    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true  // play_store, different in each flavor (productFlavors)
    }

    // Two flavors from the same code (same package and signature, so one can update to the other):
    //  - direct: the APK distributed outside the store; Home has "Support the project" (GitHub Sponsors).
    //  - play:   the Google Play one (.aab); instead, a card with the GitHub profile, because Play
    //            does not allow asking for donations with an external payment.
    flavorDimensions += "store"
    productFlavors {
        create("direct") {
            dimension = "store"
            resValue("bool", "play_store", "false")
        }
        create("play") {
            dimension = "store"
            resValue("bool", "play_store", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // --- PACKAGING BLOCK HARDENED AGAINST META-INF ---
    packaging {
        jniLibs {
            // The jros2 and Filament .so files are 16 KB aligned: they are kept uncompressed and aligned in the APK.
            useLegacyPackaging = false
        }
        resources {
            // Without the leading slash to ensure compatibility in the Kotlin DSL
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/LICENSE.md" // <-- THE ONE THAT WAS MISSING
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/NOTICE.md"  // Just in case
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    // PlotFragment saves CSVs with it (it used to come along with AppCompat).
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation(libs.material)
    // "nio" variant: jros2 uses java.nio.file.Path.of (API 33); without it, it doesn't connect on Android 8–12.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // jros2 1.5.1 with the native libraries rebuilt from the fork (16 KB alignment, ROS graph
    // discovery and sample identities for services; see tools/jros2-16k/).
    implementation("us.ihmc:jros2-android:1.5.1-16k-graph")
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:filamat-android:1.75.1")
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-auth")
    // Sign in with Google (Android account picker, Credential Manager).
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-crashlytics")
    // Also crashes inside native code (Fast DDS, Filament).
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    // WebSocket connection with foxglove_bridge (instead of DDS).
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    // Lua scripts for Plot's computed series; pure Java.
    implementation("org.luaj:luaj-jse:3.0.1")
    // Zenoh client to talk to robots using rmw_zenoh_cpp (same version as the Lyrical router).
    // Declares minSdk 30: the native part supports 21+ and the manifest forces it (overrideLibrary).
    implementation("org.eclipse.zenoh:zenoh-kotlin-android:1.10.2")
    // Profile QR codes: generate (ZXing, pure Java) and scan (Google Play Services scanner, no camera permission).
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // zstd-compressed MCAP bags (the AAR .so files are already 16 KB aligned).
    implementation("com.github.luben:zstd-jni:1.5.7-28@aar")
    // In JVM tests, the version with the desktop natives.
    testImplementation("com.github.luben:zstd-jni:1.5.7-28")
    testImplementation(libs.junit)
    // org.json on Android is a stub in JVM tests: we use the real implementation.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
// Dokka (./gradlew dokkaGenerate) does not see the sources of an Android module with AGP 9's built-in Kotlin, as there is
// no kotlin-android plugin to read them from: without this, the "app" part of the API reference is empty.
dokka {
    dokkaSourceSets.register("main") {
        analysisPlatform.set(org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform.AndroidJVM)
        sourceRoots.from("src/main/java")
        classpath.from(androidComponents.sdkComponents.bootClasspath)
        classpath.from(provider {
            configurations.getByName("directDebugCompileClasspath").incoming.artifactView {
                attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
            }.files
        })
    }
}
