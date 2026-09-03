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
            ),
            "the grace window is exclusive at its own boundary",
        )
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(isUserEditing = false, msSinceUserEdit = 0),
        )
    }

    @Test
    fun `a clock step backwards reads as a recent edit`() {
        // System.currentTimeMillis() is wall-clock and can move; erring toward "the user was just
        // typing" only ever declines to overwrite their text.
        assertFalse(
            AddressBarUrlField.navigationMayRewrite(isUserEditing = false, msSinceUserEdit = -5_000),
        )
    }

    @Test
    fun `selectAll covers the whole URL`() {
        val selected = AddressBarUrlField.selectAll(TextFieldValue("https://example.com", TextRange(19)))

        assertEquals(TextRange(0, 19), selected.selection)
        assertEquals("https://example.com", selected.text)
    }

    @Test
    fun `selectAll on an empty field is a collapsed selection, not a crash`() {
        // A tab on the home screen has no URL yet, and Cmd+L still has to be pressable there.
        val selected = AddressBarUrlField.selectAll(TextFieldValue(""))

        assertEquals(TextRange(0, 0), selected.selection)
    }
}
