import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProp(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: "").trim()

android {
    namespace = "com.parachord.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.parachord.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["appAuthRedirectScheme"] = "parachord"
        manifestPlaceholders["redirectSchemeName"] = "parachord"
        manifestPlaceholders["redirectHostName"] = "auth"

        // API keys — loaded from local.properties or environment variables
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProp("LASTFM_API_KEY")}\"")
        buildConfigField("String", "LASTFM_SHARED_SECRET", "\"${localProp("LASTFM_SHARED_SECRET")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProp("SPOTIFY_CLIENT_ID")}\"")
        buildConfigField("String", "SOUNDCLOUD_CLIENT_ID", "\"${localProp("SOUNDCLOUD_CLIENT_ID")}\"")
        buildConfigField("String", "SOUNDCLOUD_CLIENT_SECRET", "\"${localProp("SOUNDCLOUD_CLIENT_SECRET")}\"")
        buildConfigField("String", "APPLE_MUSIC_DEVELOPER_TOKEN", "\"${localProp("APPLE_MUSIC_DEVELOPER_TOKEN")}\"")
        buildConfigField("String", "TICKETMASTER_API_KEY", "\"${localProp("TICKETMASTER_API_KEY")}\"")
        buildConfigField("String", "SEATGEEK_CLIENT_ID", "\"${localProp("SEATGEEK_CLIENT_ID")}\"")
    }

    signingConfigs {
        // CI builds decode a stable keystore from the CI_KEYSTORE_BASE64
        // environment variable so that every build produces identically-signed
        // APKs. Local builds fall back to the default debug keystore.
        val ciKeystorePath = System.getenv("CI_KEYSTORE_PATH")
        if (ciKeystorePath != null) {
            create("ciRelease") {
                storeFile = file(ciKeystorePath)
                storePassword = "parachord-ci"
                keyAlias = "ci-release"
                keyPassword = "parachord-ci"
            }
        }
        named("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("ciRelease")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Browser (Custom Tabs for OAuth)
    implementation(libs.androidx.browser)

    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media) // MediaStyle notification for external playback

    // Spotify App Remote SDK (AAR from GitHub releases)
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.spotify.auth)
    implementation(libs.gson)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // Image Loading
    implementation(libs.coil.compose)

    // ML Kit (face detection for artist image centering)
    implementation(libs.mlkit.face.detection)
    implementation(libs.kotlinx.coroutines.play.services)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
