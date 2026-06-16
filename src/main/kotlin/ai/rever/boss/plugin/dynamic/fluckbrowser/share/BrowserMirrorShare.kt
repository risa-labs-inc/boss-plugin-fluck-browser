package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import ai.rever.boss.plugin.browser.BrowserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

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
private val log = Logger.getLogger("BrowserMirrorShare")

class BrowserMirrorShare(
    val viewToken: String,
    val controlToken: String,
    val sessionSecret: ByteArray,
    val sessionSecretB64: String,
    @Volatile var sessionName: String,
    private val tabsSnapshot: () -> List<TabEntry>,
    private val handleFor: (String) -> BrowserHandle?,
    private val closeTab: (String) -> Boolean,
    private val newTab: (String) -> String?,
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
        // When a WebRTC data channel is live, DOM frames go here (peer.sendDom)
        // instead of the WS outbox — lower latency, no relay. Null = use the socket.
        @Volatile var domSink: ((String) -> Unit)? = null
        // Notified with the active tab's title when the stream switches tabs, so an
        // RTC video track can re-capture the newly focused tab. Null = no video.
        @Volatile var onActiveTab: ((String) -> Unit)? = null
    }

    private val viewers = CopyOnWriteArrayList<ViewerConnection>()

    // Serializes the active-stream state machine (setActiveStream/stop + listener fields
    // + anyController), which is driven concurrently from multiple viewer coroutines.
    private val streamLock = Any()

    @Volatile private var activeStreamTabId: String? = null
    @Volatile private var anyController = false
    // rrweb maskAllInputs for this session; re-applied by restarting the active capture.
    @Volatile var maskInputs: Boolean = false
        private set
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
        viewers.forEach { deliver(it, text) }
    }

    /** Send an already-encoded frame to one viewer, preferring its RTC sink. */
    private fun deliver(vc: ViewerConnection, text: String) {
        val sink = vc.domSink
        if (sink != null) runCatching { sink(text) }
        else runCatching { vc.outbox.trySend(text) }
    }

    /** Messages a freshly-admitted viewer receives before the live stream. */
    /** Rename the session and rebroadcast the layout so viewers update their header. */
    fun rename(name: String) {
        if (sessionName == name) return
        sessionName = name
        broadcast(buildLayout())
    }

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

    /** Set rrweb input masking for the session; restarts the active capture to apply. */
    fun setMaskInputs(enabled: Boolean) = synchronized(streamLock) {
        if (maskInputs == enabled) return@synchronized
        maskInputs = enabled
        activeStreamTabId?.let { setActiveStream(it, forceRestart = true) }
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
        handle.startCoBrowseCapture(
            onEvent = { json ->
                // Runs on a JxBrowser thread — non-blocking enqueue only.
                val text = encode(ServerMessage.DomMutation(tabId, json))
                viewers.forEach { deliver(it, text) }
            },
            maskInputs = maskInputs,
        )
        handle.setCoBrowseControlEnabled(anyController)
        attachNavListeners(tabId, handle)
        pushNavStatus(tabId, handle)
        broadcast(buildLayout())
        // Let any RTC video tracks re-capture the newly active tab.
        val title = runCatching { handle.getTitle() }.getOrNull()
        if (!title.isNullOrBlank()) viewers.forEach { runCatching { it.onActiveTab?.invoke(title) } }
    }

    /** Title of the currently streamed tab (for RTC video capture selection). */
    fun activeTabTitle(): String? = synchronized(streamLock) {
        activeStreamTabId?.let { tid -> runCatching { handleFor(tid)?.getTitle() }.getOrNull() }
    }

    /** Re-emit a fresh snapshot of the active tab (e.g. after a viewer's RTC channel opens). */
    fun resnapshotActive() = synchronized(streamLock) {
        activeStreamTabId?.let { setActiveStream(it, forceRestart = true) }
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
            // Native input: trusted events through the engine's input pipeline.
            is ClientMessage.Pointer -> ifControlActive(vc, msg.tabId) {
                dispatchNative(msg.tabId, """{"kind":"${msg.kind}","x":${msg.x},"y":${msg.y},"button":${msg.button},"clicks":${msg.clicks}}""")
            }
            is ClientMessage.Wheel -> ifControlActive(vc, msg.tabId) {
                dispatchNative(msg.tabId, """{"kind":"wheel","x":${msg.x},"y":${msg.y},"dx":${msg.dx},"dy":${msg.dy}}""")
            }
            is ClientMessage.KeyNative -> ifControlActive(vc, msg.tabId) {
                val payload = ControlJson.encodeToString(
                    NativeKeyPayload.serializer(),
                    NativeKeyPayload(msg.kind, msg.key, msg.code, msg.ch, msg.shift, msg.ctrl, msg.alt, msg.meta)
                )
                dispatchNative(msg.tabId, payload)
            }
            is ClientMessage.Hello -> { /* handshake handled before stream loop */ }
            // Tab management is control-gated but not stream-gated — a controller may
            // close any tab, including a background one. The closed tab's lifecycle
            // teardown calls unregisterTab → refreshLayout, so viewers' tab bars update;
            // if the closed tab was the one being streamed, the viewer re-focuses on the
            // new active tab from that Layout.
            is ClientMessage.CloseTab -> {
                if (vc.canControl) scope.launch(Dispatchers.Main) {
                    val ok = runCatching { closeTab(msg.tabId) }.getOrDefault(false)
                    log.info("Co-browse close tab ${msg.tabId} -> $ok")
                }
                else log.warning("Co-browse close-tab dropped (canControl=false, tab=${msg.tabId})")
            }
            is ClientMessage.NewTab -> {
                if (vc.canControl) scope.launch(Dispatchers.Main) {
                    // Null/blank URL → a fresh tab (about:blank shows the browser's new-tab page).
                    val url = msg.url?.takeIf { it.isNotBlank() } ?: "about:blank"
                    val id = runCatching { newTab(url) }.getOrNull()
                    log.info("Co-browse new tab ($url) -> $id")
                }
                else log.warning("Co-browse new-tab dropped (canControl=false)")
            }
            is ClientMessage.RtcOffer, is ClientMessage.RtcIce -> { /* WebRTC signaling handled by the manager */ }
        }
    }

    // Control ops are accepted only from a controller AND only for the tab currently
    // being streamed — background tabs are never "armed" for remote control.
    private inline fun ifControlActive(vc: ViewerConnection, tabId: String, block: () -> Unit) {
        if (vc.canControl && tabId == activeStreamTabId) block()
        else log.warning("Co-browse control dropped (canControl=${vc.canControl}, msgTab=$tabId, activeTab=$activeStreamTabId)")
    }

    /** Dispatch one native input event (already-encoded JSON) to [tabId]'s handle. */
    private fun dispatchNative(tabId: String, inputJson: String) {
        val handle = handleFor(tabId) ?: return
        scope.launch { runCatching { handle.dispatchCoBrowseInput(inputJson) } }
    }

    private fun applyControl(tabId: String, payload: ControlPayload) {
        val handle = handleFor(tabId) ?: return
        val json = ControlJson.encodeToString(ControlPayload.serializer(), payload)
        scope.launch {
            val status = runCatching { handle.applyCoBrowseControl(json) }.getOrElse { "exc:${it.message}" }
            log.info("Co-browse control '${payload.kind}' -> ${status ?: "refused (host guard)"}")
        }
    }

    /** Stop everything (called on unshare / server stop). */
    fun shutdown() {
        stopActiveStream()
        viewers.forEach { runCatching { it.outbox.close() } }
        viewers.clear()
    }
}
