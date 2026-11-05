plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.avip.expense"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.avip.expense"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions sets VERSION_CODE for every build, so each release is newer
        // than the last and the app can update itself.
        val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Same signing key on every build, so new versions install over old ones.
    signingConfigs {
        create("avip") {
            storeFile = file("release.keystore")
            storePassword = "avipex2026"
            keyAlias = "avipexpense"
            keyPassword = "avipex2026"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("avip")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("avip")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // reads the odometer number from the photo, on the phone (model comes with Google Play services)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // GPS camera, same as AVIP GPSCam
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
