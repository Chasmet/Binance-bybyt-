plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chk.binancebybit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chk.binancebybit"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.4"
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
}
