package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.UrlHistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeAddressBarTest {
    private val suggestions = listOf(
        UrlHistoryEntry("https://example.com/first", "First", "example.com", 3, 0),
        UrlHistoryEntry("https://example.com/second", "Second", "example.com", 2, 0),
    )

    @Test
    fun `explicit selection takes precedence over inline completion`() {
        assertEquals(suggestions[1].url, resolveAddressSubmission("exam", suggestions, 1, true))
    }

    @Test
    fun `inline completion commits the first suggestion`() {
        assertEquals(suggestions[0].url, resolveAddressSubmission("exam", suggestions, -1, true))
    }

    @Test
    fun `without completion typed input uses the existing url and search normalization`() {
        assertEquals("https://example.org", resolveAddressSubmission("example.org", suggestions, -1, false))
        assertEquals("https://www.google.com/search?q=hello+world",
            resolveAddressSubmission("hello world", emptyList(), -1, false))
    }
}
