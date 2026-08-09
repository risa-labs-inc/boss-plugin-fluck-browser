package ai.rever.boss.plugin.dynamic.fluckbrowser.menu

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers what a headless run can see: the menu plan, invoker selection, the dismissal predicate
 * and the platform gate. No AWT menu is constructed - `java.awt.MenuComponent` throws
 * `HeadlessException`, which is exactly why those steps are toolkit-independent.
 */
class NativeContextMenuTest {
    private fun item(label: String) = NativeMenuNode.Item(label)

    private fun labels(nodes: List<NativeMenuNode>) =
        nodes.map {
            when (it) {
                is NativeMenuNode.Item -> it.label
                is NativeMenuNode.Submenu -> "${it.label}>"
                NativeMenuNode.Separator -> "---"
            }
        }

    @Test
    fun `items keep their label and order`() {
        val plan = planNativeMenu(listOf(item("Back"), item("Forward"), item("Reload")))
        assertEquals(listOf("Back", "Forward", "Reload"), labels(plan))
    }

    @Test
    fun `leading, trailing and consecutive separators are dropped`() {
        // The browser menu is assembled conditionally - link, image, selection and editable
        // sections each append a divider - so an ordinary right-click on blank page area would
        // otherwise emit several in a row.
        val plan =
            planNativeMenu(
                listOf(
                    NativeMenuNode.Separator,
                    item("Back"),
                    NativeMenuNode.Separator,
                    NativeMenuNode.Separator,
                    item("Reload"),
                    NativeMenuNode.Separator,
                ),
            )
        assertEquals(listOf("Back", "---", "Reload"), labels(plan))
    }

    @Test
    fun `a menu of only separators plans to nothing, so show reports failure`() {
        assertTrue(planNativeMenu(List(3) { NativeMenuNode.Separator }).isEmpty())
        assertFalse(NativeContextMenu.show(0, 0, List(3) { NativeMenuNode.Separator }))
    }

    @Test
    fun `ampersands are doubled on windows only`() {
        // Page titles and link text routinely contain an ampersand.
        assertEquals(
            "Tom && Jerry",
            (planNativeMenu(listOf(item("Tom & Jerry")), isWindows = true).single() as NativeMenuNode.Item).label,
        )
        assertEquals(
            "Tom & Jerry",
            (planNativeMenu(listOf(item("Tom & Jerry")), isWindows = false).single() as NativeMenuNode.Item).label,
        )
    }

    @Test
    fun `actions survive planning`() {
        var fired = 0
        val plan = planNativeMenu(listOf(NativeMenuNode.Item("Copy Link", action = { fired += 1 })))
        (plan.single() as NativeMenuNode.Item).action()
        assertEquals(1, fired)
    }

    // ----- invoker selection -----

    private fun candidate(
        name: String,
        active: Boolean = false,
        x: Int = 0,
        y: Int = 0,
        w: Int = 100,
        h: Int = 100,
        frameOrDialog: Boolean = true,
        showing: Boolean = true,
    ) = InvokerCandidate(name, frameOrDialog, showing, active, Rectangle(x, y, w, h))

    @Test
    fun `among inactive windows the smallest wins, not the largest`() {
        // A fullscreen browser window covering a smaller main frame is the case this inverts on.
        val picked =
            pickInvoker(
                listOf(candidate("fullscreen", w = 3000, h = 2000), candidate("small", w = 400, h = 300)),
                at = null,
            )
        assertEquals("small", picked?.window)
    }

    @Test
    fun `popup and hidden windows are not eligible invokers`() {
        // getWindows() also returns the heavyweight windows Swing makes for popups, including the
        // menu being replaced.
        val picked =
            pickInvoker(
                listOf(
                    candidate("popup", frameOrDialog = false, w = 10, h = 10),
                    candidate("hidden", showing = false, w = 20, h = 20),
                    candidate("frame", w = 900, h = 900),
                ),
                at = null,
            )
        assertEquals("frame", picked?.window)
    }

    @Test
    fun `only windows containing the click point are eligible`() {
        val picked =
            pickInvoker(
                listOf(
                    candidate("left", x = 0, y = 0, w = 100, h = 100),
                    candidate("right", x = 500, y = 500, w = 300, h = 300),
                ),
                at = Point(600, 600),
            )
        assertEquals("right", picked?.window)
    }

    @Test
    fun `a click on the half-open right or bottom edge still finds a window`() {
        // Rectangle.contains is half-open, so (100,100) is NOT inside a 0,0 100x100 frame.
        // Without the fallback this returns null and the right-click silently does nothing.
        val picked = pickInvoker(listOf(candidate("frame", w = 100, h = 100)), at = Point(100, 100))
        assertEquals("frame", picked?.window)
    }

