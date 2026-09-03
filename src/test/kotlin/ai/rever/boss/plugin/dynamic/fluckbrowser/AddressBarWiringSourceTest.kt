package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Compose half of Cmd+L, which no unit test can reach.
 *
 * [AddressBarFocusRegistryTest] proves the registry focuses the right toolbar, and
 * [AddressBarUrlFieldTest] proves the selection survives a navigation - but both talk to a
 * lambda a test supplied. The wiring that makes the REAL lambda do anything is four lines spread
 * across a 6000-line composable: the registration effect, the two registry calls in it, the
 * requester handed to `BrowserToolbar`, and the requester attached to the URL field's modifier.
 * Delete any one of them and Cmd+L silently does nothing while every other test still passes.
 *
 * A source check is the cheap half of the Compose UI test this repo has no infrastructure for -
 * the same trade `PageEventChannelSourceTest` makes, and it fails at the moment the line is
 * deleted rather than on someone's machine.
 */
class AddressBarWiringSourceTest {
    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main/kotlin").isDirectory }

    /**
     * Code with comments removed, so a line that merely *mentions* the wiring cannot satisfy the
     * check. Borrowed verbatim from [PageEventChannelSourceTest], including why it is not
     * `substringBefore("//")`: that also cuts a line at the slashes in `https://`.
     */
    private fun codeOf(file: File): String =
        file
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString(" ") { line -> line.split(Regex("(?<!:)//")).first() }

    private fun tabComponentCode(): String {
        val root = assertNotNull(repoRoot(), "could not locate the plugin root")
        val file = File(root, "src/main/kotlin/ai/rever/boss/plugin/dynamic/fluckbrowser/FluckBrowserTabComponent.kt")
        assertTrue(file.isFile, "FluckBrowserTabComponent.kt is not where this test expects it")
        return codeOf(file)
    }

    @Test
    fun `the tab composition still registers and unregisters its toolbar`() {
        // Without the pair, focusActiveIn has nothing to find and every Cmd+L is a miss.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""AddressBarFocusRegistry\s*\.\s*register\s*\(""")),
            "the tab composition no longer registers its address bar - Cmd+L can never find it",
        )
        assertTrue(
            code.contains(Regex("""AddressBarFocusRegistry\s*\.\s*unregister\s*\(""")),
            "the registration is never torn down - a closed tab would keep answering Cmd+L",
        )
    }

    @Test
    fun `the requester reaches the toolbar and the URL field`() {
        // The two ends of the same wire. Either one missing leaves the registry calling
        // requestFocus on a requester attached to nothing, which throws, is caught, and looks
        // exactly like "Cmd+L did nothing".
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""addressBarFocusRequester\s*=\s*addressBarFocusRequester""")),
            "BrowserToolbar is no longer given the requester - the field it focuses is not the one on screen",
        )
        assertTrue(
            code.contains(Regex("""\.focusRequester\(\s*addressBarFocusRequester\s*\)""")),
            "the URL field no longer attaches the requester - requestFocus would throw on every Cmd+L",
        )
    }
}
