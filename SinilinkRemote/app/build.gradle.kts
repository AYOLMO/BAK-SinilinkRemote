plugins {
    id("com.android.application")
}

android {
    namespace = "com.sinilink.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sinilink.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "3.4"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
