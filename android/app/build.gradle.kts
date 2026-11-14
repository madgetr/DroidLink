plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.droidlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.droidlink"
        minSdk = 33          // Android 13+: modern BLE permissions and callbacks
        targetSdk = 35
        versionCode = 63
        versionName = "0.24.2"
    }

    buildTypes {
        release {
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
    buildFeatures {
        buildConfig = true
    }
}
