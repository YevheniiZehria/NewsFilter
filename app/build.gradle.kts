import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Citim secretele din local.properties (fișier local, ignorat de git).
// Vezi local.properties.example pentru cheile necesare.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProperty(key: String, fallback: String): String =
    localProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

val n8nBaseUrl = localProperty("N8N_BASE_URL", "http://10.0.2.2:5678/webhook/")
val n8nApiKey = localProperty("N8N_API_KEY", "")
val clerkClientId = localProperty("CLERK_CLIENT_ID", "")
val clerkDomain = localProperty("CLERK_DOMAIN", "")

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

        // URL-ul n8n și cheia API vin din local.properties (nu sunt în cod)
        buildConfigField("String", "N8N_BASE_URL", "\"$n8nBaseUrl\"")
        buildConfigField("String", "N8N_API_KEY", "\"$n8nApiKey\"")
        buildConfigField("String", "CLERK_CLIENT_ID", "\"$clerkClientId\"")
        buildConfigField("String", "CLERK_DOMAIN", "\"$clerkDomain\"")
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

    // Network: Retrofit, OkHttp, Moshi
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

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

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
}

