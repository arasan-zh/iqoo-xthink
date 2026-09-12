plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "in.arasan.xthink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "in.arasan.xthink"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The bundled face detector ships a ~9 MB native library per ABI.
            // Every phone since about 2017 is arm64, the iQOO 15 included, and
            // this Mac's emulators are arm64 too. Shipping the other three adds
            // ~25 MB to a download judges make on venue Wi-Fi.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Checked-in public Android debug key. Keeps every CI build signed with
        // the same key so teammates can update-install instead of uninstalling.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":guidance"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Bundled, not Play-Services-backed: the APK is sideloaded at a venue and
    // must detect faces the instant it installs, with no model download.
    implementation(libs.mlkit.face.detection)
    // Bundled for the same reason as the face model: it must work on first launch, offline.
    implementation(libs.mlkit.objects)
    // QR code for the install link. Pure Java, generates a bit matrix; Compose draws it.
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
