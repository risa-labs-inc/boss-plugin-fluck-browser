package ai.rever.boss.plugin.dynamic.fluckbrowser.markdown

import kotlinx.coroutines.Job
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
            copyAction = { copied = true; Job().apply { complete() } },
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
            copyAction = { copied = true; Job().apply { complete() } },
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
            copyAction = { invocations.add("inactive"); Job().apply { complete() } },
        )

        val regActive = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-active",
            windowId = "window-1",
            panelActive = true,
            copyAction = { invocations.add("active"); Job().apply { complete() } },
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
            copyAction = { invocations.add("first"); Job().apply { complete() } },
        )

        val regSecond = FluckBrowserMarkdownRegistry.register(
            tabId = "tab-2",
            windowId = "window-1",
            panelActive = true,
            copyAction = { invocations.add("second"); Job().apply { complete() } },
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
            copyAction = { copied = true; Job().apply { complete() } },
        )

        FluckBrowserMarkdownRegistry.unregister(reg)
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn("window-1"))
        assertFalse(copied)
    }

    @Test
    fun `unknown windows and inactive panels cannot copy a different tab`() {
        var copies = 0
        FluckBrowserMarkdownRegistry.register("tab", "window", false) {
            copies++; Job().apply { complete() }
        }
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn("window"))
        FluckBrowserMarkdownRegistry.register("active", "window", true) {
            copies++; Job().apply { complete() }
        }
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn(null))
        assertEquals(0, copies)
    }

    @Test
    fun `debounce covers asynchronous work and releases after completion or cancellation`() {
        var copies = 0
        var pending = Job()
        FluckBrowserMarkdownRegistry.register("tab", "window", true) { copies++; pending }
        assertTrue(FluckBrowserMarkdownRegistry.copyActiveIn("window"))
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn("window"))
        assertEquals(1, copies)
        pending.complete()
        pending = Job()
        assertTrue(FluckBrowserMarkdownRegistry.copyActiveIn("window"))
        pending.cancel()
        pending = Job()
        assertTrue(FluckBrowserMarkdownRegistry.copyActiveIn("window"))
        pending.complete()
        assertEquals(3, copies)
    }

    @Test
    fun `throwing launcher releases debounce and does not escape into key dispatch`() {
        val bad = FluckBrowserMarkdownRegistry.register("tab", "window", true) { error("disposed") }
        assertFalse(FluckBrowserMarkdownRegistry.copyActiveIn("window"))
        FluckBrowserMarkdownRegistry.unregister(bad)
        FluckBrowserMarkdownRegistry.register("tab", "window", true) { Job().apply { complete() } }
        assertTrue(FluckBrowserMarkdownRegistry.copyActiveIn("window"))
    }
}