    @Test
    fun `no eligible window yields null rather than an arbitrary one`() {
        assertNull(pickInvoker(listOf(candidate("hidden", showing = false)), at = null))
        assertNull(pickInvoker(emptyList<InvokerCandidate<String>>(), at = null))
    }

    // ----- dismissal and the platform gate -----

    @Test
    fun `nothing inside the grace window counts as dismissal`() {
        val grace = NativeContextMenu.dismissGraceMs
        assertFalse(NativeContextMenu.isDismissalEvent(java.awt.event.MouseEvent.MOUSE_MOVED, 0))
        assertFalse(NativeContextMenu.isDismissalEvent(java.awt.event.MouseEvent.MOUSE_MOVED, grace - 1))
    }

    @Test
    fun `an ordinary event after the grace window counts, a mouse release never does`() {
        val grace = NativeContextMenu.dismissGraceMs
        assertTrue(NativeContextMenu.isDismissalEvent(java.awt.event.MouseEvent.MOUSE_MOVED, grace))
        assertFalse(NativeContextMenu.isDismissalEvent(java.awt.event.MouseEvent.MOUSE_RELEASED, 60_000))
    }

    @Test
    fun `only macOS gets native menus`() {
        assertTrue(shouldUseNativeMenus(settingEnabled = true, isMacOs = true))
        assertFalse(shouldUseNativeMenus(settingEnabled = false, isMacOs = true))
        assertFalse(shouldUseNativeMenus(settingEnabled = true, isMacOs = false))
    }

    // ----- the return contract -----

    @Test
    fun `show declines off the EDT rather than reporting a menu it cannot confirm`() {
        // The caller picks between this and its own menu from the return value, so reporting
        // success before knowing a menu appears would leave the right-click doing nothing.
        // This test thread is not the EDT.
        assertFalse(NativeContextMenu.show(0, 0, listOf(item("Back"))))
    }

    @Test
    fun `show declines an empty plan`() {
        assertFalse(NativeContextMenu.show(0, 0, emptyList()))
        assertFalse(NativeContextMenu.show(0, 0, List(2) { NativeMenuNode.Separator }))
    }

    @Test
    fun `hide is safe with nothing attached and does not throw`() {
        // Callers invoke hide() from teardown that may run when no menu was ever shown.
        NativeContextMenu.hide()
        NativeContextMenu.hide()
    }

    // ----- the focused-window shortcut -----

    @Test
    fun `a focused window that does not contain the click is not eligible`() {
        // The case the shortcut used to get wrong: two BOSS windows, focus on one, right-click
        // in the other. Using the focused window subtracts the wrong origin and the menu lands
        // far from the pointer. pickInvoker encodes the rule the shortcut must also satisfy.
        val picked =
            pickInvoker(
                listOf(
                    candidate("focused-elsewhere", active = true, x = 0, y = 0, w = 100, h = 100),
                    candidate("under-the-click", x = 500, y = 500, w = 300, h = 300),
                ),
                at = Point(600, 600),
            )
        assertEquals("under-the-click", picked?.window)
    }

    @Test
    fun `a popup window is never eligible even when it is the active one`() {
        // getWindows() returns the heavyweight windows Swing makes for popups, including the
        // menu being replaced; one of those becoming the invoker nests a menu inside a menu.
        val picked =
            pickInvoker(
                listOf(
                    candidate("popup", active = true, frameOrDialog = false),
                    candidate("frame", w = 900, h = 900),
                ),
                at = null,
            )
        assertEquals("frame", picked?.window)
    }
    // ----- the show() decision, testable off macOS -----

    @Test
    fun `a native menu needs support, the EDT and a non-empty plan`() {
        assertTrue(canShowNatively(isSupported = true, isEventDispatchThread = true, plannedSize = 1))
    }

    @Test
    fun `each precondition alone is enough to decline`() {
        // Going through show() on a Linux runner only ever re-tests the platform gate, so these
        // guards need coverage that actually runs everywhere.
        assertFalse(canShowNatively(isSupported = false, isEventDispatchThread = true, plannedSize = 1))
        assertFalse(canShowNatively(isSupported = true, isEventDispatchThread = false, plannedSize = 1))
        assertFalse(canShowNatively(isSupported = true, isEventDispatchThread = true, plannedSize = 0))
    }

    @Test
    fun `a menu of only separators plans to nothing and so declines`() {
        val planned = planNativeMenu(List(3) { NativeMenuNode.Separator })
        assertFalse(
            canShowNatively(isSupported = true, isEventDispatchThread = true, plannedSize = planned.size),
        )
    }
}
