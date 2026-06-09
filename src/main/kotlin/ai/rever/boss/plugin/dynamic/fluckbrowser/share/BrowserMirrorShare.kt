package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import ai.rever.boss.plugin.browser.BrowserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One co-browse share session. Fans rrweb DOM events from the host's *focused*
 * browser tab out to all connected viewers, and routes control messages back to
 * the real page.
 *
 * v1 streaming model: a single session-wide active stream. At most one tab is
 * captured at a time; a viewer's `FocusTab` switches the streamed tab for
 * everyone (the "one browser instance, switch remote" model). Switching restarts
 * the recorder, which re-emits a fresh rrweb full snapshot so every viewer
 * re-syncs. Background tabs appear in the [Layout] as metadata only.
 *
 * @param tabsSnapshot returns the current set of shareable (fluck) browser tabs.
 * @param handleFor resolves a tabId to its live [BrowserHandle], or null if gone.
 * @param scope coroutine scope for non-blocking outbox sends / control round-trips.
 */
class BrowserMirrorShare(
    val viewToken: String,
    val controlToken: String,
    val sessionSecret: ByteArray,
    val sessionSecretB64: String,
    val sessionName: String,
    private val tabsSnapshot: () -> List<TabEntry>,
    private val handleFor: (String) -> BrowserHandle?,
    private val scope: CoroutineScope,
) {
    data class TabEntry(
        val tabId: String,
        val title: String,
        val url: String,
        val favicon: String?,
        val loading: Boolean,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
    )

    class ViewerConnection(canControl: Boolean, val name: String) {
        // Mutable so a view-only viewer can be upgraded after host approval.
        @Volatile var canControl: Boolean = canControl
        // Bounded, drop-oldest: a slow viewer never blocks the capture thread.
        val outbox = Channel<String>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        @Volatile var grantKey: String? = null
    }

    private val viewers = CopyOnWriteArrayList<ViewerConnection>()

    // Serializes the active-stream state machine (setActiveStream/stop + listener fields
    // + anyController), which is driven concurrently from multiple viewer coroutines.
    private val streamLock = Any()

    @Volatile private var activeStreamTabId: String? = null
    @Volatile private var anyController = false
    /** The tab to focus first (set by share()); used when the first viewer connects. */
    @Volatile var initialActiveTabId: String? = null

    // Listeners attached to the currently-streamed handle (for NavStatus / Layout updates).
    private var navListener: ((String) -> Unit)? = null
    private var titleListener: ((String) -> Unit)? = null
    private var faviconListener: ((String?) -> Unit)? = null
    private var loadingListener: ((Boolean) -> Unit)? = null

    val viewerCount: Int get() = viewers.size

    // ---- Encoding / broadcast ----

    private fun encode(m: ServerMessage): String = ShareJson.encodeToString(ServerMessage.serializer(), m)

    private fun broadcast(m: ServerMessage) {
        val text = encode(m)
        viewers.forEach { runCatching { it.outbox.trySend(text) } }
    }

    /** Messages a freshly-admitted viewer receives before the live stream. */
    fun initialMessages(): List<ServerMessage> = listOf(buildLayout())

    private fun buildLayout(): ServerMessage.Layout {
        val tabs = tabsSnapshot().map {
            BrowserTabNode(
                tabId = it.tabId, title = it.title, url = it.url, favicon = it.favicon,
                loading = it.loading, canGoBack = it.canGoBack, canGoForward = it.canGoForward,
            )
        }
        val active = activeStreamTabId ?: initialActiveTabId ?: tabs.firstOrNull()?.tabId
        return ServerMessage.Layout(tabs = tabs, activeTabId = active, sessionName = sessionName)
    }

    /** Re-broadcast the tab list (call when tabs open/close or metadata changes). */
    fun refreshLayout() {
        broadcast(buildLayout())
    }

    // ---- Viewer lifecycle ----

    fun addViewer(canControl: Boolean, name: String): ViewerConnection {
        val vc = ViewerConnection(canControl, name)
        viewers.add(vc)
        broadcast(ServerMessage.Presence(viewers.size))
        synchronized(streamLock) {
            if (canControl) anyController = true
            // Start (or restart, so this viewer gets a fresh snapshot) the active stream.
            val target = activeStreamTabId ?: initialActiveTabId ?: tabsSnapshot().firstOrNull()?.tabId
            if (target != null) setActiveStream(target, forceRestart = true)
        }
        return vc
    }

    fun removeViewer(vc: ViewerConnection) {
        viewers.remove(vc)
        runCatching { vc.outbox.close() }
        broadcast(ServerMessage.Presence(viewers.size))
        synchronized(streamLock) {
            anyController = viewers.any { it.canControl }
            if (viewers.isEmpty()) stopActiveStream()
            else activeStreamTabId?.let { handleFor(it)?.setCoBrowseControlEnabled(anyController) }
        }
    }

    /** Grant control to a viewer mid-session (after host approval). */
    fun grantControl(vc: ViewerConnection) {
        synchronized(streamLock) {
            anyController = true
            activeStreamTabId?.let { handleFor(it)?.setCoBrowseControlEnabled(true) }
        }
        runCatching { vc.outbox.trySend(encode(ServerMessage.Control(granted = true))) }
    }

    /** Tell a viewer their mid-session control request was declined (resets their UI). */
    fun denyControl(vc: ViewerConnection) {
        runCatching { vc.outbox.trySend(encode(ServerMessage.Control(granted = false))) }
    }

    // ---- Active-stream switching ----

    private fun setActiveStream(tabId: String, forceRestart: Boolean = false) = synchronized(streamLock) {
        if (!forceRestart && tabId == activeStreamTabId) return@synchronized
        val handle = handleFor(tabId) ?: return@synchronized
        // Tear down the previously-streamed tab.
        stopActiveStream()
        activeStreamTabId = tabId
        // Tell viewers to reset their replayer before the fresh snapshot arrives.
        broadcast(ServerMessage.DomFocusAck(tabId))
        // Start capture; rrweb emits Meta + FullSnapshot first, then incrementals.
        handle.startCoBrowseCapture { json ->
            // Runs on a JxBrowser thread — non-blocking enqueue only.
            val text = encode(ServerMessage.DomMutation(tabId, json))
            viewers.forEach { runCatching { it.outbox.trySend(text) } }
        }
        handle.setCoBrowseControlEnabled(anyController)
        attachNavListeners(tabId, handle)
        pushNavStatus(tabId, handle)
        broadcast(buildLayout())
    }

    private fun stopActiveStream() = synchronized(streamLock) {
        val old = activeStreamTabId ?: return@synchronized
        val handle = handleFor(old)
        detachNavListeners(handle)
        handle?.setCoBrowseControlEnabled(false)
        handle?.stopCoBrowseCapture()
        activeStreamTabId = null
    }

    private fun attachNavListeners(tabId: String, handle: BrowserHandle) {
        val nav: (String) -> Unit = { pushNavStatus(tabId, handle); refreshLayout() }
        val title: (String) -> Unit = { pushNavStatus(tabId, handle); refreshLayout() }
        val fav: (String?) -> Unit = { pushNavStatus(tabId, handle); refreshLayout() }
        val load: (Boolean) -> Unit = { pushNavStatus(tabId, handle) }
        navListener = nav; titleListener = title; faviconListener = fav; loadingListener = load
        runCatching { handle.addNavigationListener(nav) }
        runCatching { handle.addTitleListener(title) }
        runCatching { handle.addFaviconListener(fav) }
        runCatching { handle.addLoadingListener(load) }
    }

    private fun detachNavListeners(handle: BrowserHandle?) {
        if (handle != null) {
            navListener?.let { runCatching { handle.removeNavigationListener(it) } }
            titleListener?.let { runCatching { handle.removeTitleListener(it) } }
            faviconListener?.let { runCatching { handle.removeFaviconListener(it) } }
            loadingListener?.let { runCatching { handle.removeLoadingListener(it) } }
        }
        navListener = null; titleListener = null; faviconListener = null; loadingListener = null
    }

    private fun pushNavStatus(tabId: String, handle: BrowserHandle) {
        broadcast(
            ServerMessage.NavStatus(
                tabId = tabId,
                url = runCatching { handle.getCurrentUrl() }.getOrDefault(""),
                title = runCatching { handle.getTitle() }.getOrDefault(""),
                loading = runCatching { handle.isLoading() }.getOrDefault(false),
                canGoBack = runCatching { handle.canGoBack() }.getOrDefault(false),
                canGoForward = runCatching { handle.canGoForward() }.getOrDefault(false),
            )
        )
    }

    // ---- Inbound client messages ----

    fun handleClient(vc: ViewerConnection, msg: ClientMessage) {
        when (msg) {
            is ClientMessage.FocusTab -> setActiveStream(msg.tabId)
            is ClientMessage.RequestControl -> { /* handled by the manager's approval flow */ }
            // --- control-only ---
            is ClientMessage.Navigate -> ifControlActive(vc, msg.tabId) { handleFor(msg.tabId)?.let { h -> scope.launch { h.loadUrl(msg.url) } } }
            is ClientMessage.Back -> ifControlActive(vc, msg.tabId) { handleFor(msg.tabId)?.goBack() }
            is ClientMessage.Forward -> ifControlActive(vc, msg.tabId) { handleFor(msg.tabId)?.goForward() }
            is ClientMessage.Reload -> ifControlActive(vc, msg.tabId) { handleFor(msg.tabId)?.reload() }
            is ClientMessage.Click -> ifControlActive(vc, msg.tabId) { applyControl(msg.tabId, ControlPayload(kind = "click", id = msg.id)) }
            is ClientMessage.Input -> ifControlActive(vc, msg.tabId) { applyControl(msg.tabId, ControlPayload(kind = "input", id = msg.id, value = msg.value)) }
            is ClientMessage.Key -> ifControlActive(vc, msg.tabId) { applyControl(msg.tabId, ControlPayload(kind = "key", id = msg.id, key = msg.key, code = msg.code)) }
            is ClientMessage.Scroll -> ifControlActive(vc, msg.tabId) { applyControl(msg.tabId, ControlPayload(kind = "scroll", id = msg.id, x = msg.x, y = msg.y)) }
            is ClientMessage.Hello -> { /* handshake handled before stream loop */ }
            is ClientMessage.NewTab, is ClientMessage.CloseTab -> { /* tab ops are a later phase */ }
        }
    }

    // Control ops are accepted only from a controller AND only for the tab currently
    // being streamed — background tabs are never "armed" for remote control.
    private inline fun ifControlActive(vc: ViewerConnection, tabId: String, block: () -> Unit) {
        if (vc.canControl && tabId == activeStreamTabId) block()
    }

    private fun applyControl(tabId: String, payload: ControlPayload) {
        val handle = handleFor(tabId) ?: return
        val json = ControlJson.encodeToString(ControlPayload.serializer(), payload)
        scope.launch { runCatching { handle.applyCoBrowseControl(json) } }
    }

    /** Stop everything (called on unshare / server stop). */
    fun shutdown() {
        stopActiveStream()
        viewers.forEach { runCatching { it.outbox.close() } }
        viewers.clear()
    }
}
