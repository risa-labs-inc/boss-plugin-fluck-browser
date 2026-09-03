package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [AddressBarFocusRegistry] — who answers Cmd+L.
 *
 * A plugin shortcut is GLOBAL in the host's contract: it fires with only a window id, whatever
 * has focus. Getting the resolution wrong is not a no-op, it is focus being yanked out from
 * under the user into a browser tab in some other split, so the ranking is worth pinning.
 */
class AddressBarFocusRegistryTest {
    private val focused = mutableListOf<String>()

    @BeforeTest
    @AfterTest
    fun reset() {
        AddressBarFocusRegistry.clear()
        focused.clear()
    }

    private fun register(
        tabId: String,
        windowId: String,
        panelActive: Boolean = true,
    ): Any = AddressBarFocusRegistry.register(tabId, windowId, panelActive) { focused.add(tabId) }

    @Test
    fun `no composed toolbar means the chord goes unhandled`() {
        assertFalse(AddressBarFocusRegistry.focusActiveIn("window-1"))
        assertTrue(focused.isEmpty())
    }

    @Test
    fun `a null window id is not a wildcard`() {
        register("tab-1", "window-1")

        // The host passes null when it cannot attribute the keypress to a window. Focusing
        // "whatever we can find" would be worse than doing nothing.
        assertFalse(AddressBarFocusRegistry.focusActiveIn(null))
        assertTrue(focused.isEmpty())
    }

    @Test
    fun `focuses the only toolbar in the window`() {
        register("tab-1", "window-1")

        assertTrue(AddressBarFocusRegistry.focusActiveIn("window-1"))
        assertEquals(listOf("tab-1"), focused)
    }

    @Test
    fun `never reaches into another window`() {
        register("other-window-tab", "window-2")

        assertFalse(AddressBarFocusRegistry.focusActiveIn("window-1"))
        assertTrue(focused.isEmpty())
    }

    @Test
    fun `the active panel wins over a more recently composed inactive one`() {
        register("in-active-panel", "window-1", panelActive = true)
        // Composed later, so it has the higher sequence — but the user is not in that panel.
        register("in-background-panel", "window-1", panelActive = false)

        assertTrue(AddressBarFocusRegistry.focusActiveIn("window-1"))
        assertEquals(listOf("in-active-panel"), focused)
    }

    @Test
    fun `a browser only in a background split does not answer`() {
        // The user is in another panel — an editor, say, where Cmd+L is Go To Line. The host
        // consumes the chord for whichever provider owns it, so answering here would both steal
        // focus into a browser they are not looking at and kill the binding they meant to use.
        register("in-background-panel", "window-1", panelActive = false)

        assertFalse(AddressBarFocusRegistry.focusActiveIn("window-1"))
        assertTrue(focused.isEmpty())
    }

    @Test
    fun `among equals the most recently composed wins`() {
        // LocalIsPanelActive defaults to true outside a managed panel, so ties are normal and
        // the tie-break has to be the thing the user most recently brought to the front.
        register("older", "window-1", panelActive = true)
        register("newer", "window-1", panelActive = true)

        assertTrue(AddressBarFocusRegistry.focusActiveIn("window-1"))
        assertEquals(listOf("newer"), focused)
    }

    @Test
    fun `disposing a toolbar stops it answering`() {
        val token = register("tab-1", "window-1")
        AddressBarFocusRegistry.unregister("tab-1", token)

        assertFalse(AddressBarFocusRegistry.focusActiveIn("window-1"))
        assertEquals(0, AddressBarFocusRegistry.size())
    }

    @Test
    fun `a stale token cannot evict the current registration`() {
        // A tab moving between windows builds one composition and tears down the other in an
        // order this registry does not control, and both carry the same tab id. An
        // unconditional remove from the outgoing one would delete the incoming one's entry and
        // leave the window with nothing to focus.
        val staleToken = register("tab-1", "window-1")
        register("tab-1", "window-2") // the move completed; window-2 now owns this tab

        AddressBarFocusRegistry.unregister("tab-1", staleToken)

        assertTrue(AddressBarFocusRegistry.focusActiveIn("window-2"), "the live registration survived")
        assertEquals(listOf("tab-1"), focused)
    }

    @Test
    fun `a throwing focus callback is contained`() {
        // requestFocus throws when the requester is not attached to a node — a hibernated tab,
        // or a toolbar hidden by fullscreen. That must not escape into the host's event dispatch.
        AddressBarFocusRegistry.register("tab-1", "window-1", panelActive = true) {
            error("not attached to the composition")
        }

        assertFalse(AddressBarFocusRegistry.focusActiveIn("window-1"))
    }
}
