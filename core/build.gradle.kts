plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * What the phone, TV and watch apps share: YouTube Music and the other
 * services, the library and settings, downloads, and the player service.
 * No UI lives here.
 *
 * The app's version is set in app/build.gradle.kts, where releases change it;
 * it's read from there so this module reports the same one.
 */
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)!!.groupValues[1]
val appVersionCode = Regex("""versionCode = (\d+)""").find(appBuild)!!.groupValues[1]

// Registered OpenTune application credentials are supplied by the build owner.
// Last.fm mobile authentication requires these; listeners only enter their login.
fun lastFmCredential(name: String): String {
    val value = providers.environmentVariable(name).orNull.orEmpty().trim()
    require(value.isEmpty() || value.matches(Regex("[a-fA-F0-9]{32}"))) { "$name must be a Last.fm application credential" }
    return "\"$value\""
}

android {
    namespace = "com.opentune.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "VERSION_CODE", appVersionCode)
        buildConfigField("String", "LASTFM_API_KEY", lastFmCredential("LASTFM_API_KEY"))
        buildConfigField("String", "LASTFM_API_SECRET", lastFmCredential("LASTFM_API_SECRET"))
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
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

/*
 * NewPipeExtractor ships its own org.schabi.newpipe.extractor.utils.Utils, and
 * src/main/java carries a patched copy at the same package path (see that
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
    // The apps use these types directly too, so they're passed on.
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.work:work-runtime-ktx:2.10.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // ---- Media playback: Media3 / ExoPlayer ----
    api("androidx.media3:media3-exoplayer:1.11.0")
    api("androidx.media3:media3-session:1.11.0")
    api("androidx.media3:media3-common:1.11.0")
    api("androidx.media3:media3-datasource-okhttp:1.11.0")
    api("androidx.media3:media3-database:1.11.0")
    // Internet radio stations that stream HLS rather than Icecast.
    api("androidx.media3:media3-exoplayer-hls:1.11.0")
    api("androidx.media3:media3-exoplayer-dash:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")

    // ---- Innertube (YouTube Music) client: Ktor + kotlinx.serialization ----
    api("io.ktor:ktor-client-core:3.5.2")
    api("io.ktor:ktor-client-okhttp:3.5.2")
    api("io.ktor:ktor-client-content-negotiation:3.5.2")
    api("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    // ---- InnerTubeX: a second stream extractor (client catalog, cipher tiers, PoTokens) ----
    // Ktor and serialization above are held at its versions.
    api("com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.4")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ---- Stream resolution: NewPipe solves YouTube's signature + `n` throttling ----
    // Pinned to v0.26.3: the newer v0.26.4's player-JS parser fails to parse
    // the current player build's deobfuscation function.
    api(files(newPipeExtractorStripped))
    api("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    api("org.jsoup:jsoup:1.22.2")
    api("com.google.code.findbugs:jsr305:3.0.2")
    api("com.google.protobuf:protobuf-javalite:4.35.0")
    api("org.mozilla:rhino:1.8.1")
    api("org.mozilla:rhino-engine:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}
