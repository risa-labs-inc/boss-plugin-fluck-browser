package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the guard for the one tab class where hibernation's justification is false.
 *
 * The feature's own KDoc argues "the cost of being wrong is a reload on return, not lost work".
 * For a tab holding unsubmitted user input that is exactly backwards: the reload IS the lost
 * work - a half-written order form, text typed and not sent. These tests pin the three things
 * that make the guard hold, each of which could regress silently on its own.
 */
class UserInputGuardTest {
    private val INPUT = TabHibernation.BusyState.USER_INPUT
    private val IDLE = TabHibernation.BusyState.IDLE

    // region mapping

    @Test
    fun `the script's 'input' answer maps to USER_INPUT`() {
        assertEquals(INPUT, TabHibernation.busyStateFromScriptResult("input"))
        // executeJavaScript implementations differ on whether a JS string arrives quoted.
        assertEquals(INPUT, TabHibernation.busyStateFromScriptResult("\"input\""))
        assertEquals(INPUT, TabHibernation.busyStateFromScriptResult(" INPUT "))
    }

    // endregion

    // region script ordering — the mechanism, not a style preference

    /**
     * 'input' must be answered after 'media' and before 'shown'.
     *
     * After 'media': the audible-first guarantee ("hibernation never cuts audio") is documented
     * as load-bearing in busyStateFor, and this guard must not insert itself ahead of it.
     *
     * Before 'shown': busyStateFor DOWNGRADES 'shown' to IDLE when the host answers "not popped
     * out". A page that is both shown-inferred and dirty must therefore have already returned
     * 'input', or that downgrade hibernates a tab holding the user's typing - the exact loss
     * this guard exists to prevent, reintroduced by reason-reporting order alone.
     */
    @Test
    fun `input is answered after media and before the shown inference`() {
        val script = TabHibernation.BUSY_SCRIPT
        val mediaReturn = script.indexOf("return 'media'")
        val inputReturn = script.indexOf("return 'input'")
        val shownReturn = script.indexOf("return 'shown'")
        assertTrue(mediaReturn >= 0 && inputReturn >= 0 && shownReturn >= 0, "all three answers must exist:\n$script")
        assertTrue(mediaReturn < inputReturn, "media must be answered before input:\n$script")
        assertTrue(inputReturn < shownReturn, "input must be answered before the shown inference:\n$script")
    }

    /**
     * The probe must READ the marker, never inspect the DOM. Each pin below names a decision
     * whose reversal quietly reintroduces one of the three withdrawn attempts (d1552be):
     * value-vs-default diffing exempted every SPA, and defaultValue reads an autofilled
     * password as typed. String pins, matching how this suite already asserts on BUSY_SCRIPT.
     */
    @Test
    fun `the probe reads the marker and does not inspect the DOM`() {
        val script = TabHibernation.BUSY_SCRIPT
        assertTrue(DirtyInputMarker.DIRTY_FLAG_PROPERTY in script, "the probe reads DirtyInputMarker's flag")
        assertTrue("defaultValue" !in script, "value-vs-default diffing was withdrawn in d1552be; do not reintroduce it")
        assertTrue("defaultChecked" !in script, "value-vs-default diffing was withdrawn in d1552be; do not reintroduce it")
    }

    /**
     * Setter (INSTALL_JS) and reader (BUSY_SCRIPT) must reference the SAME property name.
     * Previously two independent hardcoded literals; a rename of one and not the other would
     * desync them with no compile error and no test failure outside this one - the reader would
     * simply never see the flag set, and busyStateFromScriptResult treats any unrecognized
     * result as IDLE, so the whole guard would go silently dead.
     */
    @Test
    fun `setter and reader share one flag name, not two independent literals`() {
        assertTrue(
            DirtyInputMarker.INSTALL_JS.contains(DirtyInputMarker.DIRTY_FLAG_PROPERTY),
            "INSTALL_JS must reference the shared constant",
        )
        assertTrue(
            TabHibernation.BUSY_SCRIPT.contains(DirtyInputMarker.DIRTY_FLAG_PROPERTY),
            "BUSY_SCRIPT must reference the shared constant",
        )
    }

