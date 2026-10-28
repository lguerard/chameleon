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
        // CI passes these from the git tag (see .github/workflows/build.yml).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 2
        versionName = (findProperty("versionName") as String?) ?: "1.0"
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
        // Minified release build, signed with the same stable key as before so it
        // installs over earlier builds.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("stable")
        }
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
