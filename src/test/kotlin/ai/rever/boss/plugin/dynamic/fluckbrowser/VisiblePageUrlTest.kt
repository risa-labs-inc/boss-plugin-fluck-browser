package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The URL bar is an editable text box that happens to hold the loaded URL most of the time, not a
 * loaded-URL field. [visiblePageUrl] is the distinction, and these pin the cases where it matters:
 * the affordances that act on "the page I am on" (copy link, bookmark) must not act on a
 * half-typed string.
 */
class VisiblePageUrlTest {
    private val LOADED = "https://example.com/article"

    @Test
    fun `the box agreeing with the page is the ordinary case`() {
        assertEquals(LOADED, visiblePageUrl(draft = LOADED, loaded = LOADED))
    }

    /**
     * The bug this exists for: a copy-LINK button that hands back `htt` because that is as far as
     * the user had typed. Bookmarking a draft is the same surprise, which is why all three call
     * sites read this rather than only the newest of them being fixed.
     */
    @Test
    fun `a half-typed draft never wins over the loaded page`() {
        assertEquals(LOADED, visiblePageUrl(draft = "htt", loaded = LOADED))
        assertEquals(LOADED, visiblePageUrl(draft = "", loaded = LOADED))
    }

    /**
     * There is deliberately no "is the user editing" input. `onFocusLost` clears the editing flag
     * 200ms after focus leaves WITHOUT restoring the box, so a stale draft outlives the flag - and
     * a gate on it would hand back that draft in exactly the case it was added to exclude. This
     * case must behave identically to the one above.
     */
    @Test
    fun `a stale draft left behind after editing stopped still loses`() {
        assertEquals(LOADED, visiblePageUrl(draft = "htt", loaded = LOADED))
    }

    /**
     * Home is represented as "nothing loaded" - `about:blank` fires no navigation events, so
     * `applyHomeTabIdentity` clears the field rather than the browser announcing it. The draft is
     * what says home here, and it must survive so callers can still recognise it with [isHomeUrl].
     */
    @Test
    fun `home falls through to the draft and stays recognisable as home`() {
        assertEquals("", visiblePageUrl(draft = "", loaded = ""))
        assertTrue(isHomeUrl(visiblePageUrl(draft = "", loaded = "")))
        assertTrue(isHomeUrl(visiblePageUrl(draft = "about:blank", loaded = "")))
    }

    /**
     * Before a brand-new tab's first navigation commits there is no loaded page to prefer, so the
     * draft is the only answer there is - this must not return an empty string.
     */
    @Test
    fun `nothing loaded yet falls back to the draft`() {
        assertEquals("example.com", visiblePageUrl(draft = "example.com", loaded = ""))
    }
}
