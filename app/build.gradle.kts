plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    // App-owned CPython runtime; no external terminal application is required.
    id("com.chaquo.python")
}

// Android Studio requests this legacy model task during Gradle sync.
// AGP's built-in Kotlin support does not register it, so provide an empty fallback.
if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel")
}

android {
    namespace = "com.example.oreshik"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.example.oreshik"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Chaquopy's Python interpreter is a native component and
            // must be built per-ABI. arm64-v8a covers real devices;
            // x86_64 covers the standard emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    val releaseStorePath = project.findProperty("storeFile")?.toString()
    signingConfigs {
        create("release") {
            if (!releaseStorePath.isNullOrBlank()) {
                storeFile = file(releaseStorePath)
                storePassword = project.findProperty("storePassword")?.toString()
                keyAlias = project.findProperty("keyAlias")?.toString()
                keyPassword = project.findProperty("keyPassword")?.toString()
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // A supplied keystore signs production builds; otherwise create an installable
            // CI/test APK with the standard debug certificate.
            signingConfig = if (releaseStorePath.isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // Use the latest stable Android SDK platform available in standard CI images (API 36).
        disable += "OldTargetApi"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            optIn.addAll(
                "kotlin.RequiresOptIn",
                "kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        dataBinding = false
        buildConfig = true
    }

    // Toolpack tgz archives are already gzip-compressed — keep them stored
    // uncompressed inside the APK (avoids double compression and keeps
    // streamed-install fast). Bundled under src/main/assets/toolpacks/.
    // Database jars are zip archives served raw to the bundled JVM at runtime.
    androidResources {
        noCompress += listOf("tgz", "jar", "whl")
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/dependency-reduced-pom.xml",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

chaquopy {
    defaultConfig {
        val cachePython = file("${System.getProperty("user.home")}/.cache")
            .listFiles()
            ?.asSequence()
            ?.flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            ?.map { it.resolve("dependencies/python/python.exe") }
            ?.firstOrNull { it.isFile }
        val buildPythonPath = System.getenv("PYTHON")?.takeIf { file(it).isFile }
            ?: cachePython?.absolutePath
            ?: sequenceOf("/usr/bin/python3", "/usr/local/bin/python3")
                .firstOrNull { file(it).isFile }
            ?: error("Python 3 was not found. Set PYTHON to a Python 3.12 executable.")
        buildPython(buildPythonPath)
        // App-owned CPython runtime. Protocol libraries are bundled at build time;
        // the installed APK never depends on pip/ensurepip on the Android device.
        version = "3.12"
        pip {
            install("pip==25.3")
            install("paramiko==3.5.0")
            install("asyncssh==2.19.0")
            install("fabric==3.2.2")
            install("smbprotocol==1.15.0")
        }
        // pypyodbc is vendored as src/main/python/pypyodbc.py (single pure-Python file, bundled
        // by Chaquopy automatically) — its PyPI sdist is not pip-resolvable in the Chaquopy build.
        // Native pyodbc has no Android wheel in the configured Chaquopy repositories.
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Core Android
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.10.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.10.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // Material Components (AppCompat-based)
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Kotlin Coroutines & Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.5")
    add("ksp", "androidx.room:room-compiler:2.8.5")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")

    // On-device GGUF inference (CPU/NEON, arm64-v8a). Model weights are imported
    // by the user and stay in app-specific storage; no model is bundled in the APK.
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    // Media & Speech
    implementation("androidx.media:media:1.8.0")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // JSON
    implementation("com.google.code.gson:gson:2.14.0")

    // Markdown rendering for ChatGPT-style assistant message display
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")

    // Encryption
    implementation("androidx.security:security-crypto:1.1.0")

    // File handling
    implementation("com.github.bumptech.glide:glide:5.0.9")

    // Terminal
    implementation("org.jetbrains:annotations:26.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("io.mockk:mockk:1.14.11")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
