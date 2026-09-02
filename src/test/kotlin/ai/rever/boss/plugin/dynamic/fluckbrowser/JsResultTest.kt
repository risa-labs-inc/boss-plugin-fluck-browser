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
    fun `strips surrounding quotes`() {
        assertEquals("media", normalizeJsStringResult("\"media\""))
    }

    /**
     * Named precisely because the previous test name here claimed "a single layer", which
     * `String.trim(Char)` does not do - it strips every matching leading/trailing character, not
     * one. Pinned as intentional: none of the three real callers (busyStateFromScriptResult
     * matching a fixed keyword, ScrollRestore.parseCapture matching "x,y", the login-field
     * probe) ever expects a value that legitimately starts or ends with a literal `"`, so
     * stripping more layers than strictly needed is harmless for all of them - but it should be
     * a documented choice, not a fact a reader has to rediscover from the stdlib source.
     */
    @Test
    fun `strips more than one layer of quotes, not just one - this is trim(Char)'s real behaviour`() {
        assertEquals("media", normalizeJsStringResult("\"\"media\"\""))
    }

    @Test
    fun `trims outside whitespace, then inside-quote whitespace after stripping`() {
        assertEquals("media", normalizeJsStringResult("  media  "))
        // Whitespace INSIDE the quotes only becomes visible once the quotes themselves are gone -
        // this is why the trim is applied a second time, after the strip, not folded into one call.
        assertEquals("media", normalizeJsStringResult("\" media \""))
    }

    @Test
    fun `empty and blank inputs normalize to an empty string, not null`() {
        // Distinct from the null test below: null in means "no result at all" (e.g. a timeout);
        // an empty or blank STRING is a real answer from the page, just an empty one, and callers
        // rely on being able to tell the two apart (e.g. busyStateFromScriptResult's `else -> IDLE`
        // branch treats both the same today, but a future caller might not).
        assertEquals("", normalizeJsStringResult(""))
        assertEquals("", normalizeJsStringResult("   "))
    }

    @Test
    fun `null in, null out`() {
        assertNull(normalizeJsStringResult(null))
    }
}
