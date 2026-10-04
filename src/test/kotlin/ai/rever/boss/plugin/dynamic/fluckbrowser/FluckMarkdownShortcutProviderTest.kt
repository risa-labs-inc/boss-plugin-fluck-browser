package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.dynamic.fluckbrowser.markdown.FluckBrowserMarkdownRegistry
import kotlinx.coroutines.Job
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FluckMarkdownShortcutProviderTest {
    private val invocations = mutableListOf<String>()

    @BeforeTest
    @AfterTest
    fun reset() {
        FluckBrowserMarkdownRegistry.clear()
        invocations.clear()
    }

    private fun registerTab(
        tabId: String,
        windowId: String,
    ) = FluckBrowserMarkdownRegistry.register(tabId, windowId, panelActive = true) {
        invocations.add(tabId)
        Job().apply { complete() }
    }

    @Test
    fun `the markdown action id conforms to host plugin naming rules`() {
        val root =
            generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .firstOrNull { java.io.File(it, "build.gradle.kts").isFile && java.io.File(it, "src/main/kotlin").isDirectory }
        assertNotNull(root, "could not locate the plugin root")
        val manifest = java.io.File(root, "src/main/resources/META-INF/boss-plugin/plugin.json").readText()
        val manifestId =
            Regex(""""pluginId"\s*:\s*"([^"]+)""").find(manifest)?.groupValues?.get(1)

        assertEquals(FluckBrowserDynamicPlugin.PLUGIN_ID, manifestId, "the manifest and PLUGIN_ID have drifted")
        assertEquals(
            "plugin.$manifestId.copy_page_markdown",
            FluckBrowserDynamicPlugin.COPY_PAGE_MARKDOWN_ACTION,
        )
    }

    @Test
    fun `the markdown action ships with no default binding`() {
        val spec = FluckBrowserDynamicPlugin().markdownShortcuts.shortcuts().single()

        assertEquals(FluckBrowserDynamicPlugin.COPY_PAGE_MARKDOWN_ACTION, spec.actionId)
        assertNull(spec.defaultBinding, "plugin actions leave key binding to user or host presets")
        assertEquals("Copy as Markdown for Agent", spec.displayName)
    }

    @Test
    fun `the markdown provider ignores unrelated action ids`() {
        registerTab("tab-1", "window-1")
        val provider = FluckBrowserDynamicPlugin().markdownShortcuts

        provider.onAction("plugin.something.else", "window-1")

        assertTrue(invocations.isEmpty())
    }

    @Test
    fun `the markdown provider dispatches to registered active tab in window`() {
        registerTab("tab-1", "window-1")
        val provider = FluckBrowserDynamicPlugin().markdownShortcuts

        provider.onAction(FluckBrowserDynamicPlugin.COPY_PAGE_MARKDOWN_ACTION, "window-1")

        assertEquals(listOf("tab-1"), invocations)
    }
}
