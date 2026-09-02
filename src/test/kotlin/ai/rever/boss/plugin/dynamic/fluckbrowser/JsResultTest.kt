package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one normalizer now shared by [TabHibernation.busyStateFromScriptResult],
 * [CredentialSuggestions]'s login-field probe parser, and [ScrollRestore.parseCapture] - a
 * regression here breaks all three call sites at once, which is the point of sharing it.
 */
class JsResultTest {
    @Test
    fun `passes an unquoted string through unchanged`() {
        assertEquals("media", normalizeJsStringResult("media"))
    }

    @Test
    fun `strips a single layer of surrounding quotes`() {
        assertEquals("media", normalizeJsStringResult("\"media\""))
    }

    @Test
    fun `trims outside whitespace, then inside-quote whitespace after stripping`() {
        assertEquals("media", normalizeJsStringResult("  media  "))
        // Whitespace INSIDE the quotes only becomes visible once the quotes themselves are gone -
        // this is why the trim is applied a second time, after the strip, not folded into one call.
        assertEquals("media", normalizeJsStringResult("\" media \""))
    }

    @Test
    fun `null in, null out`() {
        assertNull(normalizeJsStringResult(null))
    }
}
