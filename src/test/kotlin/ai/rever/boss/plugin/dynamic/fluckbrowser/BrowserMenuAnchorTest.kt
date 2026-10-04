package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserContextMenuInfo
import ai.rever.boss.plugin.browser.BrowserMenuContext
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserMenuAnchorTest {
    @Test fun `old hosts keep the local cursor fallback`() {
        assertEquals(Point(12, 34), browserMenuAnchor(BrowserContextMenuInfo()) { Point(12, 34) }?.point)
        assertNull(browserMenuAnchor(BrowserContextMenuInfo()) { null })
    }

    @Test fun `detached remote click never falls back to the local cursor`() {
        val click = object : MouseEvent(JPanel(), MOUSE_PRESSED, 0, 0, 10, 10,
            10, 10, 1, true, BUTTON3), BrowserMenuContext {}
        assertNull(browserMenuAnchor(BrowserContextMenuInfo(menuContext = click)) {
            error("Detached remote click must not read the cursor")
        })
    }

    @Test fun `remote menu uses exact clicked owner and remains an owned popup above its page`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1")
        SwingUtilities.invokeAndWait {
            val owner = JFrame("Synthetic remote browser menu").apply {
                focusableWindowState = false
                contentPane = JPanel()
                setBounds(200, 200, 420, 300)
                isVisible = true
            }
            try {
                val point = Point(owner.x + 100, owner.y + 100)
                val click = object : MouseEvent(owner.contentPane, MOUSE_PRESSED, 0, 0, 100, 100,
                    point.x, point.y, 1, true, BUTTON3), BrowserMenuContext {}
                val info = BrowserContextMenuInfo(menuContext = click)
                val anchor = requireNotNull(browserMenuAnchor(info) { error("Must not read or move the system cursor") })
                assertEquals(point, anchor.point)
                assertSame(owner, anchor.owner)
                val topLevelClick = object : MouseEvent(owner, MOUSE_PRESSED, 0, 0, 100, 100,
                    point.x, point.y, 1, true, BUTTON3), BrowserMenuContext {}
                assertSame(owner, browserMenuAnchor(BrowserContextMenuInfo(menuContext = topLevelClick)) {
                    error("Window event source must use the exact owner")
                }?.owner)
                SwingContextMenu.show(point.x, point.y, listOf(ContextMenuItem(text = "Synthetic action", onClick = {})), owner = anchor.owner)
                val popup = owner.ownedWindows.single { it.isShowing }
                assertSame(owner, popup.owner)
                assertEquals(point, popup.locationOnScreen)
                val staleOwner = JFrame("Retired remote owner").apply { focusableWindowState = false }
                try {
                    var dismissed = false
                    SwingContextMenu.show(point.x, point.y, listOf(ContextMenuItem(text = "Stale", onClick = {})),
                        onDismiss = { dismissed = true }, owner = staleOwner)
                    assertTrue(dismissed)
                    assertFalse(popup.isShowing, "A stale request must close the previous menu")
                } finally {
                    staleOwner.dispose()
                }
                owner.isVisible = false
                assertNull(browserMenuAnchor(info) { error("A stale remote owner cannot fall back to another window") })
            } finally {
                SwingContextMenu.hide()
                owner.dispose()
            }
        }
    }
}
