import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Setări opționale din local.properties (nu se versionează); folosite pentru presetul vocii „Narator".
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "ro.newsfilter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.newsfilter.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Preset „Narator": numele vocii TTS instalate (gol = vocea implicită) + parametri de redare
        buildConfigField("String", "NARRATOR_VOICE_NAME", "\"${localProps.getProperty("narrator.voiceName", "")}\"")
        buildConfigField("float", "NARRATOR_PITCH", "${localProps.getProperty("narrator.pitch", "0.8")}f")
        buildConfigField("float", "NARRATOR_RATE", "${localProps.getProperty("narrator.rate", "0.9")}f")
        buildConfigField("long", "NARRATOR_PAUSE_MS", "${localProps.getProperty("narrator.pauseMs", "700")}L")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

// Export schema Room (necesar pentru exportSchema = true și migrări viitoare)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose BOM & UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Local Database: Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (Setări & Preferințe)
    implementation(libs.androidx.datastore.preferences)

    // Coil (Încărcare imagini asincronă cu cache)
    implementation(libs.coil.compose)

    // WorkManager (Background Sync / Notificări zilnice)
    implementation(libs.androidx.work.runtime.ktx)

    // Custom Tabs pentru vizualizarea articolului sursă
    implementation(libs.androidx.browser)

    // Coroutines (+ await() pentru Task-urile Firebase / Play Services)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Firebase: Authentication + Cloud Firestore (configurație din app/google-services.json)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Google Sign-In prin Credential Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
}

