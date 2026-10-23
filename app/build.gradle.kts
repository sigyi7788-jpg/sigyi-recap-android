plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.sigyi.recap"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sigyi.recap"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // Set this to your deployed Edge TTS companion API HTTPS URL.
        buildConfigField("String", "EDGE_TTS_BASE_URL", "\"https://YOUR-EDGE-TTS-SERVICE.onrender.com\"")
    }

    buildTypes {
        getByName("release") {
            // This is an installable test release. Use a private upload key for publishing.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":whisper"))
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
