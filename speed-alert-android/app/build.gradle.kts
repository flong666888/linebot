plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moto.speedalert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moto.speedalert"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Fixed key kept in the repo so every build can update the installed app
        // without uninstalling. Personal sideload app only - not for Play Store.
        create("shared") {
            storeFile = file("../keystore/speedalert.keystore")
            storePassword = "speedalert"
            keyAlias = "speedalert"
            keyPassword = "speedalert"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
