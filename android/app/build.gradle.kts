import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// One code base for Android and iOS: what both run is in src/commonMain, the platform's own in
// src/androidMain and src/iosMain. The iPhone gets a static framework the Swift app in ios/ links.
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosArm64 {
        binaries.framework {
            baseName = "MoonMobileKit"
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.16.0")
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            // The app lock's fingerprint and face prompt; fragment pinned newer than biometric's own.
            implementation("androidx.biometric:biometric:1.1.0")
            implementation("androidx.fragment:fragment-ktx:1.8.8")
        }
    }
}

android {
    namespace = "app.moonmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.moonmobile"
        minSdk = 29
        targetSdk = 35
        // CI names each build: v1.0b, v1.01b, v1.02b… A local build carries VERSION as it is.
        versionCode = (System.getenv("MM_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("MM_VERSION_NAME") ?: rootProject.file("../VERSION").readText().trim()
        // The Rust bridge is built for 64-bit ARM only.
        ndk { abiFilters += "arm64-v8a" }
        // Where the app looks for its next version: a folder holding version.json and the APK,
        // filled by CI on the owner's server. Empty switches the in-app update off.
        val updateUrl = (System.getenv("MM_UPDATE_URL") ?: "").trim().let {
            if (it.isEmpty() || it.endsWith("/")) it else "$it/"
        }
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    // The release key is not in the repository. CI writes it from the repository's secrets and
    // passes its path and passwords in MM_KEYSTORE_FILE, MM_KEYSTORE_PASSWORD, MM_KEY_ALIAS and
    // MM_KEY_PASSWORD; every build signed with it installs over the previous one without wiping
    // the app's data. Without them (a local build, a fork) the APK is signed with the debug key.
    val keystorePath = System.getenv("MM_KEYSTORE_FILE").orEmpty()
    val releaseKey = keystorePath.isNotEmpty() && file(keystorePath).exists()
    signingConfigs {
        if (releaseKey) {
            create("shared") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MM_KEY_ALIAS")
                keyPassword = System.getenv("MM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (releaseKey) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKey) signingConfigs.getByName("shared") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}


// The APK is useless without the Rust bridge: fail the build rather than ship an app that
// crashes on its first screen. CI puts the library there with `cargo ndk` (see README).
val bridgeLibrary = layout.projectDirectory.file("src/androidMain/jniLibs/arm64-v8a/libmoonmobile.so")
tasks.named("preBuild") {
    doFirst {
        if (!bridgeLibrary.asFile.isFile) {
            throw GradleException(
                "libmoonmobile.so is missing: build it first with\n" +
                    "  cd rust && cargo ndk -t arm64-v8a --platform 29 " +
                    "-o ../android/app/src/androidMain/jniLibs build --release --locked --features android",
            )
        }
    }
}
