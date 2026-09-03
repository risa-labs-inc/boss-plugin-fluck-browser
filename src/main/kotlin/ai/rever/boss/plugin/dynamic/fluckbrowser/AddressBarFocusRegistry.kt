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
 *
 * **The invariant the tie-break rests on:** `FluckBrowserTabContent` LEAVES COMPOSITION on a tab
 * switch — that is what the hibernation `DisposableEffect` further down that file is built on, and
 * why the browser handle is hoisted into the parent Component instead. So a background tab in the
 * same panel holds no entry at all, and "most recently composed" means "the tab in front of the
 * user". If tab compositions were ever kept alive while hidden, [sequence] would start picking
 * hidden tabs and would have to be replaced by something the host tells us.
 */
internal object AddressBarFocusRegistry {
    /**
     * A live registration. Opaque on purpose: [unregister] takes this type rather than [Any], so
     * handing back the wrong thing is a compile error instead of a silent no-op.
     */
    interface Registration

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
    ) : Registration

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sequencer = AtomicLong(0)

    /**
     * Last time each miss reason was logged, keyed by reason AND window.
     *
     * Throttled rather than one-shot: a user who rebinds this action to a GLOBAL chord would get
     * a line every time they pressed it anywhere in the app, but a latch that never reopens keeps
     * only the FIRST miss for the life of the JVM — and since the message names the window, that
     * one line would be about whichever window happened to miss first. Neither extreme is any use
     * for diagnosing "Cmd+L did nothing" from a user's log. Per-window keys plus a window of
     * silence keep one line per distinct problem.
     */
    private val lastMissLogged = ConcurrentHashMap<String, Long>()

    private const val MISS_LOG_THROTTLE_MS = 30_000L

    /** Whether this miss is far enough from the last one of its kind to be worth a line. */
    private fun shouldLogMiss(key: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastMissLogged[key]
        // Also true when the clock steps backwards, which errs toward logging.
        if (previous != null && now - previous in 0 until MISS_LOG_THROTTLE_MS) return false
        lastMissLogged[key] = now
        return true
    }

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
    ): Registration {
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
        token: Registration?,
    ) {
        if (token is Entry) entries.remove(tabId, token)
    }

    /**
     * Focus the address bar of the browser tab that owns Cmd+L in [windowId].
     *
     * @return whether a toolbar took the focus. Defense-in-depth and a test seam rather than a
     *   signal any caller acts on: a plugin `onAction` returns Unit, so the host's `dispatch`
     *   reports the chord handled whenever a provider owns the action, however this answers.
     */
    fun focusActiveIn(windowId: String?): Boolean {
        if (windowId == null) {
            // The host could not attribute the keypress to a window. Focusing "whatever we can
            // find" would be worse than doing nothing.
            if (shouldLogMiss("no-window-id")) {
                println("[FluckBrowser] Cmd+L ignored: no window id")
            }
            return false
        }
        val target = selectFocusTarget(entries.values, windowId)
        if (target == null) {
            if (shouldLogMiss("no-toolbar:$windowId")) {
                println("[FluckBrowser] Cmd+L ignored: no browser toolbar in the active panel of $windowId")
            }
            return false
        }
        // Catches Throwable, which is deliberate for the same reason the host's plugin dispatch
        // does: this runs inside the host's key-event dispatch, and letting anything escape would
        // take out the whole keyboard path rather than one chord.
        return runCatching { target.focus() }
            .onFailure { println("[FluckBrowser] Cmd+L could not focus the address bar: ${it.message}") }
            .isSuccess
    }

    /** Test seam: how many toolbars are currently registered. */
    fun size(): Int = entries.size

    /**
     * Drops every registration. Called on plugin dispose, and by tests between cases.
     *
     * This object is a process-global singleton, so a test that registers anything MUST clear or
     * it leaks entries into whatever runs next — `AddressBarFocusRegistryTest` and
     * `AddressBarShortcutProviderTest` both do, in `@BeforeTest`/`@AfterTest`. Safe because the
     * build runs test classes sequentially (no `maxParallelForks`); a parallel runner would need
     * the registry injected rather than reached as a singleton. Clears the miss-log throttle for
     * the same reason.
     */
    fun clear() {
        entries.clear()
        lastMissLogged.clear()
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
     *
     * **Why there is no "is this really a main-window panel?" rank above `panelActive`, unlike
     * the host's `selectActiveHandleId`.** `LocalIsPanelActive` defaults to `true`, so a surface
     * composed outside a managed panel reads as active too; the host separates the two with
     * `LocalInMainWindowPanel`, which is host-internal (`BossMainWindowPanel.kt`) and NOT part of
     * the plugin api, so this registry cannot read it. It does not need to: the host composes a
     * plugin TAB type's `Content()` from exactly one place, `BossMainPanelContent`, which sits
     * under `LocalInMainWindowPanel provides true`. Side panels render `PanelComponentWithUI`,
     * which this plugin does not implement, and the host's own sidebar/preview browsers are
     * `BrowserHandle` surfaces that never build a fluck toolbar. The host hit this (v9.4.17, on
     * `ActiveBrowserRegistry`) because its entries come from `BrowserHandleImpl.Content()`, which
     * IS composed in those slots. If this plugin ever grows a panel provider, this ranking has to
     * grow the same preference — and would need the local exported through the api first.
     */
    private fun selectFocusTarget(
        candidates: Collection<Entry>,
        windowId: String,
    ): Entry? =
        candidates
            .filter { it.windowId == windowId && it.panelActive }
            .maxByOrNull { it.sequence }
}
