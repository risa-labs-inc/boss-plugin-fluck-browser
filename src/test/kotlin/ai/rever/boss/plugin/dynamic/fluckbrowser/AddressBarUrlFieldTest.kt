package ai.rever.boss.plugin.dynamic.fluckbrowser

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The race Cmd+L's "claim the field before selecting it" exists to lose.
 *
 * Without it the sequence is: Cmd+L focuses and selects the URL, a navigation callback on a
 * still-loading or redirecting page fires a beat later, and it rewrites the field with a
 * COLLAPSED selection while the field keeps focus - so the user's next keystroke appends to the
 * URL instead of replacing it. Intermittent, load-timing dependent, and reported as "Cmd+L
 * sometimes doesn't select". [AddressBarUrlField] is the predicate and the write, split out of
 * the composable so the ordering is pinned somewhere.
 */
class AddressBarUrlFieldTest {
    @Test
    fun `a navigation may rewrite a field nobody is editing`() {
        assertEquals(
            AddressBarUrlField.NavigationWrite.CollapseCaret,
            AddressBarUrlField.navigationWrite(
                isUserEditing = false,
                msSinceUserEdit = AddressBarUrlField.USER_EDIT_GRACE_MS + 1,
                typedSinceClaim = false,
            ),
        )
    }

    @Test
    fun `claiming the field stops the navigation rewrite`() {
        // What the Cmd+L focus callback sets, and the whole reason it sets it BEFORE selecting.
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = true,
                msSinceUserEdit = 10_000,
                typedSinceClaim = true,
            ),
            "a field the user owns must not be rewritten however long ago they last typed",
        )
    }

    @Test
    fun `a recent edit stops the rewrite even once the flag is clear`() {
        // The Tab-completion buffer: onFocusLost clears the flag after a delay, and a navigation
        // landing inside the grace window must still leave the text alone.
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = false,
                msSinceUserEdit = AddressBarUrlField.USER_EDIT_GRACE_MS,
                typedSinceClaim = true,
            ),
            "the grace window is exclusive at its own boundary",
        )
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = false,
                msSinceUserEdit = 0,
                typedSinceClaim = true,
            ),
        )
    }

    @Test
    fun `a clock step backwards reads as a recent edit`() {
        // System.currentTimeMillis() is wall-clock and can move; erring toward "the user was just
        // typing" only ever declines to overwrite their text.
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = false,
                msSinceUserEdit = -5_000,
                typedSinceClaim = true,
            ),
        )
    }

    @Test
    fun `selectAll covers the whole URL`() {
        val selected = AddressBarUrlField.selectAll(TextFieldValue("https://example.com", TextRange(19)))

        assertEquals(TextRange(0, 19), selected.selection)
        assertEquals("https://example.com", selected.text)
    }

    @Test
    fun `Escape after Cmd+L hands the field back`() {
        // The whole point of restoreTo + clearing the claim. Cmd+L claims the field without the
        // user typing, so before this existed, Cmd+L then Escape left isUserEditingUrl true and
        // navigationWrite returning Skip - the URL bar silently stopped following navigations for
        // the life of the tab. These are the two values onCancelUrlEditing writes.
        val restored = AddressBarUrlField.restoreTo("https://example.com/page")

        assertEquals("https://example.com/page", restored.text)
        assertEquals(TextRange(24), restored.selection, "caret at the end, nothing selected")
        assertEquals(
            AddressBarUrlField.NavigationWrite.CollapseCaret,
            AddressBarUrlField.navigationWrite(
                isUserEditing = false,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS + 1,
                typedSinceClaim = false,
            ),
            "with the claim released and lastUserEditTime reset to 0, navigations track again",
        )
    }

    @Test
    fun `restoring an unloaded page yields an empty field, not a crash`() {
        // The pure function is total. Callers do NOT hand it a blank loadedUrl, though - Escape
        // on a home tab releases the claim and leaves the text alone, because blanking the field
        // is not reverting it.
        val restored = AddressBarUrlField.restoreTo("")

        assertEquals("", restored.text)
        assertEquals(TextRange(0), restored.selection)
    }

    @Test
    fun `an untyped claim nobody has touched in a minute stops blocking navigations`() {
        // Escape is not the only way out of a Cmd+L the user changed their mind about: clicking
        // into the page is the other, and that only releases the claim if a focus-changed event
        // arrives, which Windows/Linux HARDWARE_ACCELERATED may not produce. Without this bound
        // the URL bar silently stops following navigations for the life of the tab.
        assertEquals(
            AddressBarUrlField.NavigationWrite.KeepSelection,
            AddressBarUrlField.navigationWrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS + 1,
                typedSinceClaim = false,
            ),
        )
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS,
                typedSinceClaim = false,
            ),
            "exclusive at its own boundary, like the grace window",
        )
    }

    @Test
    fun `moving the caret is not typing`() {
        // Two keystrokes used to defeat the staleness bound entirely: Cmd+L takes the claim with
        // nothing typed, then Left arrow collapses the selection - and because BasicTextField's
        // TextFieldValue overload reports selection-only changes through onValueChange, the flag
        // was set with nothing typed. From there the bound could never fire and the URL bar
        // stopped following navigations for the life of the tab.
        assertFalse(
            AddressBarUrlField.typedUnderClaim(
                alreadyTyped = false,
                previous = "https://example.com",
                next = "https://example.com",
            ),
        )
    }

    @Test
    fun `textChanged compares the two strings it is handed`() {
        // A named predicate taking `previous` as a parameter, because the version that read the
        // previous value off shared state was placed AFTER the write to that state - so it
        // compared newValue against itself, was always false, and the history dropdown and
        // inline autocomplete stopped appearing at all. A caller cannot make that mistake here.
        assertTrue(AddressBarUrlField.textChanged(previous = "goo", next = "goog"))
        assertFalse(AddressBarUrlField.textChanged(previous = "goo", next = "goo"))
        assertTrue(AddressBarUrlField.textChanged(previous = "", next = "g"))
        assertTrue(AddressBarUrlField.textChanged(previous = "g", next = ""))
    }

    @Test
    fun `only a caret move leaves the suggestion dropdown alone`() {
        // All four combinations, because the first version of this gate got one of them wrong:
        // it skipped EVERY text-unchanged event, so Cmd+A no longer cleared the dropdown, and a
        // stale inline completion then changed what Enter did - navigating to a suggestion
        // computed for text the user had just selected over.
        assertTrue(
            AddressBarUrlField.suggestionsNeedUpdate(previous = "goo", next = "goog", selectionCollapsed = true),
            "typing recomputes",
        )
        assertTrue(
            AddressBarUrlField.suggestionsNeedUpdate(previous = "goo", next = "x", selectionCollapsed = false),
            "typing over a selection recomputes",
        )
        assertTrue(
            AddressBarUrlField.suggestionsNeedUpdate(previous = "goo", next = "goo", selectionCollapsed = false),
            "Cmd+A / shift-arrow must still clear - selecting the field is not a query, and a " +
                "stale completion left standing changes what Enter resolves",
        )
        assertFalse(
            AddressBarUrlField.suggestionsNeedUpdate(previous = "goo", next = "goo", selectionCollapsed = true),
            "a caret move is the one case worth skipping: same answer, and re-opening the " +
                "dropdown would cost Cmd+L then Escape its one-press release",
        )
    }

    @Test
    fun `typing under the claim sets it, and it stays set`() {
        assertTrue(
            AddressBarUrlField.typedUnderClaim(alreadyTyped = false, previous = "goo", next = "goog"),
        )
        // Sticky: only taking a fresh claim resets it, which is what makes "under the CURRENT
        // claim" the scope. A backspace back to the original text is still typing.
        assertTrue(
            AddressBarUrlField.typedUnderClaim(alreadyTyped = true, previous = "goo", next = "goo"),
        )
    }

    @Test
    fun `a home tab's field is not typing either`() {
        // The case an inferred "does the field read as the loaded URL" could not express: a home
        // tab holds "about:blank" while loadedUrl is deliberately "". Nothing typed is nothing
        // typed, whatever the field happens to hold.
        assertFalse(
            AddressBarUrlField.typedUnderClaim(
                alreadyTyped = false,
                previous = "about:blank",
                next = "about:blank",
            ),
        )
    }

    @Test
    fun `text typed under the standing claim is never discarded, however stale`() {
        // The gate that keeps the staleness bound from becoming a data-loss bug: someone who
        // types a long URL and is interrupted for an hour still finds it there, because the
        // bound only ever releases a claim nothing was typed under.
        //
        // Scope matters and is documented on UNTYPED_CLAIM_STALE_MS: this is "typed under the
        // CURRENT claim", so pressing Cmd+L over a draft starts a fresh one and makes that draft
        // staleable - deliberately, because Cmd+L selects the whole field, which is the user
        // saying they are about to replace it.
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = true,
                msSinceUserEdit = 60L * 60 * 1000,
                typedSinceClaim = true,
            ),
        )
    }

    @Test
    fun `Cmd+L's selection still survives a navigation seconds later`() {
        // The bound must not undo the rule it sits next to. A redirect landing a few seconds
        // after Cmd+L is the original bug: the field is claimed, unmodified, and must keep its
        // selection. Seconds, not a minute, is the window that matters here.
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = true,
                msSinceUserEdit = 5_000,
                typedSinceClaim = false,
            ),
        )
    }

    @Test
    fun `Escape reverts only an edit the user started`() {
        // Was three literal substrings asserted over source text, which would have failed the
        // moment the condition was extracted into this function - i.e. failed on an improvement.
        // Each term rules out a different way of making things worse.
        assertTrue(
            AddressBarUrlField.shouldRestore(
                isUserEditing = true,
                loadedUrl = "https://example.com",
                currentText = "exa",
            ),
        )
        assertFalse(
            AddressBarUrlField.shouldRestore(
                isUserEditing = false,
                loadedUrl = "https://example.com",
                currentText = "https://example.com/next",
            ),
            "mid-navigation the bar legitimately differs from loadedUrl with nobody editing - " +
                "reverting there puts the previous page's URL back until the load commits",
        )
        assertFalse(
            AddressBarUrlField.shouldRestore(isUserEditing = true, loadedUrl = "", currentText = "goo"),
            "loadedUrl is blank on a home tab, and blanking the field is not reverting it",
        )
        assertFalse(
            AddressBarUrlField.shouldRestore(
                isUserEditing = true,
                loadedUrl = "https://example.com",
                currentText = "https://example.com",
            ),
            "nothing to revert - rewriting would collapse Cmd+L's selection for no reason",
        )
    }

    @Test
    fun `a stale rewrite keeps the selection it is writing over`() {
        // Otherwise the staleness bound just defers the original bug by a minute: the page URL
        // lands with a collapsed caret while the field is still claimed and may still hold
        // focus, so the user comes back, types, and appends instead of replacing.
        assertEquals(
            AddressBarUrlField.NavigationWrite.KeepSelection,
            AddressBarUrlField.navigationWrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS + 1,
                typedSinceClaim = false,
            ),
        )
    }

    @Test
    fun `an ordinary rewrite collapses the caret, as every browser does`() {
        // The USER_EDIT_GRACE_MS path - nobody is editing, so there is no selection worth
        // keeping and a caret at the end is the normal behaviour.
        assertEquals(
            AddressBarUrlField.NavigationWrite.CollapseCaret,
            AddressBarUrlField.navigationWrite(
                isUserEditing = false,
                msSinceUserEdit = 10_000,
                typedSinceClaim = false,
            ),
        )
        // And a typed claim never reaches the staleness branch at all, so it never gets here.
        assertEquals(
            AddressBarUrlField.NavigationWrite.Skip,
            AddressBarUrlField.navigationWrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS + 1,
                typedSinceClaim = true,
            ),
        )
    }

    @Test
    fun `selectAll on an empty field is a collapsed selection, not a crash`() {
        // A tab on the home screen has no URL yet, and Cmd+L still has to be pressable there.
        val selected = AddressBarUrlField.selectAll(TextFieldValue(""))

        assertEquals(TextRange(0, 0), selected.selection)
    }
}
