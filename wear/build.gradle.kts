plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * OpenTune on the watch: a player of its own, on the watch's Wi-Fi or LTE
 * and Bluetooth headphones, built on the same :core as the phone app. Its
 * version is the phone app's, read from app/build.gradle.kts.
 */
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)!!.groupValues[1]
val appVersionCode = Regex("""versionCode = (\d+)""").find(appBuild)!!.groupValues[1].toInt()

android {
    lint {
        // AGP 8.10's lint can't follow the class hierarchy of classes Kotlin 2.4 compiles, so it
        // reports every Activity and Service in the manifests as not extending one. Off until the
        // Android Gradle plugin is moved up; the compiler checks the same thing.
        disable += "Instantiatable"
    }
    namespace = "com.opentune.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opentune.wear"
        // Wear OS 3 and later.
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Watches are ARM; x86_64 is for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

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
            // The phone app's rules: stream resolution reaches its libraries by reflection.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("app/proguard-rules.pro"),
            )
            signingConfig = if (unsignedRelease) null else (signingConfigs.findByName("release") ?: signingConfigs.getByName("debug"))
        }
    }
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
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-navigation:1.6.2")
    // Typing or speaking a search on the watch.
    implementation("androidx.wear:wear-input:1.2.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
