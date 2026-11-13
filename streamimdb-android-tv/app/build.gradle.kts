plugins { id("com.android.application") }
android {
    namespace = "com.streamimdb.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.streamimdb.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        ndk { abiFilters.add("armeabi-v7a") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
