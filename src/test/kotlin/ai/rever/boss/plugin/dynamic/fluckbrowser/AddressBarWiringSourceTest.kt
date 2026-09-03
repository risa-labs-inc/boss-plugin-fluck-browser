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
 * lambda a test supplied. The wiring that makes the REAL lambda do anything is a handful of lines
 * spread across a 6000-line composable: the registration effect and its two registry calls, the
 * two host values the ranking depends on, the requester handed to `BrowserToolbar`, the requester
 * attached to the URL field, and the Escape branch that hands a claimed field back. Delete any
 * one and Cmd+L silently misbehaves while every other test still passes.
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
    fun `the registration still reads the real window and panel`() {
        // The registry's entire job is ranking on these two values. Hardcoding either - a
        // constant window id, or panelActive = true - would leave every other test passing while
        // Cmd+L answered from a background split, which is the failure the refusals exist for.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""LocalWindowIdProvider\s*\.\s*current""")),
            "the window id is no longer read from the host - entries cannot be attributed to a window",
        )
        assertTrue(
            code.contains(Regex("""LocalIsPanelActive\s*\.\s*current""")),
            "panel-active is no longer read from the host - a background split could answer Cmd+L",
        )
        assertTrue(
            code.contains(Regex("""panelActive\s*=\s*addressBarPanelActive""")),
            "the registration no longer passes the real panel-active value",
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

    @Test
    fun `the navigation listener still measures the field against the shown URL`() {
        // Both halves matter, in opposite directions: hardcode this true and a redirect collapses
        // Cmd+L's selection again; hardcode it false and an abandoned claim never expires. It has
        // to be the real comparison, against the URL the bar was SHOWING rather than the one
        // being navigated to.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""fieldHoldsUnmodifiedUrl\s*=\s*urlBarText\.text\s*==\s*shownUrl""")),
            "the claim-staleness gate is no longer the real comparison - either the selection or " +
                "the stuck-claim fix is now broken",
        )
        assertTrue(
            code.contains(Regex("""val\s+shownUrl\s*=\s*loadedUrl""")),
            "shownUrl is no longer captured before loadedUrl moves on, so the comparison is " +
                "against the wrong URL",
        )
    }

    @Test
    fun `Escape still hands a claimed field back`() {
        // Cmd+L claims the field without the user typing, so the Escape branch is the only thing
        // stopping a change of mind freezing the URL bar for the life of the tab.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""onCancelUrlEditing\s*\(\s*\)""")),
            "Escape no longer cancels the edit - a Cmd+L the user backs out of leaves the bar claimed",
        )
        assertTrue(
            code.contains(Regex("""onCancelUrlEditing\s*=""")),
            "nothing supplies onCancelUrlEditing, so Escape cancels nothing",
        )
    }
}
