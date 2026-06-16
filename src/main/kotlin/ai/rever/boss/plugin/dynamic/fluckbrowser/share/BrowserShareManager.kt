package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.browser.BrowserHandle
import io.ktor.http.CacheControl
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Hosts the co-browse tab-sharing server for the Fluck browser plugin.
 *
 * Modeled on BossTerm's `SessionShareManager` (Ktor CIO WebSocket server, 24h
 * rolling view/control tokens, Pending→Grant→Denied approval, E2E AES-GCM), but
 * slimmed for the browser domain and a single session-wide stream.
 *
 * Binds loopback by default and advertises `localhost` (a secure context, so the
 * web viewer's WebCrypto E2E works out of the box). For cross-machine sharing set
 * `-Dboss.cobrowse.bind=lan` (or `boss.cobrowse.publicUrl` for a tunnel/proxy); the
 * `#k` secret is appended only on secure contexts and a plaintext client is refused
 * over a public/https reach (anti-downgrade).
 */
object BrowserShareManager {
    private val log = Logger.getLogger("BrowserShareManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private const val DEFAULT_PORT = 7701
    private const val MAX_PORT_FALLBACK = 10
    private const val GRANT_TTL_MS = 24L * 60 * 60 * 1000

    private val rng = SecureRandom()
    private val b64Url = Base64.getUrlEncoder().withoutPadding()

    @Volatile private var context: PluginContext? = null
    @Volatile private var engine: EmbeddedServer<*, *>? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var boundHost: String? = null
    @Volatile private var session: BrowserMirrorShare? = null

    /** Live registry of shareable (fluck) browser tabs → their handles. */
    private val tabRegistry = ConcurrentHashMap<String, BrowserHandle>()

    private data class TokenRef(val share: BrowserMirrorShare, val canControl: Boolean)
    private val sharesByToken = ConcurrentHashMap<String, TokenRef>()

    private data class GrantRec(
        val key: String,
        val clientId: String,
        @Volatile var canControl: Boolean,
        @Volatile var expiresAtMs: Long,
    )
    private val grants = ConcurrentHashMap<String, GrantRec>()

    // --- Cloudflare quick tunnel (public remote reach, like BossTerm) ---
    @Volatile private var remoteTunnel: RemoteTunnel? = null
    @Volatile private var tunnelStarting = false
    private val _remoteUrl = MutableStateFlow<String?>(null)
    /** The public https://&lt;rand&gt;.trycloudflare.com base once the tunnel is live, else null. */
    val remoteUrl: StateFlow<String?> = _remoteUrl.asStateFlow()
    private val _shareInfo = MutableStateFlow<ShareInfo?>(null)
    /** Active share's links; updates reactively when the tunnel URL resolves. */
    val shareInfo: StateFlow<ShareInfo?> = _shareInfo.asStateFlow()
    private val _viewerCount = MutableStateFlow(0)
    /** Number of currently connected (approved) viewers; 0 when not sharing. */
    val viewerCount: StateFlow<Int> = _viewerCount.asStateFlow()

    // --- Approval flow (plugin observes [pendingRequests] and shows toasts) ---

    data class PendingShareRequest(
        val id: String,
        val deviceName: String,
        val wantsControl: Boolean,
        val decision: CompletableDeferred<Boolean> = CompletableDeferred(),
    )
    private val _pendingRequests = MutableStateFlow<List<PendingShareRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PendingShareRequest>> = _pendingRequests.asStateFlow()

    fun approveRequest(id: String) = decide(id, true)
    fun denyRequest(id: String) = decide(id, false)
    private fun decide(id: String, approve: Boolean) {
        val req = _pendingRequests.value.firstOrNull { it.id == id } ?: return
        log.info("Co-browse approval decided: ${if (approve) "APPROVED" else "DENIED"} '${req.deviceName}'")
        _pendingRequests.value = _pendingRequests.value.filterNot { it.id == id }
        req.decision.complete(approve)
    }

    internal suspend fun awaitApproval(deviceName: String, wantsControl: Boolean): Boolean {
        val req = PendingShareRequest(UUID.randomUUID().toString(), deviceName, wantsControl)
        _pendingRequests.value = _pendingRequests.value + req
        log.info("Co-browse approval prompt raised for '$deviceName' (pending now ${_pendingRequests.value.size}); waiting up to 2 min")
        val ok = withTimeoutOrNull(2 * 60_000L) { req.decision.await() } ?: false
        _pendingRequests.value = _pendingRequests.value.filterNot { it.id == req.id }
        log.info("Co-browse approval resolved for '$deviceName': $ok")
        return ok
    }

    // --- Lifecycle ---

    fun start(ctx: PluginContext) {
        context = ctx
    }

    fun shutdown() {
        synchronized(lock) {
            session?.shutdown()
            session = null
            sharesByToken.clear()
            grants.clear()
            remoteTunnel?.destroy(); remoteTunnel = null; tunnelStarting = false
            _remoteUrl.value = null
            _shareInfo.value = null
            _viewerCount.value = 0
            val e = engine
            engine = null
            boundPort = null
            boundHost = null
            if (e != null) scope.launch { runCatching { e.stop(300, 1000) } }
        }
        _pendingRequests.value.forEach { it.decision.complete(false) }
        _pendingRequests.value = emptyList()
        context = null
    }

    // --- Tab registry (populated by each FluckBrowserTabComponent) ---

    fun registerTab(tabId: String, handle: BrowserHandle) {
        tabRegistry[tabId] = handle
        session?.refreshLayout()
        // Pre-warm like BossTerm: bind the server + start the Cloudflare tunnel as
        // soon as a shareable tab exists, so the public link is already live by the
        // time the user opens Share (no on-click wait, no hot-reload race).
        prewarm()
    }

    /** Eagerly bind the share server + start the tunnel (no session yet) so links preload. */
    fun prewarm() = synchronized(lock) {
        if (tunnelMode() == "off") return@synchronized // no public tunnel wanted
        if (!ensureServerLocked()) return@synchronized
        boundPort?.let { maybeStartTunnelLocked(it) }
    }

    fun unregisterTab(tabId: String) {
        tabRegistry.remove(tabId)
        session?.refreshLayout()
    }

    // --- Share API ---

    data class ShareInfo(
        val viewUrl: String,
        val controlUrl: String,
        val e2eCode: String?,
        val secure: Boolean,
        val sessionName: String,
        val maskInputs: Boolean,
        /** True while a Cloudflare public link is still being established. */
        val tunnelPending: Boolean = false,
    )

    fun isSharing(): Boolean = session != null

    /** Begin (or refresh) sharing, focusing [tabId] first. Returns the share links. */
    fun share(tabId: String, maskInputs: Boolean = false): ShareInfo? = synchronized(lock) {
        if (!ensureServerLocked()) return null
        val s = ensureSessionLocked()
        s.initialActiveTabId = tabId
        s.setMaskInputs(maskInputs)
        s.refreshLayout()
        val port = boundPort ?: return null
        maybeStartTunnelLocked(port)
        val info = buildShareInfoLocked(s, port)
        _shareInfo.value = info
        info
    }

    /**
     * Re-mint the public link: tear down the current Cloudflare tunnel and start a
     * fresh one (new `<rand>.trycloudflare.com`). The share dialog hot-reloads via
     * [shareInfo] — "Creating secure public link…" while it spins up, then the new
     * QR/links. The view/control tokens are unchanged; only the public host rotates.
     */
    fun refreshLink() = synchronized(lock) {
        val s = session ?: return@synchronized
        val port = boundPort ?: return@synchronized
        remoteTunnel?.destroy(); remoteTunnel = null
        _remoteUrl.value = null
        tunnelStarting = false
        maybeStartTunnelLocked(port)
        republishShareInfo()
    }

    private fun buildShareInfoLocked(s: BrowserMirrorShare, port: Int): ShareInfo {
        val secretB64 = s.sessionSecretB64
        val viewUrl = buildUrl(s.viewToken, secretB64, port)
        val controlUrl = buildUrl(s.controlToken, secretB64, port)
        val e2e = viewUrl.contains("#k=")
        return ShareInfo(
            viewUrl = viewUrl,
            controlUrl = controlUrl,
            e2eCode = if (e2e) BrowserSessionCrypto.fingerprint(s.sessionSecret) else null,
            secure = e2e,
            sessionName = s.sessionName,
            maskInputs = s.maskInputs,
            tunnelPending = tunnelStarting,
        )
    }

    private fun republishShareInfo() {
        val s = session ?: return
        val p = boundPort ?: return
        _shareInfo.value = buildShareInfoLocked(s, p)
    }

    /**
     * Lazily start a Cloudflare quick tunnel (default mode, like BossTerm) so the
     * share link is reachable from any network. Fully async — the URL resolves a few
     * seconds later and is published via [shareInfo]/[remoteUrl]. Falls back to the
     * loopback link if cloudflared isn't installed or the mode is "off".
     */
    private fun maybeStartTunnelLocked(port: Int) {
        val mode = tunnelMode()
        if (mode == "off") return
        if (remoteTunnel != null || _remoteUrl.value != null || tunnelStarting) return
        tunnelStarting = true
        scope.launch {
            var url: String? = null
            // 1) Tailscale Funnel — fastest (WireGuard, often near-direct), no creation
            //    rate limit. Tried first for "tailscale"/"auto"; fails fast if the CLI
            //    isn't installed / logged in / Funnel-enabled, then we fall through.
            if (mode == "tailscale" || mode == "auto") url = startTailscaleTunnelAsync(port)
            // 2) Cloudflare quick tunnel — fast global edge, but per-IP creation can 429.
            if (url == null && (mode == "cloudflare" || mode == "tailscale" || mode == "auto"))
                url = startCloudflareTunnelAsync(port)
            // 3) localhost.run fallback — slower single ssh relay, but independent rate
            //    limits so it works while Cloudflare is throttled. Also the primary when
            //    mode == "localhostrun"/"lhr".
            if (url == null && mode != "off") url = startLocalhostRunTunnelAsync(port)
            synchronized(lock) {
                _remoteUrl.value = url
                tunnelStarting = false
                republishShareInfo()
                log.info("Co-browse tunnel " + (if (url != null) "live: $url" else "failed; loopback only"))
            }
        }
    }

    /**
     * Try Tailscale Funnel. Sets [remoteTunnel] on success; returns the
     * `https://<magic-dns>` URL or null (CLI absent / not logged in / Funnel off).
     */
    private suspend fun startTailscaleTunnelAsync(port: Int): String? {
        if (!TailscaleExposer.isInstalled()) {
            log.info("tailscale not installed; skipping Funnel")
            return null
        }
        val tunnel = TailscaleExposer.start(port) ?: return null
        synchronized(lock) { remoteTunnel?.destroy(); remoteTunnel = tunnel }
        val url = tunnel.awaitUrl(0)
        if (url.isBlank()) {
            tunnel.destroy()
            synchronized(lock) { if (remoteTunnel === tunnel) remoteTunnel = null }
            return null
        }
        return url
    }

    /**
     * Try the Cloudflare quick tunnel a few times (transient registration failures /
     * rate limits are common). Sets [remoteTunnel] on success; returns the URL or null.
     */
    private suspend fun startCloudflareTunnelAsync(port: Int): String? {
        if (!CloudflaredExposer.isInstalled()) {
            log.info("cloudflared not installed; trying localhost.run fallback")
            return null
        }
        var attempt = 0
        while (attempt < 3) {
            attempt++
            val tunnel = CloudflaredExposer.start(port)
            if (tunnel == null) { delay(1500); continue }
            synchronized(lock) { remoteTunnel?.destroy(); remoteTunnel = tunnel }
            val url = tunnel.awaitUrl(15_000)
            if (url != null) { tunnel.awaitReady(); return url }
            log.info("Co-browse cloudflare attempt $attempt produced no URL; retrying")
            tunnel.destroy()
            synchronized(lock) { if (remoteTunnel === tunnel) remoteTunnel = null }
            delay(1500)
        }
        log.info("Co-browse cloudflare tunnel failed after $attempt attempts; falling back to localhost.run")
        return null
    }

    /**
     * Open an ssh reverse tunnel via localhost.run. Sets [remoteTunnel] on success;
     * returns the `*.lhr.life` URL or null.
     */
    private suspend fun startLocalhostRunTunnelAsync(port: Int): String? {
        if (!LocalhostRunExposer.isInstalled()) {
            log.info("ssh not available; co-browse stays on the loopback link")
            return null
        }
        val tunnel = LocalhostRunExposer.start(port) ?: return null
        synchronized(lock) { remoteTunnel?.destroy(); remoteTunnel = tunnel }
        val url = tunnel.awaitUrl(20_000)
        if (url == null) {
            log.info("Co-browse localhost.run produced no URL; loopback only")
            tunnel.destroy()
            synchronized(lock) { if (remoteTunnel === tunnel) remoteTunnel = null }
        }
        return url
    }

    fun unshare() = synchronized(lock) {
        session?.shutdown()
        session = null
        sharesByToken.clear()
        _shareInfo.value = null
        _viewerCount.value = 0
        // Invalidate all 24h grants: a reshare mints a new secret/tokens, so old grant
        // keys must not silently re-admit a viewer to the new session without approval.
        grants.clear()
        // Keep the server bound AND the Cloudflare tunnel warm (like BossTerm) so the
        // next share's link is already live — no cold-start wait, no hot-reload race.
        // The tunnel is torn down only on dispose()/shutdown().
    }

    // --- Server ---

    private fun ensureSessionLocked(): BrowserMirrorShare {
        session?.let { return it }
        val viewToken = newToken()
        val controlToken = newToken()
        val secret = BrowserSessionCrypto.newSessionSecret()
        val s = BrowserMirrorShare(
            viewToken = viewToken,
            controlToken = controlToken,
            sessionSecret = secret,
            sessionSecretB64 = BrowserSessionCrypto.encodeSecretB64Url(secret),
            sessionName = defaultSessionName(),
            tabsSnapshot = ::snapshotTabs,
            handleFor = { tabRegistry[it]?.takeIf { h -> h.isValid } },
            closeTab = { tabId -> context?.activeTabsProvider?.closeTab(tabId) ?: false },
            newTab = { url -> context?.activeTabsProvider?.createBrowserTab(url, "New tab") },
            scope = scope,
        )
        sharesByToken[viewToken] = TokenRef(s, canControl = false)
        sharesByToken[controlToken] = TokenRef(s, canControl = true)
        session = s
        return s
    }

    private fun snapshotTabs(): List<BrowserMirrorShare.TabEntry> =
        tabRegistry.entries.mapNotNull { (id, h) ->
            if (!h.isValid) null
            else BrowserMirrorShare.TabEntry(
                tabId = id,
                title = runCatching { h.getTitle() }.getOrDefault("").ifBlank { "Tab" },
                url = runCatching { h.getCurrentUrl() }.getOrDefault(""),
                favicon = null,
                loading = runCatching { h.isLoading() }.getOrDefault(false),
                canGoBack = runCatching { h.canGoBack() }.getOrDefault(false),
                canGoForward = runCatching { h.canGoForward() }.getOrDefault(false),
            )
        }

    private fun ensureServerLocked(): Boolean {
        if (engine != null) return true
        val host = resolveBindHost()
        for (offset in 0 until MAX_PORT_FALLBACK) {
            val port = DEFAULT_PORT + offset
            if (port > 65535) break
            if (!portBindable(host, port)) {
                log.warning("Co-browse port $host:$port in use, trying next")
                continue
            }
            try {
                val started = embeddedServer(CIO, host = host, port = port) {
                    install(WebSockets) {
                        // Keepalive: Cloudflare's proxy drops idle WebSockets after
                        // ~100s. Without pings, a viewer waiting on host approval (or
                        // just idle on a static page) gets disconnected mid-session
                        // and reconnects — losing its pending approval/control state.
                        pingPeriodMillis = 30_000
                        timeoutMillis = 60_000
                    }
                    routing {
                        webSocket("/ws/{token}") { serveViewer(this) }
                        staticResources("/", "cobrowse-viewer", index = "index.html") {
                            cacheControl { listOf(CacheControl.NoCache(null)) }
                        }
                    }
                }
                started.start(wait = false)
                engine = started
                boundPort = port
                boundHost = host
                log.info("Co-browse server bound on $host:$port")
                return true
            } catch (e: Throwable) {
                val bind = generateSequence(e as Throwable?) { it.cause }.filterIsInstance<BindException>().firstOrNull()
                if (bind != null) continue
                log.severe("Co-browse server failed to start on $host:$port: ${e.message}")
                return false
            }
        }
        log.severe("Co-browse server could not bind any port from $DEFAULT_PORT")
        return false
    }

    // CIO binds asynchronously, so a taken port surfaces as an uncaught BindException
    // instead of throwing from start(). Probe synchronously first.
    private fun portBindable(host: String, port: Int): Boolean = runCatching {
        ServerSocket().use { ss ->
            ss.reuseAddress = false
            ss.bind(InetSocketAddress(host, port))
        }
        true
    }.getOrDefault(false)

    /** Handle one viewer WebSocket: E2E handshake, approval, then admit + stream. */
    private suspend fun serveViewer(ws: DefaultWebSocketServerSession) {
        val token = ws.call.parameters["token"]
        val ref = token?.let { sharesByToken[it] }
        if (ref == null) {
            ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unknown or expired share token"))
            return
        }
        val share = ref.share
        log.info("Co-browse viewer connecting (control-link=${ref.canControl})")

        // --- E2E handshake: first frame is a plaintext Kex (encrypted path) or Hello (legacy). ---
        val first = withTimeoutOrNull(10_000L) { runCatching { ws.incoming.receive() }.getOrNull() }
        val kex = (first as? Frame.Text)?.let { decodeKex(it.readText()) }
        var serverCipher: BrowserSessionCrypto.FrameCipher? = null
        var clientCipher: BrowserSessionCrypto.FrameCipher? = null
        val hello: ClientMessage.Hello?
        if (kex != null) {
            if (kex.v != 1) { ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unsupported encryption version")); return }
            val saltC = runCatching { BrowserSessionCrypto.decodeSecretB64Url(kex.salt) }.getOrNull()
            if (saltC == null) { ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Bad handshake")); return }
            val saltS = BrowserSessionCrypto.randomSalt()
            val keys = BrowserSessionCrypto.deriveKeys(share.sessionSecret, saltC, saltS)
            serverCipher = BrowserSessionCrypto.FrameCipher(keys.kS2c, BrowserSessionCrypto.DIR_S2C)
            clientCipher = BrowserSessionCrypto.FrameCipher(keys.kC2s, BrowserSessionCrypto.DIR_C2S)
            ws.send(Frame.Text(encodeKex(Kex(v = 1, salt = BrowserSessionCrypto.encodeSecretB64Url(saltS), confirm = keys.confirmB64))))
            val helloFrame = withTimeoutOrNull(10_000L) { runCatching { ws.incoming.receive() }.getOrNull() }
            if (helloFrame == null) { ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Handshake timeout")); return }
            if (helloFrame !is Frame.Binary) { ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Expected an encrypted handshake")); return }
            val helloText = runCatching { clientCipher.decrypt(helloFrame.data) }.getOrNull()
            if (helloText == null) { ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Wrong or missing encryption key")); return }
            hello = decodeClient(helloText) as? ClientMessage.Hello
        } else {
            // Plaintext first frame is fine on loopback/LAN-http (no relay, no WebCrypto), but
            // over a public/https reach it must be refused so nothing streams unencrypted.
            if (requireE2E()) {
                runCatching {
                    ws.send(Frame.Text(encodeServer(ServerMessage.Denied(
                        "This shared session is end-to-end encrypted. Open it over the secure link."))))
                }
                ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Encryption required"))
                return
            }
            hello = (first as? Frame.Text)?.let { decodeClient(it.readText()) } as? ClientMessage.Hello
        }

        // Send/receive seam: encrypted binary frames when negotiated, else plaintext text frames.
        suspend fun send(m: ServerMessage) {
            val text = encodeServer(m)
            serverCipher?.let { ws.send(Frame.Binary(true, it.encrypt(text))) } ?: ws.send(Frame.Text(text))
        }
        fun decodeIncoming(frame: Frame): ClientMessage? = when {
            clientCipher != null && frame is Frame.Binary -> runCatching { decodeClient(clientCipher.decrypt(frame.data)) }.getOrNull()
            clientCipher == null && frame is Frame.Text -> decodeClient(frame.readText())
            else -> null
        }

        val clientId = hello?.clientId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        var canControl = ref.canControl
        var accessKey: String? = null

        // --- Approval (always required) + 24h rolling grant ---
        run {
            val now = System.currentTimeMillis()
            val existing = hello?.key?.let { grants[it] }
            if (existing != null && existing.expiresAtMs > now) {
                existing.expiresAtMs = now + GRANT_TTL_MS
                canControl = canControl && existing.canControl // view link can't be upgraded by a control key
                accessKey = existing.key
                send(ServerMessage.Grant(existing.key, existing.expiresAtMs, canControl))
            } else {
                hello?.key?.let { grants.remove(it) }
                val deviceName = hello?.name?.takeIf { it.isNotBlank() } ?: "Browser (${clientId.take(6)})"
                log.info("Co-browse: sending Pending + awaiting host approval for '$deviceName'")
                runCatching { send(ServerMessage.Pending) }
                val approved = awaitApproval(deviceName, ref.canControl)
                if (!approved) {
                    runCatching { send(ServerMessage.Denied("Not approved")) }
                    ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Not approved"))
                    return
                }
                val key = newToken()
                val exp = System.currentTimeMillis() + GRANT_TTL_MS
                grants[key] = GrantRec(key, clientId, ref.canControl, exp)
                accessKey = key
                send(ServerMessage.Grant(key, exp, canControl))
            }
        }

        // --- Admit: layout, then live stream ---
        share.initialMessages().forEach { send(it) }
        send(ServerMessage.Control(granted = canControl))
        val vc = share.addViewer(canControl, hello?.name?.takeIf { it.isNotBlank() } ?: "Viewer (${clientId.take(6)})")
        _viewerCount.value = share.viewerCount
        vc.grantKey = accessKey
        log.info("Co-browse viewer ADMITTED (canControl=$canControl); entering input loop")
        val sc = serverCipher

        val writer = ws.launch {
            for (text in vc.outbox) {
                sc?.let { ws.send(Frame.Binary(true, it.encrypt(text))) } ?: ws.send(Frame.Text(text))
            }
        }

        // --- WebRTC peer (optional upgrade): direct data channel for input + DOM,
        // signaling relayed over this socket. Falls back transparently to WS. ---
        val rtcProvider = context?.coBrowseRtcProvider
        log.info("Co-browse RTC: enabled=${rtcEnabled()}, provider=${rtcProvider != null}")
        var rtc: ai.rever.boss.plugin.api.CoBrowseRtcPeer? = null
        rtc = if (!rtcEnabled()) null else rtcProvider?.createPeer(
            onAnswer = { sdp -> log.info("Co-browse RTC: answer ready, relaying to viewer"); runCatching { vc.outbox.trySend(encodeServer(ServerMessage.RtcAnswer(sdp))) } },
            onIce = { c -> runCatching { vc.outbox.trySend(encodeServer(ServerMessage.RtcIce(c))) } },
            onInput = { json -> decodeClient(json)?.let { m -> share.handleClient(vc, m) } },
            onState = { connected ->
                if (connected) {
                    val peer = rtc
                    vc.domSink = { text -> peer?.sendDom(text) }
                    // Re-capture the video track whenever the streamed tab changes.
                    vc.onActiveTab = { title -> peer?.startVideo(title) }
                    log.info("Co-browse RTC connected for '${vc.name}' — streaming over data channel")
                    // Kick off pixel-accurate video for the current tab (covers video/canvas).
                    share.activeTabTitle()?.let { peer?.startVideo(it) }
                } else {
                    vc.domSink = null
                    vc.onActiveTab = null
                    log.info("Co-browse RTC closed for '${vc.name}' — back to WebSocket")
                }
                // Re-emit a snapshot so the viewer's replayer re-syncs on whichever
                // transport is now active (the dom channel is ordered+reliable).
                share.resnapshotActive()
            },
        )
        if (rtc != null) {
            log.info("Co-browse RTC: sending RtcConfig to viewer")
            runCatching { send(ServerMessage.RtcConfig(iceServers = iceServers(), enabled = true)) }
        } else {
            log.info("Co-browse RTC: peer unavailable — viewer stays on WebSocket")
        }

        try {
            for (frame in ws.incoming) {
                val msg = decodeIncoming(frame)
                if (msg == null) {
                    log.warning("Co-browse: dropped undecodable frame (encrypted=${clientCipher != null}, type=${frame.frameType})")
                    continue
                }
                // Log meaningful control intents (skip high-frequency move/wheel noise).
                if (msg is ClientMessage.Pointer && msg.kind != "move" && msg.kind != "drag") {
                    log.fine("Co-browse RX: pointer ${msg.kind} @${msg.x},${msg.y} (canControl=${vc.canControl})")
                } else if (msg is ClientMessage.KeyNative && msg.kind == "keydown") {
                    log.fine("Co-browse RX: key '${msg.key}' (canControl=${vc.canControl})")
                }
                when {
                    msg is ClientMessage.RtcOffer -> { log.info("Co-browse RTC: received offer from viewer"); rtc?.acceptOffer(msg.sdp) }
                    msg is ClientMessage.RtcIce -> rtc?.addRemoteIce(msg.candidate)
                    msg is ClientMessage.RequestControl && !vc.canControl -> {
                        // Mid-session control upgrade: ask the host, then flip + persist.
                        scope.launch {
                            val ok = awaitApproval(vc.name, wantsControl = true)
                            if (ok) {
                                vc.canControl = true
                                vc.grantKey?.let { grants[it]?.canControl = true }
                                share.grantControl(vc)
                            } else {
                                share.denyControl(vc)
                            }
                        }
                    }
                    else -> share.handleClient(vc, msg)
                }
            }
        } catch (_: Throwable) {
            // viewer disconnected
        } finally {
            writer.cancel()
            runCatching { rtc?.close() }
            share.removeViewer(vc)
            _viewerCount.value = share.viewerCount
        }
    }

    // --- Helpers ---

    // --- Bind / advertise / E2E (config via -Dboss.cobrowse.* or BOSS_COBROWSE_* env) ---

    private fun cfg(prop: String, env: String, default: String): String =
        System.getProperty(prop)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: default
    private fun bindMode(): String = cfg("boss.cobrowse.bind", "BOSS_COBROWSE_BIND", "loopback").lowercase()
    private fun customBindHost(): String = cfg("boss.cobrowse.bindHost", "BOSS_COBROWSE_BIND_HOST", "")
    private fun publicUrl(): String = cfg("boss.cobrowse.publicUrl", "BOSS_COBROWSE_PUBLIC_URL", "")
    // "cloudflare" (default): quick tunnel, falling back to localhost.run on failure.
    // "auto"/"tailscale": Tailscale Funnel first (needs tailscale set up), then
    //   cloudflare, then localhost.run.
    // "localhostrun"/"lhr": skip the others, use the ssh localhost.run tunnel directly.
    // "off": no public tunnel (loopback / LAN only).
    private fun tunnelMode(): String = cfg("boss.cobrowse.tunnel", "BOSS_COBROWSE_TUNNEL", "cloudflare").lowercase()
    private fun rtcEnabled(): Boolean = cfg("boss.cobrowse.rtc", "BOSS_COBROWSE_RTC", "true").lowercase() != "false"

    /**
     * ICE servers the viewer uses to build its peer connection. STUN locates the
     * public address; **TURN relays the media when hole-punching fails** — which it
     * does across most real cross-network setups (symmetric NAT / mobile CGNAT). With
     * STUN only, a remote viewer's WebRTC never connects and the stream falls back to
     * the (slow) WebSocket-over-tunnel path. So TURN is what makes a cross-network
     * share feel like a same-machine one.
     *
     * Must stay in lockstep with the host peer's own ICE config
     * (`CoBrowseRtcPeerImpl.buildIceServersJson` in BossConsole) — both read the same
     * `boss.cobrowse.turn` and the same free default, so the two peers always agree.
     *
     *  - `boss.cobrowse.turn=turn:host:port[,user,cred]` → your own TURN (most reliable;
     *    metered.ca free 5GB/mo, or self-hosted coturn).
     *  - else (default) → Open Relay Project free public TURN, so a cross-NAT viewer
     *    connects with zero config. Disable with `boss.cobrowse.turn.free=false`.
     */
    private fun iceServers(): List<RtcIceServer> {
        val list = mutableListOf(
            RtcIceServer("stun:stun.l.google.com:19302"),
            RtcIceServer("stun:stun1.l.google.com:19302"),
        )
        val turn = cfg("boss.cobrowse.turn", "BOSS_COBROWSE_TURN", "")
        if (turn.isNotBlank()) {
            val parts = turn.split(",").map { it.trim() }
            list += RtcIceServer(
                urls = parts.getOrElse(0) { turn },
                username = parts.getOrNull(1),
                credential = parts.getOrNull(2),
            )
        } else if (cfg("boss.cobrowse.turn.free", "BOSS_COBROWSE_TURN_FREE", "true").lowercase() != "false") {
            // Open Relay Project free public TURN — shared/best-effort. Ports 80 + 443
            // (and TCP/TLS) maximize the chance of punching through restrictive firewalls.
            val u = "openrelayproject"; val c = "openrelayproject"
            list += RtcIceServer("turn:openrelay.metered.ca:80", u, c)
            list += RtcIceServer("turn:openrelay.metered.ca:443", u, c)
            list += RtcIceServer("turn:openrelay.metered.ca:443?transport=tcp", u, c)
            list += RtcIceServer("turns:openrelay.metered.ca:443", u, c)
        }
        return list
    }

    private fun resolveBindHost(): String = when (bindMode()) {
        "lan" -> "0.0.0.0"
        "custom" -> customBindHost().ifBlank { "127.0.0.1" }
        else -> "127.0.0.1"
    }

    private fun advertisedHost(): String {
        val bound = boundHost ?: "127.0.0.1"
        if (bound != "0.0.0.0") return bound
        return siteLocalIpv4() ?: "localhost"
    }

    private fun siteLocalIpv4(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

    /**
     * True when a browser opening [url] has WebCrypto (a secure context: https or
     * loopback). Detection is syntactic (scheme + host literal), not DNS-resolved —
     * an http host that merely resolves to loopback is treated as non-secure.
     */
    private fun e2eCapable(url: String): Boolean {
        if (url.startsWith("https://", ignoreCase = true)) return true
        val h = hostOf(url).lowercase()
        // URI.host keeps the brackets for IPv6 literals, hence "[::1]".
        return h == "localhost" || h == "::1" || h == "[::1]" || h.startsWith("127.")
    }

    /** Refuse plaintext when reachable via a public/https URL (anti-downgrade). */
    private fun requireE2E(): Boolean {
        val pub = _remoteUrl.value ?: publicUrl()
        return pub.isNotBlank() && e2eCapable(pub)
    }

    /** Build a share URL; append the #k secret only when the context can do WebCrypto. */
    private fun buildUrl(token: String, secretB64: String, port: Int): String {
        // Prefer the live Cloudflare tunnel, then a user-supplied public URL, then loopback.
        val pub = _remoteUrl.value ?: publicUrl()
        val base = if (pub.isNotBlank()) "${pub.trimEnd('/')}/?t=$token"
                   else "http://${advertisedHost()}:$port/?t=$token"
        return if (e2eCapable(base)) "$base#k=$secretB64" else base
    }

    private fun newToken(): String = ByteArray(16).also { rng.nextBytes(it) }.let { b64Url.encodeToString(it) }

    fun defaultSessionName(): String =
        "Browser on " + (runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "this machine")

    /** Rename the share as viewers see it (blank → default); rebroadcasts Layout + republishes links. */
    fun setSessionName(name: String) = synchronized(lock) {
        val s = session ?: return@synchronized
        s.rename(name.ifBlank { defaultSessionName() })
        republishShareInfo()
    }
}