    /**
     * What arms the marker: real keystrokes only, in the capture phase, on editable targets.
     * isTrusted is the load-bearing pin - it is the property that makes autofill and framework
     * writes (the two documented failure modes) structurally undetectable as typing via those
     * paths (CDP-level input injection is a separate, accepted, out-of-scope boundary - see
     * DirtyInputMarker's KDoc rather than this test, which cannot observe that distinction).
     */
    @Test
    fun `the marker arms on trusted keystrokes in the capture phase`() {
        val js = DirtyInputMarker.INSTALL_JS
        assertTrue("isTrusted" in js, "only a real user event may set the flag")
        assertTrue("isContentEditable" in js, "rich-text editors are typing too - the DOM approach could never cover them")
        assertTrue("'keydown', mark, true" in js, "capture phase, so a page stopping propagation cannot hide typing")
        assertTrue("'paste'" in js && "'cut'" in js, "paste and cut are typing by other means")
        assertTrue("'Enter'" !in js, "Enter usually IS the submit; marking dirty on the keystroke that saves the work inverts the guard")
        assertTrue(
            "typeof window.${DirtyInputMarker.DIRTY_FLAG_PROPERTY} !== 'undefined'" in js,
            "the flag doubles as the install guard - reinstalling must not clear it",
        )
    }

    /**
     * IME composition (CJK/Korean input methods) reports `key === 'Process'` on every keydown in
     * the sequence - none of them satisfy the length-1-or-edit-key filter above, so without this
     * a user composing a whole paragraph through an IME would leave the flag unset and hibernate
     * mid-draft, silently, for an entire class of users. `compositionstart` is a real, user-driven
     * signal with no autofill or programmatic-value-assignment equivalent, so adding it does not
     * reopen the autofill hole the keydown filter exists to avoid.
     */
    @Test
    fun `IME composition start also arms the marker, independently of the keydown filter`() {
        val js = DirtyInputMarker.INSTALL_JS
        assertTrue("compositionstart" in js, "IME composition must arm the marker - it fires no matching keydown")
        assertTrue(
            "addEventListener('compositionstart', mark, true)" in js,
            "must go through the same trusted, capture-phase, editable-target checks as keydown",
        )
    }

    /**
     * A modifier held alongside a printable key is a shortcut (Cmd+A/C/X, Ctrl+A/C/X), never a
     * character insertion - real typing never carries ctrl/meta/alt. Without this guard,
     * select-all-then-copy on a filled form would mark it dirty for a shortcut that inserted
     * nothing, exempting the tab from hibernation for no typing at all.
     */
    @Test
    fun `a modifier held alongside a printable key is a shortcut, not a keystroke`() {
        val js = DirtyInputMarker.INSTALL_JS
        assertTrue(
            "e.ctrlKey || e.metaKey || e.altKey" in js,
            "keydown must exclude ctrl/meta/alt-modified keys - those are shortcuts, not typing",
        )
    }

    /**
     * A trusted `submit` resets the flag. Without this, one keystroke into a search box exempts a
     * long-lived SPA tab (Gmail/Slack/Jira-shaped apps) from hibernation for its entire document
     * lifetime - the exact class of heavy, long-lived renderer hibernation exists to reclaim.
     * Capture phase and `isTrusted`-gated for the same reason the mark listeners are: a page's
     * own submit handler must not be able to fake or suppress this from either direction.
     */
    @Test
    fun `a trusted submit clears the flag, so a later edit can re-arm it`() {
        val js = DirtyInputMarker.INSTALL_JS
        assertTrue(
            "addEventListener('submit', clear, true)" in js,
            "submit must be a trusted, capture-phase listener like the mark listeners",
        )
        val clearBody = js.substringAfter("var clear = function(e){").substringBefore("};")
        assertTrue("isTrusted" in clearBody, "the clear listener must gate on isTrusted like every other listener here")
        assertTrue(
            "window.${DirtyInputMarker.DIRTY_FLAG_PROPERTY} = 0" in clearBody,
            "the submit listener must reset the flag, not just observe it",
        )
    }

    // endregion

    // region wait-out behaviour

    @Test
    fun `a dirty tab is waited out, then hibernates once clean`() = runBlocking {
        var probes = 0
        val waits = mutableListOf<Long>()
        val outcome =
            TabHibernation.awaitQuiet(
                probe = { if (probes++ < 2) INPUT else IDLE },
                onWait = { waits.add(it) },
            )
        assertEquals(IDLE, outcome, "a tab whose input was submitted or cleared should hibernate")
        assertEquals(2, waits.size)
    }

    /**
     * Input does not drain on its own the way playback does, so this is the common ending, not
     * the edge: the tab keeps its process tree until the user comes back to it. That is the
     * intended trade - the same one SHOWN_IN_POP_OUT makes for calls. If this test starts
     * failing because someone capped the exemption, the user's draft is what pays.
     */
    @Test
    fun `a tab still dirty at the recheck limit is left alone, not hibernated`() = runBlocking {
        val outcome = TabHibernation.awaitQuiet(probe = { INPUT }, onWait = {}, maxRechecks = 3)
        assertEquals(INPUT, outcome)
    }

    // endregion
}
