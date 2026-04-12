import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read credentials from local.properties (never committed to git)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
fun localProp(key: String) = localProps.getProperty(key, "")

android {
    namespace = "com.omi4wos.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omi4wos"
        minSdk = 30
        targetSdk = 34
        versionCode = 12
        versionName = "1.12.0"

        // Standalone Omi credentials — set in local.properties:
        //   OMI_FIREBASE_WEB_API_KEY=AIzaSy...
        //   OMI_FIREBASE_TOKEN=eyJ...
        //   OMI_FIREBASE_REFRESH_TOKEN=AMf...
        buildConfigField("String", "OMI_FIREBASE_WEB_API_KEY",  "\"${localProp("OMI_FIREBASE_WEB_API_KEY")}\"")
        buildConfigField("String", "OMI_FIREBASE_TOKEN",         "\"${localProp("OMI_FIREBASE_TOKEN")}\"")
        buildConfigField("String", "OMI_FIREBASE_REFRESH_TOKEN", "\"${localProp("OMI_FIREBASE_REFRESH_TOKEN")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))

    // Wear OS
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Wear Compose
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // Compose core
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // Silero VAD with downgraded OnnxRuntime (1.20 SIGBUS on armeabi-v7a, testing 1.14.1)
    implementation("com.github.gkonovalov.android-vad:silero:2.0.7") {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.14.0")

    // Wear Tiles (for tile service)
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.tiles:tiles-material:1.4.0")
    implementation("androidx.wear.tiles:tiles-tooling-preview:1.4.0")

    // AppCompat (provides colorControlNormal attr used in ic_mic.xml drawable)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.wear:wear:1.3.0")

    // Standalone: direct Omi upload + QR credential setup
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
