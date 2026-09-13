package ai.rever.boss.plugin.dynamic.fluckbrowser.markdown

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates "Copy as Markdown for Agent" shortcut invocation to the focused or active
 * browser tab in a window.
 *
 * Implements panel-priority selection (favoring active split panels over background panels)
 * and atomic in-flight debouncing to prevent stacked executions on rapid key presses.
 */
internal object FluckBrowserMarkdownRegistry {

    interface Registration

    private class Entry(
        val tabId: String,
        val windowId: String,
        val panelActive: Boolean,
        val sequence: Long,
        val copyAction: () -> Unit,
    ) : Registration

    private val entries = ConcurrentHashMap.newKeySet<Entry>()
    private val sequenceGenerator = AtomicLong(0)
    private val inFlight = AtomicBoolean(false)

    fun register(
        tabId: String,
        windowId: String,
        panelActive: Boolean,
        copyAction: () -> Unit,
    ): Registration {
        val entry =
            Entry(
                tabId = tabId,
                windowId = windowId,
                panelActive = panelActive,
                sequence = sequenceGenerator.incrementAndGet(),
                copyAction = copyAction,
            )
        entries.add(entry)
        return entry
    }

    fun unregister(registration: Registration?) {
        if (registration is Entry) {
            entries.remove(registration)
        }
    }

    /**
     * Triggers markdown copy on the active browser tab in [windowId].
     * Returns true if an active candidate was found and invoked, false otherwise.
     */
    fun copyActiveIn(windowId: String?): Boolean {
        // Atomic in-flight debounce drops concurrent or rapid successive key repeats
        if (!inFlight.compareAndSet(false, true)) return false

        return try {
            val candidate =
                entries
                    .filter { windowId == null || it.windowId == windowId }
                    .sortedWith(
                        compareByDescending<Entry> { it.panelActive }
                            .thenByDescending { it.sequence },
                    ).firstOrNull() ?: return false

            candidate.copyAction()
            true
        } finally {
            inFlight.set(false)
        }
    }

    fun clear() {
        entries.clear()
        inFlight.set(false)
    }
}
