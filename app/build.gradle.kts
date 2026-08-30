import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingPropsPath = providers.environmentVariable("CHK_SIGNING_PROPERTIES_PATH").orNull
val signingProps = Properties().apply {
    if (!signingPropsPath.isNullOrBlank()) {
        rootProject.file(signingPropsPath).inputStream().use { load(it) }
    }
}

val stableKeystorePath = signingProps.getProperty("keystorePath")
    ?: providers.environmentVariable("CHK_KEYSTORE_PATH").orNull
val stableStorePassword = signingProps.getProperty("storePassword")
    ?: providers.environmentVariable("CHK_KEYSTORE_PASSWORD").orNull
val stableKeyAlias = signingProps.getProperty("keyAlias")
    ?: providers.environmentVariable("CHK_KEY_ALIAS").orNull
val stableKeyPassword = signingProps.getProperty("keyPassword")
    ?: providers.environmentVariable("CHK_KEY_PASSWORD").orNull
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
        versionCode = 30
        versionName = "0.9.5"
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

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
