plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "lt.skeveldros"
    compileSdk = 34
    defaultConfig {
        applicationId = "lt.skeveldros"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.5"
    }
    signingConfigs {
        create("fixed") {
            storeFile = file("skeveldros.jks")
            storePassword = "skeveldros"
            keyAlias = "skeveldros"
            keyPassword = "skeveldros"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.ar:core:1.44.0")
}
