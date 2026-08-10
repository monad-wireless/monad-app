// `java.security.…` cannot be written inline: in a Gradle Kotlin DSL script `java` resolves to the
// java plugin extension accessor, not to the package root.
import java.security.MessageDigest

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

// ---------------------------------------------------------------------------------------------
// Build identity — one version, derived everywhere.
//
// A wireless measurement is only comparable to another measurement if you can say which artefact
// produced it. The sidecar used to record a version string (`0.3.0-lab`) that neither Gradle nor
// Xcode had ever heard of, so "which build produced this data?" was unanswerable — and a lab whose
// artefacts must survive a thesis defence cannot afford an unanswerable provenance question.
//
// The hand-written truth is `monad.version` / `monad.versionCode` in gradle.properties. From it:
//   * Android's versionName / versionCode are set below, so they cannot drift.
//   * `BuildIdentity.kt` is generated into commonMain, so both platforms compile the *same*
//     constants into the binary and the sidecar records them.
//   * The iOS project is checked, not derived (see `verifyIosAppVersion`).
//
// The version alone is not enough: two builds of 1.2.0 from different commits produce different
// data. `BUILD_ID` therefore carries the commit and, when the working tree is dirty, a fingerprint
// of the uncommitted change — so a bench build is distinguishable from the next bench build made
// five minutes later with a different patch applied, without a timestamp making every build
// non-cacheable.
// ---------------------------------------------------------------------------------------------

val monadVersion: String = providers.gradleProperty("monad.version").get()
val monadVersionCode: Int = providers.gradleProperty("monad.versionCode").get().toInt()

/** Run a git command in the repository root, or null when git is unavailable (source zip, CI). */
fun gitOutput(vararg args: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    process.errorStream.close()
    if (process.waitFor() != 0) null else text
}.getOrNull()

val monadBuildCommit: String = gitOutput("rev-parse", "--short=8", "HEAD")?.trim()?.ifBlank { null }
    ?: "nogit"

/**
 * Eight hex characters identifying the uncommitted state, or null on a clean tree.
 *
 * Deliberately a content hash rather than a timestamp: it changes exactly when the working tree
 * changes, so Gradle can still consider the generated source up to date across rebuilds of the
 * same code, and two dirty builds of genuinely different code get different ids.
 */
