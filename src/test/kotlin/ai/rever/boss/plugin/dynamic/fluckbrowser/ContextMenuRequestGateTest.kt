package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The request gate behind the bug this fixed: a right-click has to open a menu every time,
 * including the second one in a row, while a tab re-entering composition must not replay
 * the last one.
 *
 * These cover the arithmetic only — the reason right-click died in the first place was the
 * callback writing to a discarded `remember` slot, which lives in Compose's lifecycle and
 * is checked by the manual repro (right-click → switch tabs → switch back → right-click).
 */
class ContextMenuRequestGateTest {

    @Test
    fun `the first right-click opens a menu`() {
        assertTrue(shouldOpenContextMenu(request = 1, shownRequest = 0))
    }

    @Test
    fun `a second right-click opens again rather than being folded into the first`() {
        assertTrue(shouldOpenContextMenu(request = 2, shownRequest = 1))
    }

    @Test
    fun `re-entering composition does not replay the menu already shown`() {
        assertFalse(shouldOpenContextMenu(request = 7, shownRequest = 7))
    }

    @Test
    fun `a request whose run was cancelled before it opened is retried`() {
        // shownRequest is only advanced once the menu is actually shown, so a run cancelled
        // while loading secrets leaves the request eligible on the next pass.
        assertTrue(shouldOpenContextMenu(request = 4, shownRequest = 3))
    }

    @Test
    fun `the initial state opens nothing`() {
        assertFalse(shouldOpenContextMenu(request = 0, shownRequest = 0))
    }

    @Test
    fun `a closing menu clears only its own target`() {
        assertTrue(shouldClearContextMenuTarget(dismissedRequest = 3, currentRequest = 3))
    }

    @Test
    fun `a closing menu does not clear the target of the one replacing it`() {
        // Showing a new menu hides the old one, whose dismiss handler runs after the new
        // target is already in place — clearing it there would drop the incoming menu.
        assertFalse(shouldClearContextMenuTarget(dismissedRequest = 3, currentRequest = 4))
    }
}
