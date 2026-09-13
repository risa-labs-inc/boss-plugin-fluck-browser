package ai.rever.boss.plugin.dynamic.fluckbrowser.markdown

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FluckBrowserMarkdownRegistryTest {

    @BeforeTest
    @AfterTest
    fun reset() {
        FluckBrowserMarkdownRegistry.clear()
    }

    @Test
    fun `returns false when no browser tabs are registered`() {
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn("window-1"))
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn(null))
    }

    @Test
    fun `invokes the registered browser copy action in matching window`() {
        var copied = false
        val reg = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-1",
            windowId = "window-1",
            panelActive = true,
            copyAction = { copied = true },
        )

        assertTrue(FluckBrowserMarkdownRegistry.copyActiveIn("window-1"))
        assertTrue(copied)

        FluckBrowserMarkdownRegistry.unregister(reg)
    }

    @Test
    fun `does not invoke tabs in another window`() {
        var copied = false
        val reg = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-1",
            windowId = "window-1",
            panelActive = true,
            copyAction = { copied = true },
        )

        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn("window-2"))
        assertFalse(copied)

        FluckBrowserMarkdownRegistry.unregister(reg)
    }

    @Test
    fun `prioritizes active split panel over inactive panel`() {
        val invocations = mutableListOf<String>()

        val regInactive = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-inactive",
            windowId = "window-1",
            panelActive = false,
            copyAction = { invocations.add("inactive") },
        )

        val regActive = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-active",
            windowId = "window-1",
            panelActive = true,
            copyAction = { invocations.add("active") },
        )

        assertTrue(FluckBrowserMarkdownRegistry.copyActiveIn("window-1"))
        assertEquals(listOf("active"), invocations)

        FluckBrowserMarkdownRegistry.unregister(regInactive)
        FluckBrowserMarkdownRegistry.unregister(regActive)
    }

    @Test
    fun `prioritizes most recent registration when panel states match`() {
        val invocations = mutableListOf<String>()

        val regFirst = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-1",
            windowId = "window-1",
            panelActive = true,
            copyAction = { invocations.add("first") },
        )

        val regSecond = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-2",
            windowId = "window-1",
            panelActive = true,
            copyAction = { invocations.add("second") },
        )

        assertTrue(FluckBrowserMarkdownRegistry.copyActiveIn("window-1"))
        assertEquals(listOf("second"), invocations)

        FluckBrowserMarkdownRegistry.unregister(regFirst)
        FluckBrowserMarkdownRegistry.unregister(regSecond)
    }

    @Test
    fun `unregistering removes tab from candidates`() {
        var copied = false
        val reg = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-1",
            windowId = "window-1",
            panelActive = true,
            copyAction = { copied = true },
        )

        FluckBrowserMarkdownRegistry.unregister(reg)
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn("window-1"))
        assertFalse(copied)
    }
}
