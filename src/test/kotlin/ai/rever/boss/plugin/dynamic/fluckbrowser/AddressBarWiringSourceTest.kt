package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Every right-hand side assigned to [name] in [code], as written.
     *
     * A capture rather than a negative lookahead: `\s*` backtracks, so `(?!true|false)` placed
     * after it matches ` false` perfectly happily - which is how the first version of these
     * assertions passed against a hardcoded literal.
     */
    private fun argumentsPassedTo(
        name: String,
        code: String,
    ): List<String> =
        Regex(Regex.escape(name) + """\s*=\s*([A-Za-z0-9_.]+)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()

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
        // "not a literal" rather than a pinned variable name: a local rename changes nothing
        // about the behaviour, and hardcoding true/false is the edit that breaks it. Captured
        // and compared rather than expressed as a negative lookahead, because `\s*` backtracks -
        // `(?!true|false)` after it happily matches ` false`.
        assertEquals(
            emptyList(),
            argumentsPassedTo("panelActive", code).filter { it == "true" || it == "false" },
            "panelActive is passed a literal - a background split could answer Cmd+L",
        )
    }

    @Test
    fun `the requester reaches the toolbar and the URL field`() {
        // The two ends of the same wire. Either one missing leaves the registry calling
        // requestFocus on a requester attached to nothing, which throws, is caught, and looks
        // exactly like "Cmd+L did nothing".
        val code = tabComponentCode()

        // The PARAMETER name is BrowserToolbar's signature and fair to pin; the local handed to
        // it is not - a rename there changes no behaviour, which is the same argument this file
        // makes for panelActive.
        val threaded = argumentsPassedTo("addressBarFocusRequester", code)
        assertTrue(
            threaded.any { it != "null" },
            "BrowserToolbar is no longer given a requester ($threaded) - the field it focuses is " +
                "not the one on screen",
        )
        assertTrue(
            code.contains(Regex("""\.focusRequester\(\s*[A-Za-z_][A-Za-z0-9_.]*\s*\)""")),
            "the URL field no longer attaches a requester - requestFocus would throw on every Cmd+L",
        )
    }

    @Test
    fun `the staleness gate is fed the tracked flag, not a literal`() {
        // Pass a literal here and one of two fixes silently dies: `true` and a redirect collapses
        // Cmd+L's selection again, `false` and an abandoned claim never expires. The flag also
        // has to actually be maintained, so something must set it when the user types.
        val code = tabComponentCode()

        // The gate has to be fed something computed - `true` and a redirect collapses Cmd+L's
        // selection again, `false` and an abandoned claim never expires. The assignments that
        // MAINTAIN the flag are literals by nature, so this asserts that at least one
        // non-literal is passed, plus that something still sets it.
        val passed = argumentsPassedTo("typedSinceClaim", code)
        assertTrue(
            passed.any { it != "true" && it != "false" },
            "the staleness gate is only ever fed literals ($passed) - either the selection or " +
                "the stuck-claim fix is now broken",
        )
        assertTrue(
            passed.contains("true"),
            "nothing sets typedSinceClaim, so typed text would be treated as an abandoned claim",
        )
    }

    @Test
    fun `the delayed focus-loss release still checks it owns the claim`() {
        // onFocusLost releases from a coroutine after 200ms and nothing cancels that job. Without
        // the generation check, a Cmd+L pressed inside that window has its fresh claim wiped by
        // the previous one's release, and the next navigation collapses the selection it just
        // made - the claim-before-select ordering undone from behind.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""val\s+releasing\s*=\s*claimGeneration\.get\(\)""")),
            "the focus-loss release no longer captures the claim it was scheduled for",
        )
        assertTrue(
            code.contains(Regex("""claimGeneration\.get\(\)\s*!=\s*releasing""")),
            "the delayed release no longer checks it still owns the claim - a fresh Cmd+L can be " +
                "wiped by the previous claim's release",
        )
        assertTrue(
            code.contains(Regex("""claimGeneration\.incrementAndGet\(\)""")),
            "nothing bumps the claim generation, so the check above can never fire",
        )
    }

    @Test
    fun `Escape does not blank or collapse a field with nothing to revert`() {
        // The guard standing between Escape and a blanked address bar on a home tab, where
        // loadedUrl is deliberately "". Compose-side, so no unit test reaches it, and it is the
        // only thing making restoreTo safe to call from there.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""loadedUrl\.isNotBlank\(\)\s*&&\s*urlBarText\.text\s*!=\s*loadedUrl""")),
            "Escape can now blank the bar on a home tab, or collapse the selection on a field " +
                "that already reads as the loaded URL",
        )
    }

    @Test
    fun `a tab that cannot register says so`() {
        // Otherwise "Cmd+L never works in this tab" is indistinguishable in a log from "no
        // toolbar in the active panel" - and only one of those is a configuration problem.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""noteUnregisterable\(""")),
            "a tab that cannot register its toolbar is silent again",
        )
    }

    @Test
    fun `Escape still hands a claimed field back`() {
        // Cmd+L claims the field without the user typing, so the Escape branch is the only thing
        // stopping a change of mind freezing the URL bar for the life of the tab.
        val code = tabComponentCode()

        assertTrue(
            code.contains(Regex("""onCancelUrlEditing\s*\(\s*\)""")),
            "nothing calls onCancelUrlEditing - a Cmd+L the user backs out of leaves the bar claimed",
        )
        assertTrue(
            code.contains(Regex("""onCancelUrlEditing\s*=""")),
            "nothing supplies onCancelUrlEditing, so Escape cancels nothing",
        )
    }
}
