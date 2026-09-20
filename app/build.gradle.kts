plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.upscaler.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.upscaler.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
        // ONNX Runtime ships arm64-v8a, armeabi-v7a, x86, x86_64.
        // arm64-v8a only: every Android phone since ~2017. Dropping x86_64 (emulator) saves ~40 MB.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    val ksPath = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val hasReleaseKey = ksPath != null && file(ksPath).exists()

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                val ksFile = ksPath!!
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }


    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.useLegacyPackaging = false
    }

    // Model files are big; don't compress them so they can be memory-mapped quickly.
    androidResources { noCompress += listOf("onnx", "ort") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // AI inference engine: ONNX Runtime with NNAPI (GPU/NPU) support built-in
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    // Media3 for fast media metadata & transformer (hardware codecs)
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
