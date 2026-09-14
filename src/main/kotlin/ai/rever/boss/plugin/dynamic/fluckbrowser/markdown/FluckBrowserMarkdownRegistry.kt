package ai.rever.boss.plugin.dynamic.fluckbrowser.markdown

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates "Copy as Markdown for Agent" shortcut invocation to the focused or active
 * browser tab in a window.
 *
 * Restricts selection to active split panels in the invoking window
 * and uses atomic in-flight debouncing to prevent stacked executions on rapid key presses.
 */
internal object FluckBrowserMarkdownRegistry {

    interface Registration

    private class Entry(
        val tabId: String,
        val windowId: String,
        val panelActive: Boolean,
        val sequence: Long,
        val copyAction: () -> Job,
    ) : Registration

    private val entries = ConcurrentHashMap.newKeySet<Entry>()
    private val sequenceGenerator = AtomicLong(0)
    private val inFlight = AtomicBoolean(false)

    fun register(
        tabId: String,
        windowId: String,
        panelActive: Boolean,
        copyAction: () -> Job,
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
        if (windowId == null) return false
        val candidate = entries
            .filter { it.windowId == windowId && it.panelActive }
            .maxByOrNull { it.sequence } ?: return false
        if (!inFlight.compareAndSet(false, true)) return false

        try {
            // launch returns before extraction completes. Hold the guard until its Job ends,
            // including cancellation, so repeated chords cannot race clipboard writes.
            candidate.copyAction().invokeOnCompletion { inFlight.set(false) }
            return true
        } catch (e: Exception) {
            inFlight.set(false)
            return false
        }
    }

    fun clear() {
        entries.clear()
        // An already running copy retains the guard until its completion.
    }
}
