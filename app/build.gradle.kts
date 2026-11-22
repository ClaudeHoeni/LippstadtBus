plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    namespace = "de.chris.mamafon"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.chris.mamafon"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASS") ?: "android123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "mamafon"
            keyPassword = System.getenv("KEY_PASS") ?: "android123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // WebRTC (inkl. ScreenCapturerAndroid & EglBase)
    implementation("io.getstream:stream-webrtc-android:1.1.3")

    // MQTT Client für serverloses WebSocket-Signaling
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}