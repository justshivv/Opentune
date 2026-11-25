pluginManagement {
    repositories {
        google()
        // Google's copy of Maven Central first: Maven Central turns busy CI machines away with
        // 429s, and Gradle doesn't fall through to the next repository on that, it fails.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's copy of Maven Central first: Maven Central turns busy CI machines away with
        // 429s, and Gradle doesn't fall through to the next repository on that, it fails.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // NewPipeExtractor (YouTube stream resolution) is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "OpenTune"
include(":app")
include(":shared")
include(":core")
include(":wear")
include(":tv")
include(":desktop")
