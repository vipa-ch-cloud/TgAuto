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
        versionName = "1.9.3-term"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Terminal + AI CLI live in src/terminal. 1.9.2: nightly only. For 1.9.3 flip this to true (one edit).
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
        // Terminal + AI-CLI in both flavors since 1.9.3. libtermux.so (tools/pty) is built with 16 KB page
        // alignment; the userland (Termux packages, tools/termux_userland) is generated into build/userland.
        getByName("main").apply {
            java.srcDir("src/terminal/java")
            kotlin.srcDir("src/terminal/java")
            res.srcDir("src/terminal/res")
            jniLibs.srcDir("src/terminal/jniLibs")
            jniLibs.srcDir(layout.buildDirectory.dir("userland/jniLibs"))
            assets.srcDir(layout.buildDirectory.dir("userland/assets"))
        }
    }
    packaging {
        // The `claude` shim (jniLibs/*/libclaude.so, a shell script) must be extracted to
        // nativeLibraryDir: the only app location Android lets us execute files from.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libclaude.so"
        // our 16 KB-aligned libtermux.so replaces the 4 KB-aligned one inside the Termux AAR
        jniLibs.pickFirsts += "**/libtermux.so"
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

// Embedded Termux userland (bash, busybox, git, curl, python, node): downloaded from the official Termux
// repository, patched with patchelf and packed as jniLibs + an asset. Skip with -PnoUserland.
val fetchUserland = tasks.register<Exec>("fetchUserland") {
    val script = file("../../tools/termux_userland/build_userland.py")
    val out = layout.buildDirectory.dir("userland").get().asFile
    inputs.file(script)
    outputs.dir(out)
    commandLine("python3", "-I", script.absolutePath, out.absolutePath)
    onlyIf { !project.hasProperty("noUserland") }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchUserland) }
