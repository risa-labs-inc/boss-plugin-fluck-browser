package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Compose half of Cmd+L, which no unit test can reach.
 *
 * Deliberately SMALL, and weaker than it looks. A source-text assertion fails when code is
 * reformatted or extracted - i.e. on improvements - so it earns its place only where deleting a
 * line would silently break behaviour and nothing else would notice. Anything that could be a
 * unit test has been moved to one: the Escape revert condition lives in
 * `AddressBarUrlField.shouldRestore`, and this class no longer asserts on it.
 *
 * What these assertions do NOT prove, so nobody over-trusts them: the argument checks scan the
 * whole file, so they pass while SOME non-literal is passed to SOME assignment of that name, not
 * specifically the one that matters; and the requester check is textual, so it would still pass
 * if that modifier moved to a different composable in this file. They catch deletion, which is
 * the failure mode with no other witness. They do not verify wiring.
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

    /** [code] with every run of whitespace squeezed to one space, so layout stops mattering. */
    private fun normalised(code: String): String = code.replace(Regex("""\s+"""), " ")

    /**
     * The braced block that follows [anchor], found by counting braces to the match.
     *
     * Neither `substringBefore("},")` nor a character budget: the first is coupled to ktlint's
     * brace placement rather than to the code, and the second silently reads into whatever comes
     * next when a block grows. Returns "" if the anchor is absent, so the assertion fails on its
     * own message rather than on an exception.
     */
    private fun blockAfter(
        anchor: String,
        code: String,
    ): String {
        // The first occurrence is not necessarily the one wanted: `AddressBarRegistration(`
        // matches its own `private fun` declaration before it matches the call. Skip declarations
        // rather than trusting declaration order, which is exactly the fragility this replaced.
        val at =
            generateSequence(code.indexOf(anchor)) { previous ->
                code.indexOf(anchor, previous + 1).takeIf { it >= 0 }
            }.takeWhile { it >= 0 }
                .firstOrNull { !code.substring(maxOf(0, it - 40), it).contains("fun ") }
                ?: return ""
        val open = code.indexOf('{', at)
        if (open < 0) return ""
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open + 1, i)
            }
        }
        return ""
    }

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
        // The far end of the same wire, and it DOES pin the identifier - deliberately, after a
        // round of getting this wrong in both directions. The loose version (any
        // `.focusRequester(x)` in the file) passed with the URL field's line deleted, so it
        // asserted nothing; naming the requester is what makes this "the right field attaches
        // the right one". The cost is that a rename fails the test, which is a one-line fix,
        // against a silent dead Cmd+L, which is not.
        assertTrue(
            code.contains(Regex("""\.focusRequester\s*\(\s*addressBarFocusRequester\s*\)""")),
            "the URL field no longer attaches the requester - requestFocus would throw on every " +
                "Cmd+L and the user would get a throttled log line and nothing else",
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

        // Neither a local's name nor a one-line layout: rewrapping the condition changes no
        // behaviour, which is the same objection this file makes for panelActive. What has to
        // hold is that the generation is both bumped and read.
        assertTrue(
            code.contains(Regex("""claimGeneration\.incrementAndGet\(\)""")),
            "nothing bumps the claim generation, so no release can tell whose claim it holds",
        )
        assertTrue(
            code.contains(Regex("""claimGeneration\.get\(\)""")),
            "nothing reads the claim generation - the delayed focus-loss release can wipe a " +
                "fresh Cmd+L claim",
        )
        assertTrue(
            argumentsPassedTo("releasing", code).isNotEmpty() ||
                code.contains(Regex("""!=\s*releasing""")),
            "the delayed release no longer compares against the claim it was scheduled for",
        )
    }

    @Test
    fun `Cmd+L clears the dropdown state it invalidates`() {
        // The two-stage Escape leans on "Cmd+L opens no dropdown", which is only true if Cmd+L
        // also CLOSES one left over from typing. Without this, the first Escape dismissed a stale
        // dropdown instead of releasing the claim, Enter navigated to a suggestion computed from
        // the pre-Cmd+L text, and the arrow keys walked a list that no longer matched the field.
        val code = tabComponentCode()
        // Anchored on AddressBarRegistration, not on the first `claimGeneration.incrementAndGet()`
        // - there are two of those (here and onUrlBarTextChange) and "first" is only the right
        // one by declaration order.
        val callback = normalised(blockAfter("AddressBarRegistration(", code))

        assertTrue(
            callback.contains("showUrlSuggestions = false"),
            "Cmd+L no longer closes a stale dropdown - the two-stage Escape needs two presses",
        )
        assertTrue(
            callback.contains("autocompleteSuggestion = null"),
            "Cmd+L leaves a stale inline completion - Enter would navigate to the wrong URL",
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
