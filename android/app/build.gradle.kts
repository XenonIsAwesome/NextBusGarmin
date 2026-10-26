plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "app.nextbus"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.nextbus"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The watch app's Connect IQ id (watch/manifest.xml). The store id is only needed to
        // offer "install the watch app" once the app is published.
        buildConfigField("String", "WATCH_APP_ID", "\"fc1842ca-f09c-444c-a9dc-572fdc5d0d2b\"")
        buildConfigField("String", "WATCH_APP_STORE_ID", "\"\"")
        // Where the prebuilt index is published (see docs/README.md). Empty disables updates.
        buildConfigField("String", "INDEX_MANIFEST_URL", "\"https://github.com/XenonIsAwesome/NextBusGarmin/releases/download/index-latest/manifest.json\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Garmin Connect IQ Mobile SDK (Maven Central, BLE edition; TETHERED mode is in the same AAR).
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(kotlin("test"))
}

// The bundled stop/line index is produced by tools/gtfs-index and is not committed.
val checkIndexAsset = tasks.register("checkIndexAsset") {
    val index = file("src/main/assets/index.db")
    doFirst {
        if (!index.exists()) {
            throw GradleException(
                "Missing ${index.relativeTo(rootDir.parentFile)}. Build it first: " +
                    "python3 tools/gtfs-index/build_index.py (see tools/gtfs-index/README.md)",
            )
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkIndexAsset) }
