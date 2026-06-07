import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---- local.properties (not committed) ----
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(name: String, default: String): String =
    (localProps.getProperty(name) ?: default).trim()

// ---- keystore.properties (not committed) — release signing ----
// Looked up from a root `keystore.properties` first, then environment variables
// (for CI). When none are present the release build falls back to debug signing
// so minified smoke builds still work without the upload key on hand.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun keystoreProp(name: String): String? =
    (keystoreProps.getProperty(name) ?: System.getenv(name))?.trim()?.ifBlank { null }

val releaseStoreFilePath = keystoreProp("RELEASE_STORE_FILE")
val hasReleaseSigning = releaseStoreFilePath != null &&
    rootProject.file(releaseStoreFilePath).exists() &&
    keystoreProp("RELEASE_STORE_PASSWORD") != null &&
    keystoreProp("RELEASE_KEY_ALIAS") != null &&
    keystoreProp("RELEASE_KEY_PASSWORD") != null

android {
    namespace = "com.danielanazodo.proofledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.danielanazodo.proofledger"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phase 2: build-config-driven URLs / keys.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${localProp("API_BASE_URL", "http://10.0.2.2:8000/")}\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${localProp("SUPABASE_URL", "")}\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${localProp("SUPABASE_ANON_KEY", "")}\"",
        )

        // Phase 12 observability: empty by default so debug / CI builds need no
        // keys and both SDKs stay no-op. Real values come from local.properties.
        buildConfigField("String", "SENTRY_DSN", "\"${localProp("SENTRY_DSN", "")}\"")
        buildConfigField("String", "POSTHOG_API_KEY", "\"${localProp("POSTHOG_API_KEY", "")}\"")
        buildConfigField(
            "String",
            "POSTHOG_HOST",
            "\"${localProp("POSTHOG_HOST", "https://us.i.posthog.com")}\"",
        )
    }

    signingConfigs {
        getByName("debug") {
            // Default debug config already points at ~/.android/debug.keystore.
        }
        // Real upload key, configured only when keystore.properties / env vars are
        // present (see keystore.properties.example). Never commit the keystore or
        // its passwords.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = keystoreProp("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProp("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Explicit so release builds can never be attached to a debugger
            // even if some future tweak forgets to inherit defaults.
            isDebuggable = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Fallback: debug-signed so minified smoke builds still work
                // without the upload key. NOT publishable to the Play Store.
                logger.warn(
                    "WARNING: release build is debug-signed — no keystore.properties " +
                        "found. This artifact CANNOT be published. See keystore.properties.example.",
                )
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
            // Schemas live under app/schemas/. Exposing them as androidTest
            // assets lets MigrationTestHelper load any version JSON by name.
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

// Room schema JSON output. Committed under app/schemas/<version>.json so
// migrations are reviewable in diffs and MigrationTestHelper can validate them.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    // Generates compile-time adapters for every @JsonClass(generateAdapter=true)
    // DTO. Without this, Moshi falls back to reflection, which R8 minification
    // silently breaks by renaming the very fields the reflection looks up.
    ksp(libs.moshi.kotlin.codegen)

    implementation(libs.androidx.security.crypto)

    // Supabase Auth (Phase 2)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.coil.compose)
    implementation(libs.okio)
    implementation(libs.mlkit.text.recognition)

    // Phase 12 observability (no-op without keys)
    implementation(libs.sentry.android)
    implementation(libs.posthog.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
}
