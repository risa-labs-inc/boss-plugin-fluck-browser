package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.UrlHistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the dropdown bookkeeping behind "forget this suggestion" (the ✕ on a row and
 * shift+Delete on the highlighted one).
 *
 * The interesting part is the highlight: it has to follow the entry the user selected,
 * not its index. The ✕ appears on the row under the pointer regardless of which row the
 * arrow keys are on, so deleting above the selection is easy to do — and keeping the old
 * index would leave the highlight on a different suggestion, which the next Enter would
 * navigate to.
 */
class SuggestionDeleteTest {
    private fun entry(url: String) = UrlHistoryEntry(url = url, title = url, domain = url, visitCount = 1, lastVisited = 0)

    private val suggestions = listOf(entry("a.com"), entry("b.com"), entry("c.com"), entry("d.com"))

    @Test
    fun `deleting above the selection keeps the highlight on the same entry`() {
        val (remaining, index) = suggestionsAfterDelete(suggestions, deletedUrl = "a.com", selectedIndex = 2)

        assertEquals(listOf("b.com", "c.com", "d.com"), remaining.map { it.url })
        // Was on c.com at index 2; c.com is now index 1.
        assertEquals("c.com", remaining[index].url)
    }

    @Test
    fun `deleting below the selection leaves the index alone`() {
        val (remaining, index) = suggestionsAfterDelete(suggestions, deletedUrl = "d.com", selectedIndex = 1)

        assertEquals("b.com", remaining[index].url)
    }

    @Test
    fun `deleting the selection itself lands on what took its place`() {
        val (remaining, index) = suggestionsAfterDelete(suggestions, deletedUrl = "b.com", selectedIndex = 1)

        assertEquals(listOf("a.com", "c.com", "d.com"), remaining.map { it.url })
        assertEquals("c.com", remaining[index].url)
    }

    @Test
    fun `deleting the last remaining entry clears the highlight`() {
        val (remaining, index) = suggestionsAfterDelete(listOf(entry("a.com")), deletedUrl = "a.com", selectedIndex = 0)

        assertEquals(emptyList(), remaining.map { it.url })
        assertEquals(-1, index)
    }

    @Test
    fun `deleting the bottom entry while it is selected steps up`() {
        val (remaining, index) = suggestionsAfterDelete(suggestions, deletedUrl = "d.com", selectedIndex = 3)

        assertEquals("c.com", remaining[index].url)
    }

    @Test
    fun `with nothing highlighted the deletion just shortens the list`() {
        val (remaining, index) = suggestionsAfterDelete(suggestions, deletedUrl = "b.com", selectedIndex = -1)

        assertEquals(listOf("a.com", "c.com", "d.com"), remaining.map { it.url })
        assertEquals(-1, index)
    }

    @Test
    fun `deleting an entry that is no longer listed changes nothing`() {
        val (remaining, index) = suggestionsAfterDelete(suggestions, deletedUrl = "gone.com", selectedIndex = 2)

        assertEquals(suggestions.map { it.url }, remaining.map { it.url })
        assertEquals(2, index)
    }
}
