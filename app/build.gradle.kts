plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.guerard.palettewall"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.guerard.palettewall"
        // MediaStore.MediaColumns.IS_FAVORITE needs API 30
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // A fixed keystore committed to the repo, so every CI build is signed
        // the same way and can be installed over the previous one.
        create("stable") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
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