val monadWorktreeFingerprint: String? = run {
    val status = gitOutput("status", "--porcelain")?.takeIf { it.isNotBlank() } ?: return@run null
    val diff = gitOutput("diff", "HEAD").orEmpty()
    MessageDigest.getInstance("SHA-1")
        .digest((status + diff).toByteArray())
        .joinToString("") { b: Byte -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
        .take(8)
}

/** `<version>+<versionCode>.g<commit8>[.dirty<worktree8>]` — the grammar the sidecar records. */
val monadBuildId: String = buildString {
    append(monadVersion)
    append('+').append(monadVersionCode)
    append(".g").append(monadBuildCommit)
    monadWorktreeFingerprint?.let { append(".dirty").append(it) }
}

val generateBuildIdentity = tasks.register("generateBuildIdentity") {
    description = "Generates BuildIdentity.kt from monad.version / monad.versionCode."
    group = "build"
    val outputDir = layout.buildDirectory.dir("generated/monad/commonMain/kotlin")
    val version = monadVersion
    val versionCode = monadVersionCode
    val commit = monadBuildCommit
    val worktree = monadWorktreeFingerprint
    val buildId = monadBuildId
    inputs.property("version", version)
    inputs.property("versionCode", versionCode)
    inputs.property("commit", commit)
    inputs.property("worktree", worktree ?: "")
    outputs.dir(outputDir)
    doLast {
        val packageDir = outputDir.get().asFile.resolve("sk/martinvanco/monad/core/config")
        packageDir.mkdirs()
        packageDir.resolve("BuildIdentity.kt").writeText(
            """
            package sk.martinvanco.monad.core.config

            /**
             * GENERATED — do not edit. Source of truth: `monad.version` / `monad.versionCode` in
             * gradle.properties, materialised by :composeApp:generateBuildIdentity.
             *
             * Compiled into both the Android APK and the iOS framework, so the identity recorded in
             * a session sidecar is the identity of the binary that recorded it, on either platform.
             *
             * `val` rather than `const val`, deliberately. [BUILD_ID] changes on every edit to a
             * dirty tree — that is the whole point of it — and a `const` would be inlined at every
             * use site, so each keystroke's rebuild would recompile everything that touches
             * `AppConfig`. As plain properties this file recompiles alone.
             */
            object BuildIdentity {

                /** Marketing version. Android `versionName`, iOS `CFBundleShortVersionString`. */
                val VERSION: String = "$version"

                /** Store build number. Android `versionCode`, iOS `CFBundleVersion`. */
                val VERSION_CODE: Int = $versionCode

                /** Short commit this build was made from, or `nogit` when git was unavailable. */
                val COMMIT: String = "$commit"

                /** True when the working tree carried uncommitted changes — the build is not reproducible. */
                val DIRTY: Boolean = ${worktree != null}

                /**
                 * The identifier recorded in every session sidecar as `build_id`.
                 *
                 * Grammar: `<version>+<versionCode>.g<commit8>[.dirty<worktree8>]`, e.g.
                 * `$buildId`. The leading `<version>` is exactly [VERSION], which is what lets a
                 * reader recover the marketing version from a build id alone.
                 */
                val BUILD_ID: String = "$buildId"
            }
            """.trimIndent() + "\n"
        )
    }
}

/**
 * iOS cannot be *derived*, so it is *checked*.
 *
 * Xcode resolves MARKETING_VERSION / CURRENT_PROJECT_VERSION from the project file before any build
 * phase — including the phase that runs Gradle — so nothing Gradle writes can reach them in the
 * same build. A comment asking the next person to remember is exactly the mechanism that produced
 * the three-way disagreement in the first place, so this fails the iOS compile instead.
 */
val verifyIosAppVersion = tasks.register("verifyIosAppVersion") {
    description = "Fails when iosApp's MARKETING_VERSION / CURRENT_PROJECT_VERSION disagree with monad.version."
    group = "verification"
    val projectFile = rootProject.layout.projectDirectory.file("iosApp/iosApp.xcodeproj/project.pbxproj")
    val expectedVersion = monadVersion
    val expectedCode = monadVersionCode.toString()
    val stamp = layout.buildDirectory.file("tmp/verifyIosAppVersion.stamp")
    inputs.file(projectFile)
    inputs.property("version", expectedVersion)
    inputs.property("versionCode", expectedCode)
    outputs.file(stamp)
    doLast {
        val text = projectFile.asFile.readText()
        fun values(setting: String): List<String> =
            Regex("""\b$setting\s*=\s*([^;]+);""").findAll(text)
                .map { it.groupValues[1].trim().trim('"') }
                .toList()

        val marketing = values("MARKETING_VERSION")
        val current = values("CURRENT_PROJECT_VERSION")
        val problems = buildList {
            // An empty match set is a failure, not a pass: a renamed build setting must not be
            // able to turn this check into a no-op.
            if (marketing.isEmpty()) add("no MARKETING_VERSION found in ${projectFile.asFile.name}")
            if (current.isEmpty()) add("no CURRENT_PROJECT_VERSION found in ${projectFile.asFile.name}")
            marketing.distinct().filter { it != expectedVersion }.forEach {
                add("MARKETING_VERSION = $it, expected $expectedVersion")
            }
            current.distinct().filter { it != expectedCode }.forEach {
                add("CURRENT_PROJECT_VERSION = $it, expected $expectedCode")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "iosApp version drifted from gradle.properties (monad.version=$expectedVersion, " +
                    "monad.versionCode=$expectedCode):\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nFix it in Xcode (target iosApp > Build Settings > Versioning) or in " +
                    "iosApp/iosApp.xcodeproj/project.pbxproj, then rebuild."
            )
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("$expectedVersion+$expectedCode\n")
    }
}

// Every iOS compilation goes through the check. Android needs no equivalent: its versionName and
// versionCode are assigned from the same properties below, so they cannot disagree.
tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach {
    dependsOn(verifyIosAppVersion)
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
        // BuildIdentity.kt is generated, not written: it is the one place the version enters the
        // shared code, and it enters it from gradle.properties.
        named("commonMain") { kotlin.srcDir(generateBuildIdentity) }

        androidMain.dependencies {
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.coil.network.okhttp)
            // Optional sensor modules. Present in the binary on every build; whether they can do
            // anything is decided at runtime by DeviceCapabilities, so a handset without the
            // hardware simply never receives a quest that needs them.
            implementation(libs.arcore)
            implementation(libs.androidx.core.uwb)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        // Pure-logic tests only: health thresholds, backoff, the clock fit, the QR codec, zone
        // resolution. Nothing here starts a coroutine, opens a database, or touches Compose — the
        // whole point of extracting those objects was that they can be checked without a lab.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The one place a test is allowed a database. The boot-epoch guard in LabSessionRecovery
        // and the upload-then-delete rule in LabSessionUploader are the highest-consequence logic
        // in the lab path and were covered by reasoning alone; both are about what SQL actually
        // does to rows, so both need a real schema. An in-memory JDBC driver gives that on the JVM
        // without a device, an emulator, or a file.
        getByName("androidUnitTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.sqldelight.sqlite.driver)
            // Koin's `verify()` walks every definition's constructor by reflection and fails on a
            // parameter type nothing provides. Koin resolves lazily, so a broken `get()` otherwise
            // surfaces as a crash on the screen that first needs it — which, for the lab console, is
            // in a room with twelve participants in it. JVM-only and test-only: nothing is added to
            // the shipped binary.
            implementation(libs.koin.test)
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
        // Derived, never written here. See gradle.properties `monad.version` / `monad.versionCode`.
        versionCode = monadVersionCode
        versionName = monadVersion

        // Bench / CI override for the API host. The lab console can already redirect the
        // *collector* by hand for a rig the backend does not know about; this is the same
        // escape hatch for the backend itself, so an emulator or a bench server can be
        // targeted without editing source. Empty means "use the compiled-in deployment".
        //
        //   ./gradlew :composeApp:assembleDebug -Pmonad.apiBase=http://10.0.2.2:8088
        //
        // 10.0.2.2 is the host loopback as seen from the Android emulator.
        buildConfigField(
            "String",
            "API_BASE_URL_OVERRIDE",
            "\"${project.findProperty("monad.apiBase") ?: ""}\"",
        )
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
    testOptions {
        unitTests {
            // The lab tests touch `clockBootId()`, whose Android actual reads
            // SystemClock.elapsedRealtime(). Without this the stub android.jar throws
            // "not mocked", which would make the continuity-epoch guard the one thing untestable —
            // and it is the thing most worth testing. No test asserts on a stubbed value; the
            // epoch is only ever compared for equality against itself.
            isReturnDefaultValues = true
        }
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
            // `version` is the schema version the app ships, and SQLDelight runs migration `N.sqm`
            // only when `version > N`. It had drifted to 5 while 5.sqm and 6.sqm existed, so those
            // two migrations could never fire on an upgrade: 5.sqm needs version > 5 and 6.sqm
            // needs version > 6. Devices carrying schema 5 kept a retired table and — worse — never
            // got GroundTruthEventRecord or SessionMarkerRecord, so the very first marker or
            // check-in scan threw on exactly the handsets that had been in the field longest.
            //
            // With eight migration files the correct value is 9. Every one of 5.sqm through 8.sqm
            // is safe to run on a device that already has its objects (IF NOT EXISTS, or an ALTER
            // onto columns a schema-5 database provably lacks).
            //
            // 4 — EXP-P3 lab instrument tables (sessions + traffic/beacon/transition/clock streams)
            // 5 — retire BleAdvertisementRecord, superseded by BeaconObservationRecord
            // 6 — ground-truth (people) channel; also creates SessionMarkerRecord, which reached
            //     LabSample.sq without a migration and so existed only on fresh installs
            // 7 — session continuity epoch (bootId) + crash/kill recovery (interruptedReason)
            // 8 — ground truth gets a second acknowledgement (`ingested`) for the room aggregate,
            //     tracked apart from the S3 `uploaded` flag because the two fail independently
            // 9 — periodic health checkpoints, so "was it degraded for 42 minutes?" is answerable
            //     for a session the OS killed and not only for one that reached stop()
            // 10 — LabSessionRecord.buildId, so an interrupted session's sidecar names the build
            //      that *recorded* it rather than the build that later recovered it
            version = 11
        }
    }
}
