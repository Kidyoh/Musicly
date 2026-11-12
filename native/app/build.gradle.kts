import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing reuses the key of the Flutter app so this installs over it.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("../android/key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// The app's own Telegram key (api_id / api_hash), one for every user, like any Telegram
// client. It is read from native/tg-keys.properties (never committed) or the
// TG_API_ID / TG_API_HASH environment variables. Without it the login screen asks
// for a key instead.
val tgKeys = Properties().apply {
    val f = rootProject.file("tg-keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tgApiId: String = (tgKeys.getProperty("api_id") ?: System.getenv("TG_API_ID") ?: "0").trim()
val tgApiHash: String = (tgKeys.getProperty("api_hash") ?: System.getenv("TG_API_HASH") ?: "").trim()

android {
    namespace = "com.musicly.musicly"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicly.musicly"
        minSdk = 24
        targetSdk = 35
        // Keep above the Flutter releases (3001) so it installs over them.
        versionCode = 4003
        buildConfigField("int", "TG_API_ID", tgApiId.toIntOrNull()?.toString() ?: "0")
        buildConfigField("String", "TG_API_HASH", "\"${tgApiHash.filter { it.isLetterOrDigit() }}\"")
        versionName = "2.1.1-preview4"
        // TDLib ships ~20 MB of native code per ABI; keep phones only (no x86 emulators).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file("../android/app/" + keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystoreProperties.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // TDLib's .so files are ~37 MB for the two ABIs. Stored compressed in the APK they download as ~15 MB
    // (they are extracted at install instead of mapped from the APK).
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests { isReturnDefaultValues = true; isIncludeAndroidResources = true } }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric downloads its android-all jar at first use; MUSICLY_SHOTS selects the PNG output dir.
    System.getenv("ROBOLECTRIC_REPO")?.let { systemProperty("robolectric.dependency.repo.url", it) }
    System.getenv("MUSICLY_SHOTS")?.let { systemProperty("MUSICLY_SHOTS", it) }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // TDLib: JNI part + libtdjson.so for arm64-v8a / armeabi-v7a. The td-ktx wrapper is not used: its
    // consumer rules keep its 55k-line generated API (about 10 MB of dex) and it println()s the
    // login request (api hash) in setTdlibParameters. TdJsonClient is a 60-line replacement.
    implementation("io.github.tdlibx:td-android:1.8.56-RC9")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
