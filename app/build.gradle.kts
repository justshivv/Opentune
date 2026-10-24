plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.opentune"
    // InnerTubeX's AAR is compiled against 37; targetSdk (runtime behaviour) stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opentune"
        minSdk = 26
        targetSdk = 36
        versionCode = 44
        versionName = "1.0.2"
        // This version's store changelog, shown as What's new when a release
        // goes out before the app's own notes for it are written.
        val changelog = rootProject.file("fastlane/metadata/android/en-US/changelogs/$versionCode.txt")
            .takeIf { it.exists() }?.readText().orEmpty().trim()
        buildConfigField("String", "CHANGELOG", "\"" + changelog.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A release key from the environment (CI secrets) when there is one;
    // otherwise release builds are signed with the debug key so they still
    // install. See .github/workflows/release.yml.
    val releaseKeystore = System.getenv("OPENTUNE_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("OPENTUNE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENTUNE_KEY_ALIAS")
                keyPassword = System.getenv("OPENTUNE_KEY_PASSWORD")
            }
        }
    }

    val unsignedRelease = providers.gradleProperty("unsignedRelease").map { it.toBoolean() }.getOrElse(false)

    buildTypes {
        release {
            // Shrinking only: nothing renamed or optimized (see proguard-rules.pro),
            // because stream resolution reaches NewPipe, Rhino, Ktor and others by
            // reflection. Unused library code and resources go.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (unsignedRelease) null else (signingConfigs.findByName("release") ?: signingConfigs.getByName("debug"))
        }
    }

    // One APK per processor type, so each is a third of the size, plus one
    // that runs anywhere.
    splits {
        abi {
            isEnable = !providers.gradleProperty("universalOnly").map { it.toBoolean() }.getOrElse(false)
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    // No encrypted dependency report in the APK: only Google can read it, and
    // F-Droid-style repositories (IzzyOnDroid) flag it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The recorded service answers the tests read live with the code they came from, in :core.
    sourceSets["test"].resources.srcDir(rootProject.file("core/src/test/resources"))
    testOptions {
        // Lets JVM tests run code that logs through android.util.Log.
        unitTests.isReturnDefaultValues = true
        // Robolectric tests (the media library) read the merged manifest and resources.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- Compose (Material 3) ----
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    // Full Material 3 color schemes from one seed color: accent picker and
    // artwork-tinted themes. Also quantizes artwork into a seed.
    implementation("com.materialkolor:material-kolor:4.0.0")
    // Frosted glass behind the floating nav bar and mini player.
    implementation("dev.chrisbanes.haze:haze:1.7.1")
    // Apple-style Liquid Glass (lens refraction, highlights) on Android 13+.
    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Services, library, settings and the player service, shared with the watch app.
    implementation(project(":core"))

    // ---- QR codes: the Listen together invite and the TV's sign-in code, drawn and read. No Android parts. ----
    implementation("com.google.zxing:core:3.5.4")
    // ---- The camera for scanning a QR code in the app (no Google services). ----
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // ---- Images ----
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("io.coil-kt.coil3:coil-test:3.0.4")
    // Rendering screens to images in unit tests, to check layouts.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}




