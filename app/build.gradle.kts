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
        versionCode = 13
        versionName = "0.3.1"

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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // One APK per processor type, so each is a third of the size, plus one
    // that runs anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
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

/*
 * NewPipeExtractor ships its own org.schabi.newpipe.extractor.utils.Utils, and
 * app/src/main/java carries a patched copy at the same package path (see that
 * file for why). A release build merges every input into one dex set, where
 * D8 rejects the duplicate type outright, so the library's copy is stripped
 * from its jar before it reaches dexing, leaving exactly one definition.
 *
 * The artifact is resolved on its own and non-transitive purely to re-jar it;
 * its transitive dependencies are declared by hand below, since dropping the
 * module drops them too.
 */
val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>(
    "stripNewPipeExtractorUtils"
) {
    archiveFileName.set("NewPipeExtractor-v0.26.3-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    // `elements` keeps resolution lazy: the jar is only opened when this task runs,
    // not while Gradle configures the build.
    from(newPipeExtractorRaw.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
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
    // Downloads that keep going after the app is closed.
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Media playback: Media3 / ExoPlayer ----
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("androidx.media3:media3-database:1.11.0")
    // Internet radio stations that stream HLS rather than Icecast.
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")

    // ---- Images ----
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // ---- Innertube (YouTube Music) client: Ktor + kotlinx.serialization ----
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    // ---- InnerTubeX: a second stream extractor (client catalog, cipher tiers, PoTokens) ----
    // Ktor and serialization above are held at its versions.
    implementation("com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ---- Stream resolution: NewPipe solves YouTube's signature + `n` throttling ----
    // Pinned to v0.26.3: the newer v0.26.4's player-JS parser fails to parse
    // the current player build's deobfuscation function.
    implementation(files(newPipeExtractorStripped))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

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
