import java.util.Properties

plugins {
    // AGP 9 built-in Kotlin compiles Kotlin sources; org.jetbrains.kotlin.android is not applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing: android/keystore.properties (git-ignored) or environment variables.
// Keys: storeFile, storePassword, keyAlias, keyPassword
// Env:  DAYCUE_KEYSTORE_FILE, DAYCUE_KEYSTORE_PASSWORD, DAYCUE_KEY_ALIAS, DAYCUE_KEY_PASSWORD
// If incomplete, release builds fall back to the debug key (installable locally, never publishable).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envKey).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "DAYCUE_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "DAYCUE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "DAYCUE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "DAYCUE_KEY_PASSWORD")
// ---- Spotify (optional): client ID placeholder + conditional App Remote AAR ----------------------------------
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val spotifyClientId: String = (providers.gradleProperty("daycue.spotify.clientId").orNull
    ?: localProperties.getProperty("daycue.spotify.clientId"))?.takeIf { it.isNotBlank() }?.replace("\"", "")
    ?: "YOUR_SPOTIFY_CLIENT_ID"
val spotifyAar: File? = file("libs").listFiles { f -> f.name.startsWith("spotify-app-remote") && f.extension == "aar" }?.firstOrNull()
val hasSpotifySdk: Boolean = spotifyAar != null

val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it != null } && rootProject.file(releaseStoreFile!!).isFile

android {
    namespace = "app.daycue"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.daycue"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Spotify alarm source (integrations/spotify, docs/setup/SPOTIFY.md). The client ID comes from the
        // git-ignored android/local.properties (daycue.spotify.clientId=...) or -Pdaycue.spotify.clientId; the
        // committed default is a placeholder. The App Remote AAR is NOT in the repo: drop it into app/libs/ (git-ignored).
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("boolean", "SPOTIFY_SDK", hasSpotifySdk.toString())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("DayCue: no release keystore configured; release build is signed with the DEBUG key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (hasSpotifySdk) {
        // The real App Remote adapter is only compiled when the AAR is present.
        sourceSets.getByName("main").kotlin.directories.add("src/spotifysdk/kotlin")
    }
}

room {
    // Exported schemas are committed (they contain structure only, no data) for migration tests.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat) // per-app language backport (API 26-32)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location) // geofencing, fused location, activity transitions (integrations/location)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    if (hasSpotifySdk) {
        implementation(files(spotifyAar!!))
        implementation("com.google.code.gson:gson:2.6.1") // required by the App Remote AAR (per Spotify's quick start)
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
