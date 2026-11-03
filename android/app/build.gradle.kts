plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// The automod example from ../../scripts/automod goes into assets/templates/automod.
val scriptsSrc = rootDir.resolve("../scripts")
val scriptAssetsDir = layout.buildDirectory.dir("generated/scriptAssets")
val bundleScripts by tasks.registering(Sync::class) {
    // Scripts are imported from phone storage; automod ships only as a template
    // that the "Добавить пример" button copies into the chosen folder.
    from(scriptsSrc.resolve("automod")) { include("automod.py", "README.md") }
    into(scriptAssetsDir.map { it.dir("templates/automod") })
}

android {
    namespace = "com.v1pach.telescript"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.v1pach.telescript"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
        getByName("main") {
            assets.directories.add(scriptAssetsDir.get().asFile.path)
        }
    }
}

tasks.named("preBuild") { dependsOn(bundleScripts) }

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
    debugImplementation(libs.androidx.compose.ui.tooling)
}
