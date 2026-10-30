import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

/*
 * OpenTune for Windows, macOS and Linux. It compiles the parts of :core
 * that are plain Kotlin as they are: the YouTube Music client and parser,
 * the song models, the HTTP client, settings, lyrics and Subsonic.
 * The few Android classes those touch (Context, SharedPreferences, Log)
 * have desktop stand-ins in src/shim. Everything else, the player
 * included, is its own. Of the phone's lossless code it takes only the
 * add-on protocol; JioSaavn, the add-on catalog and FLAC playback are its own.
 */
// core makes the NewPipe jar used below.
evaluationDependsOn(":core")

val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)!!.groupValues[1]
val appVersionCode = Regex("""versionCode = (\d+)""").find(appBuild)!!.groupValues[1]

val coreSources = rootProject.file("core/src/main/java")
val sharedFromCore = listOf(
    "com/opentune/data/model/**",
    "com/opentune/data/innertube/Innertube.kt",
    "com/opentune/data/innertube/InnertubeParser.kt",
    "com/opentune/data/innertube/PlayerClient.kt",
    "com/opentune/data/podcasts/PodcastParser.kt",
    "com/opentune/data/podcasts/PodcastModels.kt",
    "com/opentune/data/Http.kt",
    "com/opentune/data/DebugLog.kt",
    "com/opentune/data/AppLog.kt",
    "com/opentune/data/TrackIds.kt",
    "com/opentune/data/settings/AppSettings.kt",
    "com/opentune/data/history/ListeningHistoryTarget.kt",
    // Lyrics from every source the phone asks, without the romanizer (Android's ICU).
    "com/opentune/data/lyrics/Lyrics.kt",
    "com/opentune/data/lyrics/LyricsRepository.kt",
    "com/opentune/data/lyrics/LyricsQuality.kt",
    "com/opentune/data/lyrics/SongParts.kt",
    "com/opentune/data/lyrics/BetterLyrics.kt",
    "com/opentune/data/lyrics/KuGou.kt",
    "com/opentune/data/lyrics/LyricsPlus.kt",
    "com/opentune/data/lyrics/LrcRed.kt",
    "com/opentune/data/lyrics/Paxsenix.kt",
    "com/opentune/data/metadata/Deezer.kt",
    "com/opentune/data/metadata/AppleWebToken.kt",
    "com/opentune/data/lyrics/NetEase.kt",
    "com/opentune/data/lyrics/SimpMusic.kt",
    "com/opentune/data/lyrics/YouTubeCaptions.kt",
    "com/opentune/data/subsonic/Subsonic.kt",
    // The add-on wire protocol, as the phone reads it.
    "com/opentune/data/addons/AddonModels.kt",
    // The owner's signed app configuration from the VPS, which carries the published add-ons.
    "com/opentune/data/config/RemoteConfig.kt",
    // NewPipe's patched helper, see that file.
    "org/schabi/newpipe/extractor/utils/Utils.java",
)

/** core's BuildConfig, for the shared files that read it, with this build's version. */
val generateBuildConfig = tasks.register("generateBuildConfig") {
    val out = layout.buildDirectory.dir("generated/buildconfig")
    outputs.dir(out)
    inputs.property("version", appVersionName)
    // OpenTune's Last.fm application, as the phone builds take it (core/build.gradle.kts); empty
    // in a local build, where Settings asks for the listener's own key instead.
    val lastFmKey = providers.environmentVariable("LASTFM_API_KEY").orElse("").map { it.trim() }
    val lastFmSecret = providers.environmentVariable("LASTFM_API_SECRET").orElse("").map { it.trim() }
    inputs.property("lastFmKey", lastFmKey)
    inputs.property("lastFmSecret", lastFmSecret)
    doLast {
        listOf(lastFmKey.get(), lastFmSecret.get()).forEach {
            require(it.isEmpty() || it.matches(Regex("[a-fA-F0-9]{32}"))) { "Last.fm credentials must be 32 hex characters" }
        }
        val file = out.get().file("com/opentune/core/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.opentune.core
            |
            |object BuildConfig {
            |    const val DEBUG = false
            |    const val VERSION_NAME = "$appVersionName"
            |    const val VERSION_CODE = $appVersionCode
            |    const val LASTFM_API_KEY = "${lastFmKey.get()}"
            |    const val LASTFM_API_SECRET = "${lastFmSecret.get()}"
            |}
            |""".trimMargin(),
        )
    }
}

sourceSets {
    main {
        kotlin {
            srcDir(coreSources)
            srcDir(generateBuildConfig)
            srcDir("src/shim/kotlin")
            include("com/opentune/desktop/**", "com/opentune/core/**", "shim/**")
            include(sharedFromCore.filter { it.endsWith(".kt") || it.endsWith("**") })
        }
        java {
            srcDir(coreSources)
            srcDir("src/shim/java")
            include("android/**")
            include(sharedFromCore.filter { it.endsWith(".java") })
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// JavaFX plays the audio; its native parts differ by system.
val javafxPlatform = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Stream addresses, as on the phone: NewPipe, with core's patched helper.
    implementation(files(rootProject.project(":core").tasks.named("stripNewPipeExtractorUtils")))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    // Add-on responses (the shared protocol reads them with org.json, as on the phone),
    // FLAC decoding for lossless sources, and the QR code a phone scans to verify this computer.
    implementation("org.json:json:20240303")
    implementation("org.jflac:jflac-codec:1.5.2")
    implementation("com.google.zxing:core:3.5.4")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    for (module in listOf("base", "graphics", "media")) {
        implementation("org.openjfx:javafx-$module:21.0.5:$javafxPlatform")
    }

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The live check runs only when asked: -Dopentune.live=true
    systemProperty("opentune.live", System.getProperty("opentune.live") ?: "false")
    System.getProperty("opentune.video")?.let { systemProperty("opentune.video", it) }
    // Pictures of the window, drawn offscreen: -Dopentune.shots=/some/folder
    System.getProperty("opentune.shots")?.let { systemProperty("opentune.shots", it) }
    testLogging { showStandardStreams = true }
}

compose.desktop {
    application {
        mainClass = "com.opentune.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "OpenTune"
            // Installers need MAJOR.MINOR.BUILD with MAJOR above 0; the app's own version shows inside.
            packageVersion = "1.0.$appVersionCode"
            description = "A music player for YouTube Music"
            vendor = "OpenTune"
            // What jlink's scan suggests, plus scripting for Rhino and EC ciphers for HTTPS.
            modules("java.instrument", "java.management", "java.net.http", "java.scripting", "jdk.crypto.ec", "jdk.dynalink", "jdk.httpserver", "jdk.jfr", "jdk.unsupported")
            macOS {
                iconFile.set(project.file("packaging/icons/AppIcon.icns"))
                bundleID = "com.opentune.desktop"
            }
            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                menuGroup = "OpenTune"
                upgradeUuid = "6c1f6c2e-5a3b-4d34-9a51-0e9c1d8f2a71"
            }
            linux {
                iconFile.set(project.file("packaging/icons/AppIcon.png"))
                debMaintainer = "78946189+justshivv@users.noreply.github.com"
            }
        }
    }
}
