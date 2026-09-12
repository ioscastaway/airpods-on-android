import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ioscastaway.airpods"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ioscastaway.airpods"
        // 31 = Android 12: BLUETOOTH_SCAN / BLUETOOTH_CONNECT runtime permissions replace the
        // location-permission-for-Bluetooth era. Everything here is built on that model.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Hidden-API use is declared in code, not via Play; keep the dependency metadata out of the APK.
    dependenciesInfo { includeInApk = false; includeInBundle = false }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Reaches the hidden classic-L2CAP socket constructors on BluetoothDevice (see AapClient).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
