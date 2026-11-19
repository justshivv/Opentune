plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * Code every OpenTune app runs unchanged: song and album models, lyrics
 * parsing, album matching, the Listen Together room crypto. No Android and no
 * UI. The phone, TV and watch get it through :core; the desktop depends on it
 * directly.
 *
 * Only a JVM target for now. What's here still uses java.util and
 * java.security, so it lives in jvmMain; files move to commonMain as those
 * uses are replaced. See docs/architecture-desktop.md.
 */
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}
