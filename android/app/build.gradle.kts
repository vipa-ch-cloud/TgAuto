plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.v1pach.telescript"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.v1pach.telescript"
        minSdk = 26
        targetSdk = 37
        versionCode = 21
        versionName = "1.9.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") { dimension = "channel" }
        create("nightly") {
            dimension = "channel"
            applicationIdSuffix = ".nightly"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded tool: signed with the debug key so the build stays
            // reproducible without shipping a private keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // The `claude` shim (jniLibs/*/libclaude.so, a shell script) must be extracted to
        // nativeLibraryDir: the only app location Android lets us execute files from.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libclaude.so"
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython(System.getenv("TELESCRIPT_BUILD_PYTHON")
            ?: "${System.getProperty("user.home")}/.local/share/uv/python/cpython-3.12.14-linux-x86_64-gnu/bin/python3.12")
        pip {
            // pyaes (a telethon dependency) is sdist-only on PyPI; a prebuilt
            // pure-Python wheel lives in app/wheels.
            options("--find-links", file("wheels").absolutePath)
            install("telethon")
            install("httpx")
            install("python-socks[asyncio]")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.zxing.core)
    // Terminal emulator + view from termux-app (Apache-2.0 parts, see docs/TERMINAL.md). Native libtermux.so does the PTY.
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
