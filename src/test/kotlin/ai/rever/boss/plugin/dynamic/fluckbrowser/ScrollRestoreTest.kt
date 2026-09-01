package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the parse/serialize half of scroll restore - the pure functions, not the
 * capture-before-dispose ordering or the settle-and-reapply timing, which need a live
 * [ai.rever.boss.plugin.browser.BrowserHandle] to exercise and are covered by manual QA instead.
 */
class ScrollRestoreTest {

    @Test
    fun `parses a plain x,y pair`() {
        assertEquals(ScrollRestore.Position(120, 4500), ScrollRestore.parseCapture("120,4500"))
    }

    @Test
    fun `tolerates the same quoting variance busyStateFromScriptResult already documents`() {
        assertEquals(ScrollRestore.Position(0, 0), ScrollRestore.parseCapture("\"0,0\""))
        assertEquals(ScrollRestore.Position(10, 20), ScrollRestore.parseCapture("  10,20  "))
    }

    @Test
    fun `negative offsets parse (RTL pages can report negative scrollX)`() {
        assertEquals(ScrollRestore.Position(-50, 0), ScrollRestore.parseCapture("-50,0"))
    }

    @Test
    fun `malformed capture returns null rather than a wrong position`() {
        assertNull(ScrollRestore.parseCapture(null))
        assertNull(ScrollRestore.parseCapture(""))
        assertNull(ScrollRestore.parseCapture("not-a-number,5"))
        assertNull(ScrollRestore.parseCapture("only-one-part"))
        assertNull(ScrollRestore.parseCapture("1,2,3"))
    }

    @Test
    fun `isOrigin is true only at exactly 0,0`() {
        assertTrue(ScrollRestore.Position(0, 0).isOrigin)
        assertTrue(!ScrollRestore.Position(0, 1).isOrigin)
        assertTrue(!ScrollRestore.Position(1, 0).isOrigin)
    }

    @Test
    fun `restoreJs applies exactly the captured position`() {
        assertEquals("window.scrollTo(120, 4500)", ScrollRestore.restoreJs(ScrollRestore.Position(120, 4500)))
    }

    @Test
    fun `CAPTURE_JS degrades to the origin rather than throwing on a NaN scroll read`() {
        // Guards against a regression that drops the `||0` fallback, which would make a capture
        // on a sandboxed or about: page throw and lose the whole hibernation event's scroll data,
        // not just fail to capture a position.
        assertTrue("scrollX||0" in ScrollRestore.CAPTURE_JS)
        assertTrue("scrollY||0" in ScrollRestore.CAPTURE_JS)
    }
}
