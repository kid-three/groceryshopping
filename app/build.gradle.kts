import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Secrets live in local.properties (git-ignored) and are baked into BuildConfig.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String): String = (localProps.getProperty(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "app.receiptscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.receiptscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${localProp("anthropic.apiKey")}\"")
        buildConfigField("String", "CLAUDE_MODEL", "\"${localProps.getProperty("anthropic.model") ?: "claude-haiku-5-5"}\"")
        // Optional defaults so the phones don't need typing in Settings.
        buildConfigField("String", "DEFAULT_SCRIPT_URL", "\"${localProp("sheet.scriptUrl")}\"")
        buildConfigField("String", "DEFAULT_SECRET", "\"${localProp("sheet.secret")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Private sideloaded app: sign release with the debug key so it installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
            // Modern phones are all arm64; drops ~30 MB of OCR native libs for other CPUs.
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin",
        )
    }
    testOptions {
        unitTests.all { it.systemProperty("testdata.dir", rootProject.file("testdata").absolutePath) }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
}
