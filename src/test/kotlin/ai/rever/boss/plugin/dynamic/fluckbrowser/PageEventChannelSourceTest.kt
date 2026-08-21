package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Nothing in this plugin may NAME the page-event members directly.
 *
 * This is the guard on a load-bearing property, not a style rule. `BrowserHandle` is
 * `@HostImplemented`: the host compiles its own copy and serves it parent-first, so a member
 * reference the host's copy lacks fails `BinaryCompatibilityValidator` and the host disables the
 * **entire plugin**. Not the feature - the plugin. fluck-browser is a `systemPlugin` that provides
 * the browser tab, so 1.2.22 declaring `minBossVersion: 9.4.23` and calling the members directly
 * meant every host on 9.4.22 that took the update lost its browser, recoverable only by replacing
 * the jar by hand.
 *
 * Reflection through [PageEventChannel] is what makes the dependency optional. A single direct call
 * anywhere silently undoes that: the plugin still compiles, still passes every other test, and stops
 * loading on older hosts. A source check is the only thing that catches it at the moment it is
 * written, since reproducing it needs an older host to load against.
 */
class PageEventChannelSourceTest {
    /** The members that exist only in api 1.0.83 and later. */
    private val gatedMembers =
        listOf("setPageEventScript", "clearPageEventScript", "supportsPageEventScript")

    /** The one file allowed to name them, in strings, for reflection. */
    private val reflectionHolder = "PageEventChannel.kt"

    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main/kotlin").isDirectory }

    private fun mainSources(root: File): List<File> =
        File(root, "src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * Code with comments removed.
     *
     * Load-bearing: the call sites that were converted explain themselves in comments that name the
     * very members they no longer call, and matching raw text flags the explanation as the offence.
     * Not `substringBefore("//")`, which also cuts a line at the slashes in `https://`.
     */
    private fun codeOf(file: File): String =
        file
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString(" ") { line -> line.split(Regex("(?<!:)//")).first() }

    @Test
    fun `no source calls a gated member directly`() {
        val root = assertNotNull(repoRoot(), "could not locate the plugin root")
        val sources = mainSources(root)
        assertTrue(sources.size > 5, "only ${sources.size} files scanned - the walk is not seeing the source")

        val offenders =
            sources
                .filter { it.name != reflectionHolder }
                .flatMap { file ->
                    val code = codeOf(file)
                    // A DOT before the name is the direct call. The reflective holder passes the
                    // same names as bare strings, which is exactly the distinction that matters.
                    gatedMembers
                        .filter { member -> code.contains(Regex("""\.$member\b""")) }
                        .map { "${file.name} calls .$it" }
                }
        assertEquals(
            emptyList(),
            offenders,
            "these would make the plugin unloadable on hosts below api 1.0.83: $offenders",
        )
    }

    @Test
    fun `the reflective holder still reaches every gated member`() {
        // Otherwise the check above passes by the feature simply being gone - which is the failure
        // mode of every "nothing references X" test.
        val root = assertNotNull(repoRoot(), "could not locate the plugin root")
        val holder = File(root, "src/main/kotlin/ai/rever/boss/plugin/dynamic/fluckbrowser/$reflectionHolder")
        assertTrue(holder.isFile, "$reflectionHolder is not where this test expects it")
        val code = codeOf(holder)
        // Either the property name or its JVM getter: a Kotlin `val` is reached reflectively as
        // getSupportsPageEventScript, so demanding the property name would fail the correct code.
        val missing =
            gatedMembers.filterNot { member ->
                val getter = "get" + member.replaceFirstChar { it.uppercase() }
                code.contains("\"$member\"") || code.contains("\"$getter\"")
            }
        assertEquals(emptyList(), missing, "$reflectionHolder no longer resolves: $missing")
    }

    @Test
    fun `the manifest floors match what the plugin actually requires`() {
        // The floors and the reflection are one decision. Declaring 1.0.83 / 9.4.23 again would make
        // the plugin unloadable below them for a feature it can now do without, and lowering them
        // while calling directly would make it CRASH there instead. They move together or not at
        // all.
        val root = assertNotNull(repoRoot(), "could not locate the plugin root")
        val manifest = File(root, "src/main/resources/META-INF/boss-plugin/plugin.json").readText()
        assertTrue(
            manifest.contains("\"minApiVersion\": \"1.0.73\""),
            "minApiVersion is not the floor this plugin can honour: $manifest",
        )
        assertTrue(
            manifest.contains("\"minBossVersion\": \"9.4.2\""),
            "minBossVersion is not the floor this plugin can honour: $manifest",
        )
    }
}
