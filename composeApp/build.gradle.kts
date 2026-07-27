plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

// Firebase carries per-deployment secrets in google-services.json, which is not in the repository.
// Applying its plugins unconditionally made a fresh clone unbuildable — the build failed at
// :processDebugGoogleServices before a single line of Kotlin was compiled. Crash reporting and push
// are not needed to build or to run a lab session, so they are opt-in on the file's presence.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
    apply(plugin = libs.plugins.firebaseCrashlytics.get().pluginId)
} else {
    logger.lifecycle("composeApp: google-services.json absent — Firebase and Crashlytics disabled")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "11"
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            linkerOpts("-lsqlite3")
            export(libs.kmpnotifier)
        }
    }

    // Enable experimental APIs
    sourceSets.all {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.coil.network.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.material.icons.core)
            implementation(libs.material.icons.extended)

            implementation(libs.voyager.navigator)
            implementation(libs.voyager.screenmodel)
            implementation(libs.voyager.koin)
            implementation(libs.voyager.transitions)
            implementation(libs.voyager.tab.navigator)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.network)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.sqldelight.runtime)
            implementation(libs.napier)
            implementation(libs.kable.core)
            implementation(libs.qrkit.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.firebase.common)
            implementation(libs.firebase.crashlytics)
            api(libs.kmpnotifier)
            api(libs.moko.permissions.compose)
            api(libs.moko.permissions.camera)
            api(libs.moko.permissions.bluetooth)
            api(libs.moko.permissions.location)
        }
    }
}

android {
    namespace = "sk.martinvanco.monad"
    compileSdk = 36

    defaultConfig {
        applicationId = "sk.martinvanco.monad"
        // 29: WifiNetworkSpecifier + Network.bindSocket are the app-scoped association and
        // socket-pinning primitives the lab instrument is built on, and WifiConnectionServiceV2 is
        // already @RequiresApi(Q). Declaring 24 promised a compatibility the code never had.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.androidx.compose.ui.tooling)
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("sk.martinvanco.monad")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            // 4 — EXP-P3 lab instrument tables (sessions + traffic/beacon/transition/clock streams)
            // 5 — retire BleAdvertisementRecord, superseded by BeaconObservationRecord
            version = 5
        }
    }
}
