import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Optional release signing: android/keystore.properties (git-ignored) with
// storeFile, storePassword, keyAlias, keyPassword. Without it the release APK is unsigned.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "de.heineke.myvrnlite"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.heineke.myvrnlite"
        // Android 5.0. Everything below is chosen for it: framework views instead of
        // AndroidX/Compose, OkHttp 4.12, and bundled Let's Encrypt roots (see net/Tls.kt).
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
