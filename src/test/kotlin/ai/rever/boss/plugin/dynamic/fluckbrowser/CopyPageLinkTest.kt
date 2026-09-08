package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the copy-link button is allowed to say it copied something.
 *
 * The button reports success with a green check-mark. It used to set that state unconditionally,
 * immediately after `clipboardManager.setText(...)`, so the only thing the tick actually proved
 * was that the click handler ran. `setText` reaches AWT's system clipboard, which throws
 * `IllegalStateException` when the clipboard is unavailable or another process holds it, and the
 * user was then shown a confirmation while pasting whatever was there before.
 *
 * That is worse than silence: a control that reports unverified success hides the failure on both
 * sides, from the user and from anyone reading a bug report about it.
 */
class CopyPageLinkTest {
    @Test
    fun `a normal url is copied and reported as copied`() {
        var written: String? = null

        val copied = copyPageLink("https://example.com/page") { written = it }

        assertTrue(copied)
        assertEquals("https://example.com/page", written, "the url handed to the clipboard should be the page's")
    }

    @Test
    fun `a clipboard that refuses the write is not reported as a copy`() {
        // The case the old code could not express. AWT throws IllegalStateException when the
        // clipboard is unavailable or another process holds it, which is ordinary contention
        // rather than an exotic failure.
        val copied = copyPageLink("https://example.com") { throw IllegalStateException("cannot open system clipboard") }

        assertFalse(copied, "a failed clipboard write must not light up the success indicator")
    }

    @Test
    fun `an error rather than an exception is also not a success`() {
        // runCatching catches Throwable, and that is deliberate here: the caller is a click
        // handler on the UI thread, where letting anything escape kills the interaction rather
        // than reporting it.
        val copied = copyPageLink("https://example.com") { throw NoClassDefFoundError("clipboard backend") }

        assertFalse(copied)
    }

    @Test
    fun `home is not copyable, and the clipboard is never touched`() {
        // Home is a surface rather than a page. The button is disabled there, but the guard lives
        // with the success decision so the two cannot disagree: the previous code decided
        // "enabled" and "shows a tick" in two separate places.
        var touched = false

        assertFalse(copyPageLink("") { touched = true })
        assertFalse(copyPageLink("about:blank") { touched = true })
        assertFalse(copyPageLink("   ") { touched = true })

        assertFalse(touched, "an unusable url should not reach the clipboard at all")
    }

    @Test
    fun `a url that merely mentions about blank is still copyable`() {
        // Guards the home check against being widened into a substring match, which would refuse
        // to copy a real page whose URL happens to contain the word.
        var written: String? = null

        assertTrue(copyPageLink("https://example.com/docs/about:blank-explained") { written = it })
        assertEquals("https://example.com/docs/about:blank-explained", written)
    }
}
