package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The URL bar is an editable text box that happens to hold the loaded URL most of the time, not a
 * loaded-URL field. [visiblePageUrl] is the distinction, and these pin the one case where it
 * matters: the affordances that act on "the page I am on" (copy link, bookmark) must not act on
 * a half-typed string.
 */
class VisiblePageUrlTest {
    private val LOADED = "https://example.com/article"

    @Test
    fun `not editing - the URL bar is the page`() {
        assertEquals(LOADED, visiblePageUrl(draft = LOADED, isEditing = false, loaded = LOADED))
    }

    /**
     * The bug this exists for: a copy-LINK button that hands back `htt` because that is as far as
     * the user had typed. Bookmarking a draft is the same surprise, which is why both read this
     * rather than only the newer of the two being fixed.
     */
    @Test
    fun `editing - a half-typed draft never wins over the loaded page`() {
        assertEquals(LOADED, visiblePageUrl(draft = "htt", isEditing = true, loaded = LOADED))
        assertEquals(LOADED, visiblePageUrl(draft = "", isEditing = true, loaded = LOADED))
    }

    /**
     * Before the first navigation commits there is no loaded page to prefer - on a brand-new tab
     * the draft is the only answer there is, so this must not return an empty string.
     */
    @Test
    fun `editing before anything has loaded falls back to the draft`() {
        assertEquals("example.com", visiblePageUrl(draft = "example.com", isEditing = true, loaded = ""))
    }
}
