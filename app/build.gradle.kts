plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableKeystorePath = providers.environmentVariable("CHK_KEYSTORE_PATH").orNull
val stableStorePassword = providers.environmentVariable("CHK_KEYSTORE_PASSWORD").orNull
val stableKeyAlias = providers.environmentVariable("CHK_KEY_ALIAS").orNull
val stableKeyPassword = providers.environmentVariable("CHK_KEY_PASSWORD").orNull
val stableSigningConfigured = listOf(
    stableKeystorePath,
    stableStorePassword,
    stableKeyAlias,
    stableKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.chk.binancebybit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chk.binancebybit"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.6.0"
    }

    signingConfigs {
        if (stableSigningConfigured) {
            create("stableRelease") {
                storeFile = rootProject.file(stableKeystorePath!!)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("stableRelease")
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
