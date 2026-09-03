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
        assertTrue(
            AddressBarUrlField.navigationMayRewrite(
                isUserEditing = false,
                msSinceUserEdit = AddressBarUrlField.USER_EDIT_GRACE_MS + 1,
                typedSinceClaim = false,
            ),
        )
    }

    @Test
    fun `claiming the field stops the navigation rewrite`() {
        // What the Cmd+L focus callback sets, and the whole reason it sets it BEFORE selecting.
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(
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
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(
                isUserEditing = false,
                msSinceUserEdit = AddressBarUrlField.USER_EDIT_GRACE_MS,
                typedSinceClaim = true,
            ),
            "the grace window is exclusive at its own boundary",
        )
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(
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
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(
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
        // navigationMayRewrite false - the URL bar silently stopped following navigations for
        // the life of the tab. These are the two values onCancelUrlEditing writes.
        val restored = AddressBarUrlField.restoreTo("https://example.com/page")

        assertEquals("https://example.com/page", restored.text)
        assertEquals(TextRange(24), restored.selection, "caret at the end, nothing selected")
        assertTrue(
            AddressBarUrlField.navigationMayRewrite(
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
        assertTrue(
            AddressBarUrlField.navigationMayRewrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS + 1,
                typedSinceClaim = false,
            ),
        )
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS,
                typedSinceClaim = false,
            ),
            "exclusive at its own boundary, like the grace window",
        )
    }

    @Test
    fun `a home tab's claim still goes stale`() {
        // The case an inferred "field still reads as the loaded URL" could not express: a home
        // tab holds "about:blank" while loadedUrl is deliberately "", so the strings never match
        // and the bound would never have fired there. Nothing was TYPED, so it fires.
        assertTrue(
            AddressBarUrlField.navigationMayRewrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS + 1,
                typedSinceClaim = false,
            ),
        )
    }

    @Test
    fun `a claim taken over an abandoned draft still goes stale`() {
        // The sharper one. Type "goo", click the page: onFocusLost releases the claim but leaves
        // the text. Press Cmd+L again and the claim is over text that does not match loadedUrl -
        // so an inferred version would never expire it, and the bar would be frozen for the life
        // of the tab. Cmd+L resets the flag, so what matters is that nothing was typed under
        // THIS claim.
        assertTrue(
            AddressBarUrlField.navigationMayRewrite(
                isUserEditing = true,
                msSinceUserEdit = AddressBarUrlField.UNTYPED_CLAIM_STALE_MS + 1,
                typedSinceClaim = false,
            ),
        )
    }

    @Test
    fun `text the user actually typed is never discarded, however stale`() {
        // The gate that keeps the staleness bound from becoming a data-loss bug: someone who
        // types a long URL and is interrupted for an hour still finds it there, because the
        // bound only ever releases a claim over text nothing has changed.
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(
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
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(
                isUserEditing = true,
                msSinceUserEdit = 5_000,
                typedSinceClaim = false,
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
