package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.util.concurrent.ConcurrentHashMap

/**
 * Tabs a Fluck agent is driving, so hibernation does not release a renderer mid-task.
 *
 * The plugin cannot see the agent's routing: from here an agent-driven tab is just a
 * backgrounded tab with a quiet page, and that is precisely what hibernation is built to
 * reclaim. Observed on the DGX - the idle timer fired twice while an agent sat waiting on a
 * DoorDash page, and the next tool call found no live tab.
 *
 * So the agent declares it. [touch] on every routed tool call, [hold] while a workspace keeps a
 * routed handle, [release] on hand-off. Reached from the MCP tools in
 * [FluckBrowserMcpToolProvider] - the seam the agent already calls - rather than a new provider.
 *
 * Keyed by tab id, not by handle: the handle is what hibernation disposes, so a registry keyed on
 * it would empty itself at exactly the moment it is consulted.
 */
internal object AgentKeepAlive {
    /** How long one routed tool call keeps its tab awake. */
    internal const val KEEP_ALIVE_WINDOW_MS = 15L * 60L * 1000L

    private val touchedAtMs = ConcurrentHashMap<String, Long>()
    private val held = ConcurrentHashMap.newKeySet<String>()

    /** Called on every routed tool call. Blank ids are dropped - a tab with no id cannot be matched back. */
    fun touch(
        tabId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (tabId.isBlank()) return
        touchedAtMs[tabId] = nowMs
    }

    /** A workspace holds a routed handle to this tab. Unbounded by design; [release] ends it. */
    fun hold(tabId: String) {
        if (tabId.isBlank()) return
        held.add(tabId)
    }

    /** Hand-off: the workspace is done with this tab, and the idle timer may have it back. */
    fun release(tabId: String) {
        held.remove(tabId)
        touchedAtMs.remove(tabId)
    }

    /** Whether hibernation must leave this tab alone. */
    fun isKeptAlive(
        tabId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (tabId.isNullOrBlank()) return false
        if (tabId in held) return true
        return isFresh(touchedAtMs[tabId], nowMs)
    }

    /** Pure, so the window is testable without a clock. A touch dated in the future still counts. */
    internal fun isFresh(
        touchedAt: Long?,
        nowMs: Long,
    ): Boolean = touchedAt != null && nowMs - touchedAt < KEEP_ALIVE_WINDOW_MS

    /** Tests only: the registry is process-wide. */
    internal fun clearForTest() {
        touchedAtMs.clear()
        held.clear()
    }
}
