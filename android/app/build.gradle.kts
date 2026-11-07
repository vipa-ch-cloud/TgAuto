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
        versionCode = 23
        versionName = "1.9.3"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Terminal + AI CLI live in src/terminal. 1.9.2: nightly only. For 1.9.3 flip this to true (one edit).
    val terminalInStable = false
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
    sourceSets {
        val flavors = if (terminalInStable) listOf("nightly", "stable") else listOf("nightly")
        for (f in flavors) getByName(f).apply {
            java.srcDir("src/terminal/java")
            kotlin.srcDir("src/terminal/java")
            res.srcDir("src/terminal/res")
            manifest.srcFile("src/terminal/AndroidManifest.xml")
        }
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
    // nightly only in 1.9.2 (terminalInStable above; for 1.9.3 use implementation(...) here)
    "nightlyImplementation"(libs.termux.terminal.emulator)
    "nightlyImplementation"(libs.termux.terminal.view)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
