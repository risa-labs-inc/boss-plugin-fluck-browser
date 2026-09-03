package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which browser tab's address bar Cmd+L should focus.
 *
 * Plugin shortcuts are GLOBAL in the host's v1 contract: the chord fires with nothing but a
 * window id, whatever has focus. So "focus the address bar" needs an answer to "whose?", and the
 * only code that knows a toolbar is on screen — in which window, in which panel, and whether that
 * panel is the one the user is in — is the tab's own composition. That is where entries come from,
 * the same shape and for the same reason as the host's `ActiveBrowserRegistry`.
 *
 * Without this, Cmd+L in a terminal tab would yank focus into some arbitrary browser tab in
 * another split. With it, the chord is inert unless a browser toolbar is actually the thing in
 * front of the user.
 */
internal object AddressBarFocusRegistry {
    /**
     * One composed browser toolbar.
     *
     * Value-equality is load-bearing: it lets [unregister] remove an entry only while it is still
     * the current one, so a tab moving between windows (which builds one composition and tears
     * down the other in an order this registry does not control) cannot have the outgoing
     * composition delete the incoming one's entry.
     */
    private data class Entry(
        val tabId: String,
        val windowId: String,
        val panelActive: Boolean,
        val sequence: Long,
        val focus: () -> Unit,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sequencer = AtomicLong(0)

    /**
     * Record that [tabId]'s address bar is composed in [windowId].
     *
     * @param panelActive whether the surrounding split panel is the one the user is in
     *   (`LocalIsPanelActive`). Defaults to true outside a managed panel, which is why it cannot
     *   be the only thing consulted — see [selectFocusTarget].
     * @param focus focuses and selects the field; called on the UI thread.
     * @return a token to hand back to [unregister].
     */
    fun register(
        tabId: String,
        windowId: String,
        panelActive: Boolean,
        focus: () -> Unit,
    ): Any {
        val entry =
            Entry(
                tabId = tabId,
                windowId = windowId,
                panelActive = panelActive,
                sequence = sequencer.incrementAndGet(),
                focus = focus,
            )
        entries[tabId] = entry
        return entry
    }

    /** Remove [tabId]'s registration, but only if [token] is still the current one. */
    fun unregister(
        tabId: String,
        token: Any?,
    ) {
        if (token is Entry) entries.remove(tabId, token)
    }

    /**
     * Focus the address bar of the browser tab that owns Cmd+L in [windowId].
     *
     * @return false when this window has no composed browser toolbar, so the caller can leave the
     *   chord unhandled rather than swallow it.
     */
    fun focusActiveIn(windowId: String?): Boolean {
        if (windowId == null) return false
        val target = selectFocusTarget(entries.values, windowId) ?: return false
        return runCatching { target.focus() }.isSuccess
    }

    /** Test seam: how many toolbars are currently registered. */
    internal fun size(): Int = entries.size

    /** Test seam. */
    internal fun clear() {
        entries.clear()
    }

    /**
     * Of the composed toolbars in [windowId], which one owns the chord.
     *
     * A toolbar in a NON-active panel is not a candidate at all, rather than a lower-ranked one.
     * The chord is global (plugin shortcuts have no context in the host's v1 contract) and the
     * host consumes it whenever a provider owns the action, so answering from a background split
     * would take Cmd+L away from whatever the user is actually working in — an editor's Go To
     * Line, say — and move focus to a browser they were not looking at. Declining leaves the
     * chord to that panel.
     *
     * Among the remaining candidates the most recently composed wins: the browser tab the user
     * most recently brought to the front.
     */
    private fun selectFocusTarget(
        candidates: Collection<Entry>,
        windowId: String,
    ): Entry? =
        candidates
            .filter { it.windowId == windowId && it.panelActive }
            .maxByOrNull { it.sequence }
}
