plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stsmn.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stsmn.monitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    // Release signing is configured ONLY when the CI provides a keystore via
    // environment variables (GitHub Secrets). No keystore is ever stored in the repo.
    val keystorePath = System.getenv("STSMN_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STSMN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STSMN_KEY_ALIAS")
                keyPassword = System.getenv("STSMN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Every dependency has a reason (HTTP uses the built-in HttpURLConnection,
// JSON uses the built-in org.json, history/settings use plain files/SharedPreferences).
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")                         // KTX helpers
    implementation("androidx.activity:activity-compose:1.9.1")              // Compose host activity, edge-to-edge
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")  // ViewModel for MVVM
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")    // lifecycle-aware StateFlow collection
    implementation("androidx.compose.ui:ui")                                // Compose UI
    implementation("androidx.compose.material3:material3")                  // Material 3 components
    implementation("androidx.compose.material:material-icons-core")         // tab icons
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1") // polling loop / Flow
    implementation("com.squareup.okhttp3:okhttp:4.12.0")                      // realtime WebSocket to the STSMN server (wss://)
    implementation("org.osmdroid:osmdroid-android:6.1.18")                  // Live Map (OpenStreetMap, no API key)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests
}
