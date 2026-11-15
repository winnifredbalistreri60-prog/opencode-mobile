import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.dredd20.opencode"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.dredd20.opencode"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // Only arm64 and x86_64 have an official OpenCode binary. The APK still
        // installs on armeabi-v7a (no abiFilters) and shows a remote-only message.
        ndk {
            // Intentionally empty: packaging ships arm64-v8a + x86_64 jniLibs only.
        }
    }

    signingConfigs {
        // Same debug cert as 0.1.0/0.1.1 (SHA-256 65b96eb0…2a90). The keystore is
        // NOT in git: set debug.keystore in local.properties or OPENCODE_DEBUG_KEYSTORE.
        getByName("debug") {
            storeFile = debugKeystoreFile()
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // No signingConfig: this tree is not used to ship a release APK.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.usb.serial)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}

// Resolve the 0.1.0 debug keystore without keeping it in the repository.
fun debugKeystoreFile(): File {
    val props = Properties()
    val lp = rootProject.file("local.properties")
    if (lp.isFile) lp.inputStream().use { props.load(it) }
    val raw = sequenceOf(
        props.getProperty("debug.keystore"),
        System.getenv("OPENCODE_DEBUG_KEYSTORE"),
        "/workspace/opencode-android-keys/debug-v0.1.keystore",
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.first()
    val f = File(raw)
    if (!f.isFile) {
        throw GradleException(
            "Debug keystore not found at $f. Copy debug-v0.1.keystore and set debug.keystore in local.properties or OPENCODE_DEBUG_KEYSTORE.",
        )
    }
    return f
}
