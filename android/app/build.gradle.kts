plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.terapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.terapp"
        minSdk = 26
        // Target SDK is explicitly set to 28 (Android 9.0 Pie), exactly as Termux does
        // (https://github.com/termux/termux-app).
        // Starting with Android 10 (API 29), Android SELinux enforces W^X policy on untrusted apps,
        // blocking execve() on files within app private data directories (/data/data/.../files).
        // Targeting API 28 retains the platform capability to execute native binaries, applets,
        // package manager scripts, and user shell scripts.
        targetSdk = 28
        versionCode = 3
        versionName = "1.1.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val releaseKeyFile = project.findProperty("RELEASE_STORE_FILE")?.toString()
        ?: System.getenv("RELEASE_STORE_FILE")
    val hasReleaseKey = releaseKeyFile != null && file(releaseKeyFile).exists()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKeyFile!!)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD")?.toString()
                    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS")?.toString()
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD")?.toString()
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += listOf("ExpiredTargetSdkVersion")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
}
