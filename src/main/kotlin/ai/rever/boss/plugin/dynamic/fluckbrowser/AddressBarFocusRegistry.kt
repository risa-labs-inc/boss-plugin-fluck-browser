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
     * NOT a data class, deliberately: [entries] is a set, so entry equality decides what
     * [unregister] removes, and identity is exactly the semantics wanted. Value equality would
     * make two toolbars that happened to agree on every field one entry - and it is what makes
     * the teardown race unrepresentable rather than merely handled, since a tab moving between
     * windows builds one composition and tears down the other in an order this registry does not
     * control, both carrying the same tab id. Each composition owns exactly the entry it
     * registered.
     */
    private class Entry(
        val tabId: String,
        val windowId: String,
        val panelActive: Boolean,
        val sequence: Long,
        val focus: () -> Unit,
    ) : Registration

    /**
     * Every composed toolbar, held by identity rather than keyed by tab id.
     *
     * **What an entry retains.** The `focus` lambda closes over the tab's composition - which
     * reaches `hoistedState`, and through it the live JxBrowser handle - and this object is a
     * process-global singleton in a plugin with `canUnload: false`. Compose pairs every
     * `DisposableEffect` with its `onDispose` and [clear] runs on plugin dispose, so entries are
     * bounded by composed toolbars in practice. But there is no cap and no weak reference: ONE
     * missed dispose retains a Chromium-backed state object for the life of the process rather
     * than the life of a tab. A `WeakReference` to the callback would make that structural, at
     * the cost of the identity keying's tidiness - worth revisiting if this ever holds anything
     * heavier than a toolbar.
     *
     * Keying by tab id would ask a question this registry cannot answer - whether the host can
     * ever compose one tab in two panels at once - and would silently drop one of the two
     * toolbars if it ever could. Identity makes that a non-question, and [unregister] already
     * takes the token, so the tab id was never doing any work as a key. Compose pairs every
     * effect with its `onDispose`, so entries do not accumulate.
     */
    private val entries = ConcurrentHashMap.newKeySet<Entry>()
    private val sequencer = AtomicLong(0)

    /**
     * Last time each miss reason was logged, keyed by reason AND window, on [System.nanoTime].
     *
     * Throttled rather than one-shot: a user who rebinds this action to a GLOBAL chord would get
     * a line every time they pressed it anywhere in the app, but a latch that never reopens keeps
     * only the FIRST miss for the life of the JVM — and since the message names the window, that
     * one line would be about whichever window happened to miss first. Neither extreme is any use
     * for diagnosing "Cmd+L did nothing" from a user's log. Per-window keys plus a window of
     * silence keep one line per distinct problem.
     */
    private val lastMissLogged = ConcurrentHashMap<String, Long>()

    internal const val MISS_LOG_THROTTLE_NANOS = 30_000_000_000L

    /**
     * Whether this miss is far enough from the last one of its kind to be worth a line.
     *
     * One atomic `compute` rather than a get-then-put: the map is a [ConcurrentHashMap], and a
     * check-then-act body would promise a concurrency guarantee it does not keep. `onAction` is
     * EDT-only today, so this is about the code saying what it means.
     */
    private fun shouldLogMiss(key: String): Boolean {
        val now = System.nanoTime()
        var log = false
        lastMissLogged.compute(key) { _, previous ->
            log = missLogDue(previous, now)
            if (log) now else previous
        }
        return log
    }

    /**
     * The throttle decision, split out of [shouldLogMiss] so it is assertable: the map access
     * around it is only observable through `println`, and the rule inside it is not obvious.
     *
     * A never-logged key is always due. [System.nanoTime] is monotonic, so unlike a wall clock
     * it cannot step in either direction and no special case is needed for one that does - which
     * is the whole reason this is not measured in `currentTimeMillis`.
     */
    internal fun missLogDue(
        previous: Long?,
        now: Long,
    ): Boolean = previous == null || now - previous >= MISS_LOG_THROTTLE_NANOS

    /**
     * Record that [tabId]'s address bar is composed in [windowId].
     *
     * @param panelActive whether the surrounding split panel is the one the user is in
     *   (`LocalIsPanelActive`). Defaults to true outside a managed panel, which is why it cannot
     *   be the only thing consulted — see [selectFocusTarget].
     * @param focus focuses and selects the field; called on the UI thread.
     * @return the token to hand back to [unregister]; identity is what identifies it.
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
        entries.add(entry)
        return entry
    }

    /**
     * Drop the registration [token] identifies.
     *
     * A token this registry did not issue (or null) is ignored rather than treated as "remove
     * whatever is there for that tab" - the distinction the old tab-id keying needed a value
     * comparison to make.
     */
    fun unregister(token: Registration?) {
        if (token is Entry) entries.remove(token)
    }

    /**
     * Focus the address bar of the browser tab that owns Cmd+L in [windowId].
     *
     * @return whether a toolbar took the focus. Defense-in-depth and a test seam rather than a
     *   signal any caller acts on: a plugin `onAction` returns Unit, so the host's `dispatch`
     *   reports the chord handled whenever a provider owns the action, however this answers. A
     *   `false` therefore means "Cmd+L did nothing here", never "someone else may serve it".
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
        val target = selectFocusTarget(entries, windowId)
        if (target == null) {
            if (shouldLogMiss("no-toolbar:$windowId")) {
                println("[FluckBrowser] Cmd+L ignored: no browser toolbar in the active panel of $windowId")
            }
            return false
        }
        // Exception, NOT Throwable. Containment is the point - this runs inside the host's
        // key-event dispatch, and letting a bad chord take out the whole keyboard path would be
        // far worse than one dead shortcut - but `runCatching` would also swallow an
        // OutOfMemoryError or a LinkageError into a quiet `false` plus one throttled line, and a
        // VM error is exactly what should reach the host's own guard and the logs.
        return runCatchingException { target.focus() }
            .onFailure {
                // Throttled like the two miss paths above, and for the same reason: this is the
                // path a hibernated tab or a future toolbar gate takes, and it repeats on every
                // press. Keyed on the reason only, not the tab - see noteUnregisterable.
                if (shouldLogMiss("focus-failed")) {
                    println(
                        "[FluckBrowser] Cmd+L could not focus the address bar of tab " +
                            "${target.tabId.ifEmpty { "<no id>" }}: ${it.message}",
                    )
                }
            }.isSuccess
    }

    /**
     * A tab whose toolbar could NOT be registered, so "Cmd+L does nothing in this tab, ever" is
     * distinguishable in a log from "no toolbar in the active panel" - the two look identical
     * otherwise, and only one of them is a configuration problem.
     *
     * Throttled on the same terms as the miss paths, because tab compositions come and go.
     */
    fun noteUnregisterable(
        tabId: String,
        reason: String,
    ) {
        // Keyed on the REASON only, deliberately: including the tab id would leave one permanent
        // entry per tab that ever hit this path, and this is a systemPlugin with canUnload:false,
        // so "permanent" means the life of the process. The 30s window still names a fresh tab
        // each time it reopens, which diagnoses the same thing.
        if (shouldLogMiss("unregisterable:$reason")) {
            println("[FluckBrowser] Cmd+L unavailable for tab ${tabId.ifEmpty { "<no id>" }}: $reason")
        }
    }

    /**
     * [runCatching] narrowed to [Exception], because that stdlib function is Throwable-wide by
     * definition and there is no variant that is not.
     */
    private inline fun runCatchingException(block: () -> Unit): Result<Unit> =
        try {
            Result.success(block())
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Test seam: how many toolbars are currently registered. */
    fun size(): Int = entries.size

    /** Test seam: how many distinct throttle keys the miss log is holding. */
    fun throttleKeyCount(): Int = lastMissLogged.size

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
     * A toolbar in a NON-active panel is not a candidate at all, rather than a lower-ranked one:
     * answering from a background split would move focus to a browser the user was not looking
     * at, which is reason enough on its own.
     *
     * It does NOT hand the chord back. The host consumes it whenever a provider owns the action
     * (`onAction` returns Unit, so `dispatch` reports success however this answers), so declining
     * makes Cmd+L a no-op there rather than an editor's Go To Line. What protects the editor is
     * the host preset's `context = BROWSER` — see `FluckBrowserDynamicPlugin.addressBarShortcuts`
     * for why the binding lives there, which is the one place that reasoning is spelled out. This
     * refusal covers the case scoping cannot: the action rebound to a globally-scoped chord.
     *
     * Among the remaining candidates the highest [Entry.sequence] wins. That is "most recently
     * registered", which is not quite "most recently composed": the registration effect is keyed
     * on panel-active too, so a panel gaining or losing focus re-registers with a fresh sequence.
     * Both orderings answer "the toolbar the user most recently brought to the front", which is
     * the question — and the panel filter above has already removed everything else.
     *
     * **Why `panelActive` alone is enough, though the host's equivalent registry needed a
     * main-panel rank above it.** `LocalIsPanelActive` defaults to `true`, so a surface composed
     * outside a managed panel reads as active too — which is a live hazard for the host, whose
     * entries come from a browser SURFACE that really is composed in sidebar slots and previews.
     * These entries come from a plugin TAB type, and the host composes one only inside a main
     * window panel; a side panel takes a different interface, which this plugin does not
     * implement. The local that separates the two is host-internal and not in the plugin api, so
     * this could not read it in any case. If this plugin ever grows a panel provider, this
     * ranking needs the same preference — and needs that local exported first.
     */
    private fun selectFocusTarget(
        candidates: Collection<Entry>,
        windowId: String,
    ): Entry? =
        candidates
            .filter { it.windowId == windowId && it.panelActive }
            .maxByOrNull { it.sequence }
}
