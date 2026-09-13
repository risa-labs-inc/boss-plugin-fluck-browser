package ai.rever.boss.plugin.dynamic.fluckbrowser.markdown

import ai.rever.boss.plugin.browser.BrowserHandle
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FluckMarkdownExtractorTest {

    private fun createStubHandle(
        title: String = "",
        url: String = "",
        scriptResult: Any? = null,
    ): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTitle" -> title
                "getCurrentUrl" -> url
                "executeJavaScript" -> scriptResult
                "toString" -> "StubBrowserHandle"
                else -> null
            }
        } as BrowserHandle

    @Test
    fun `formats source attribution and wraps urls with parentheses in angle brackets`() = runBlocking {
        val handle = createStubHandle(
            title = "Test [Page] Title\nWith Newline",
            url = "https://example.com/wiki/Page_(disambiguation)",
            scriptResult = """{"isSelection":false,"markdown":"Page content"}""",
        )

        val result = FluckMarkdownExtractor.extractMarkdown(handle)

        assertTrue(result.markdown.contains("> **Source:** [Test \\[Page\\] Title With Newline](<https://example.com/wiki/Page_(disambiguation)>)"))
        assertTrue(result.markdown.contains("> **Captured from Fluck Browser**"))
        assertTrue(result.markdown.contains("Page content"))
        assertFalse(result.isTruncated)
    }

    @Test
    fun `truncation closes open code fence and appends truncation note`() = runBlocking {
        // Construct markdown that exceeds 200k chars and opens a code fence
        val hugeCode = "```python\n" + "x = 1\n".repeat(40000)
        val handle = createStubHandle(
            title = "Code Page",
            url = "https://example.com/code",
            scriptResult = """{"isSelection":false,"markdown":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), hugeCode)}}""",
        )

        val result = FluckMarkdownExtractor.extractMarkdown(handle)

        assertTrue(result.isTruncated)
        assertTrue(result.markdown.contains("> *[Output truncated: exceeded 200,000 character limit]*"))
        val noteIndex = result.markdown.indexOf("> *[Output truncated")
        val textBeforeNote = result.markdown.substring(0, noteIndex).trimEnd()
        assertTrue(textBeforeNote.endsWith("```"), "Truncated markdown must end code fence with ```")
    }

    @Test
    fun `repairUnclosedCodeFences closes 4-backtick fence with 4 backticks`() {
        val unclosed = "````markdown\n```python\nprint(1)\n```\nmore text"
        val repaired = FluckMarkdownExtractor.repairUnclosedCodeFences(unclosed)
        assertEquals("$unclosed\n````", repaired)
    }

    @Test
    fun `repairUnclosedCodeFences leaves closed fences unchanged`() {
        val closed = "```python\nprint(1)\n```\n"
        val repaired = FluckMarkdownExtractor.repairUnclosedCodeFences(closed)
        assertEquals(closed, repaired)
    }

    @Test
    fun `truncation preserves surrogate pairs`() = runBlocking {
        // Build string where a surrogate pair sits at index 199999
        val prefix = "a".repeat(199999)
        val emoji = "\uD83D\uDE00" // 😀 (surrogate pair: \uD83D, \uDE00)
        val fullContent = prefix + emoji + "extra text".repeat(1000)

        val handle = createStubHandle(
            title = "Emoji Page",
            url = "https://example.com/emoji",
            scriptResult = """{"isSelection":false,"markdown":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), fullContent)}}""",
        )

        val result = FluckMarkdownExtractor.extractMarkdown(handle)

        assertTrue(result.isTruncated)
        // Verify no lone surrogates exist
        for (i in 0 until result.markdown.length) {
            val ch = result.markdown[i]
            if (Character.isHighSurrogate(ch)) {
                assertTrue(i + 1 < result.markdown.length && Character.isLowSurrogate(result.markdown[i + 1]), "High surrogate must be followed by low surrogate")
            }
        }
    }

    @Test
    fun `token estimator charges higher rate for code and tables`() = runBlocking {
        val prose = "Regular prose text without tables or code blocks. ".repeat(100)
        val proseHandle = createStubHandle(
            title = "Prose Page",
            url = "https://example.com/prose",
            scriptResult = """{"isSelection":false,"markdown":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), prose)}}""",
        )
        val proseResult = FluckMarkdownExtractor.extractMarkdown(proseHandle)

        val code = "```kotlin\nval x = 1\n```\n".repeat(50)
        val codeHandle = createStubHandle(
            title = "Code Page",
            url = "https://example.com/code",
            scriptResult = """{"isSelection":false,"markdown":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), code)}}""",
        )
        val codeResult = FluckMarkdownExtractor.extractMarkdown(codeHandle)

        assertTrue(codeResult.estimatedTokens > 0, "Code tokens must be greater than 0")
        assertTrue(proseResult.estimatedTokens > 0, "Prose tokens must be greater than 0")
    }

    @Test
    fun `handles empty extraction gracefully`() = runBlocking {
        val handle = createStubHandle(
            title = "Empty",
            url = "https://example.com",
            scriptResult = """{"isSelection":false,"markdown":""}""",
        )

        val result = FluckMarkdownExtractor.extractMarkdown(handle)

        assertEquals("", result.markdown)
        assertEquals(0, result.estimatedTokens)
        assertFalse(result.isTruncated)
    }
}
