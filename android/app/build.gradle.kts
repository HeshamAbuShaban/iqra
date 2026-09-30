plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.iqra.quran"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.iqra.quran"
        minSdk = 24
        targetSdk = 36
        ndk {
            // arm64 only: the 32-bit phone is retired, and shipping a second
            // ABI costs ~26 MB of native libs on every download.
            //
            // -PemulatorAbi=true adds x86_64 for LOCAL testing on the x86_64 AVD,
            // which the arm64-only APK otherwise cannot install on. It is opt-in
            // and off by default, so the released artifact is unchanged.
            val emulatorAbi = providers.gradleProperty("emulatorAbi").orNull == "true"
            abiFilters += if (emulatorAbi) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
        }
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1.0"
    }
    signingConfigs {
        // A committed debug key so every CI build shares one signature.
        // Without it each run generates a fresh debug key, every update fails
        // with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only remedy is an
        // uninstall - which wipes app data and the user's settings.
        // DEBUG ONLY: this says nothing about, and does not affect, release
        // signing.
        create("sharedDebug") {
            storeFile = rootProject.file("iqra-debug.keystore")
            storePassword = "iqradbg"
            keyAlias = "iqra"
            keyPassword = "iqradbg"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("sharedDebug") }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += setOf("/META-INF/*") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
}
