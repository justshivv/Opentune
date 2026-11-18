package com.opentune

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles regexes with ICU, which rejects some of the JVM's own syntax: the
 * `\p{IsHan}`, `\p{InHiragana}` and `\p{javaLowerCase}` forms among them. Tests run on the JVM,
 * which accepts them, so a pattern using one passed every test and crashed on the phone (the
 * lyrics view's Japanese readings did). This keeps them out of the app's sources.
 */
class RegexPortabilityTest {
    private val jvmOnly = Regex("""\\{1,2}p\{(?:Is|In|java)[A-Za-z]""")

    @Test fun noJvmOnlyUnicodePropertiesInAppSources() {
        val root = File("..").canonicalFile
        val offending = listOf("core", "app", "tv", "wear").flatMap { module ->
            File(root, "$module/src/main").takeIf(File::isDirectory)?.walkTopDown()
                ?.filter { it.isFile && it.extension in setOf("kt", "java") }
                ?.flatMap { file ->
                    file.readLines().withIndex()
                        .filter { (_, line) -> jvmOnly.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                        .map { (n, line) -> "${file.relativeTo(root)}:${n + 1}: ${line.trim()}" }
                }?.toList().orEmpty()
        }
        assertTrue("Android's regex engine rejects these; use Character.UnicodeScript or explicit ranges:\n" + offending.joinToString("\n"), offending.isEmpty())
    }
}
