package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hibernation exemption for agent-driven tabs.
 *
 * Asserted through [TabHibernation.busyStateFor] rather than the registry alone: the registry
 * being right is not the fix - the decision reading it is.
 */
class AgentKeepAliveTest {
    private val now = 1_800_000_000_000L

    @BeforeTest
    fun reset() = AgentKeepAlive.clearForTest()

    @AfterTest
    fun cleanUp() = AgentKeepAlive.clearForTest()

    @Test
    fun `window is fifteen minutes`() {
        assertEquals(15L * 60L * 1000L, AgentKeepAlive.KEEP_ALIVE_WINDOW_MS)
    }

    @Test
    fun `a touch inside the window keeps the tab alive`() {
        AgentKeepAlive.touch("tab-1", now)
        assertTrue(AgentKeepAlive.isKeptAlive("tab-1", now + AgentKeepAlive.KEEP_ALIVE_WINDOW_MS - 1))
    }

    @Test
    fun `a touch older than the window does not`() {
        AgentKeepAlive.touch("tab-1", now)
        assertFalse(AgentKeepAlive.isKeptAlive("tab-1", now + AgentKeepAlive.KEEP_ALIVE_WINDOW_MS))
    }

    @Test
    fun `a held routed handle outlives the window`() {
        AgentKeepAlive.hold("tab-1")
        assertTrue(AgentKeepAlive.isKeptAlive("tab-1", now + 10L * AgentKeepAlive.KEEP_ALIVE_WINDOW_MS))
    }

    @Test
    fun `release hands the tab back on hand-off`() {
        AgentKeepAlive.hold("tab-1")
        AgentKeepAlive.touch("tab-1", now)
        AgentKeepAlive.release("tab-1")
        assertFalse(AgentKeepAlive.isKeptAlive("tab-1", now))
    }

    @Test
    fun `an untouched tab is never kept alive`() {
        AgentKeepAlive.touch("tab-1", now)
        assertFalse(AgentKeepAlive.isKeptAlive("tab-2", now))
        assertFalse(AgentKeepAlive.isKeptAlive(null, now))
        assertFalse(AgentKeepAlive.isKeptAlive("  ", now))
    }

    /** (a) The DGX defect: a tab touched inside the window is not hibernated. */
    @Test
    fun `a tab touched inside the window is not hibernated`() =
        runTest {
            AgentKeepAlive.touch("tab-1", now)
            assertEquals(
                TabHibernation.BusyState.AGENT_ROUTED,
                TabHibernation.busyStateFor(
                    fullscreenBlocks = false,
                    handle = null,
                    tabId = "tab-1",
                    agentKeptAlive = AgentKeepAlive.isKeptAlive("tab-1", now + 60_000L),
                ),
            )
        }

    /** (b) Past the window the tab is the plugin's again. */
    @Test
    fun `a tab whose keep-alive expired is hibernated`() =
        runTest {
            AgentKeepAlive.touch("tab-1", now)
            assertEquals(
                TabHibernation.BusyState.IDLE,
                TabHibernation.busyStateFor(
                    fullscreenBlocks = false,
                    handle = null,
                    tabId = "tab-1",
                    agentKeptAlive =
                        AgentKeepAlive.isKeptAlive("tab-1", now + AgentKeepAlive.KEEP_ALIVE_WINDOW_MS + 1),
                ),
            )
        }

    /** (c) A tab no agent ever touched decides exactly as before. */
    @Test
    fun `an untouched tab still hibernates`() =
        runTest {
            assertEquals(
                TabHibernation.BusyState.IDLE,
                TabHibernation.busyStateFor(fullscreenBlocks = false, handle = null, tabId = "tab-9"),
            )
            // And the other guards still win over a tab with no keep-alive.
            assertEquals(
                TabHibernation.BusyState.FULLSCREEN,
                TabHibernation.busyStateFor(fullscreenBlocks = true, handle = null, tabId = "tab-9"),
            )
        }

    /** A held tab is exempt even while fullscreen would otherwise answer first. */
    @Test
    fun `the agent exemption is answered before the page is asked`() =
        runTest {
            AgentKeepAlive.hold("tab-1")
            assertEquals(
                TabHibernation.BusyState.AGENT_ROUTED,
                TabHibernation.busyStateFor(fullscreenBlocks = true, handle = null, tabId = "tab-1"),
            )
        }
}
