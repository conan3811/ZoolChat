plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.legacy-kapt")
}

// google-services.json is intentionally not committed. Once it is placed in this
// module, the Firebase Gradle plugin is applied automatically.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.zoolchat.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zoolchat.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 32
        versionName = "0.32.0-dev"

        buildConfigField("boolean", "DEMO_MODE", "false")
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("ZOOLCHAT_API_BASE_URL") ?: "https://YOUR_BACKEND_HOST"}\"")
        buildConfigField("boolean", "DEV_AUTH_ENABLED", "${project.findProperty("ZOOLCHAT_DEV_AUTH") ?: "false"}")
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    flavorDimensions += "network"
    productFlavors {
        create("lanBeta") {
            dimension = "network"
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            buildConfigField("boolean", "LAN_BETA", "true")
            versionNameSuffix = "-lan"
        }
        create("public") {
            dimension = "network"
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField("boolean", "LAN_BETA", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.4")

    // Offline-first local cache and durable send queue.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Networking + realtime chat socket.
    implementation("com.squareup.okhttp3:okhttp:5.1.0")

    // International phone number parsing and E.164 normalization.
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.37")

    // Real-time calls: audio, video, group rooms and screen sharing.
    implementation("io.livekit:livekit-android:2.28.1")

    // Identity + incoming call/chat push notifications.
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-messaging")
}
