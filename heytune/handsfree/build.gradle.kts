plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.heytune.handsfree"
    compileSdk = 37
    // Must match the NDK installed in CI (see .github/workflows/android.yml).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Prebuilt Cactus engine ships arm64 only (arm64-v8a).
                // Other ABIs build without the .so; NeedleBridge reports
                // nativeAvailable=false and VoiceService refuses to start there.
                abiFilters += listOf("arm64-v8a")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(libs.media3.session)
    implementation("androidx.core:core:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
