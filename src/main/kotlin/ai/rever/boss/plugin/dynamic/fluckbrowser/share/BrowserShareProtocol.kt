package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol for co-browse (DOM state-sync) tab sharing.
 *
 * Modeled on BossTerm's `ShareProtocol`, but the per-tab payload is rrweb DOM
 * events (full snapshot + incremental mutations) instead of terminal escape
 * sequences, and the layout is a flat list of browser tabs (no split tree).
 *
 * JSON, discriminator `t`. Unknown keys are ignored and defaults encoded so the
 * protocol can evolve additively.
 */
val ShareJson: Json = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Compact JSON for control payloads sent into the page (omits null fields). */
val ControlJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
}

/** One remote browser tab as metadata for the viewer's tab bar. */
@Serializable
data class BrowserTabNode(
    val tabId: String,
    val title: String = "",
    val url: String = "",
    val favicon: String? = null,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/**
 * E2E key-exchange frame (plaintext JSON, sent before the encrypted stream).
 * Mirrors BossTerm's `Kex`: client sends its salt; host replies salt + confirm.
 */
@Serializable
data class Kex(
    val v: Int = 1,
    val salt: String,
    val confirm: String? = null,
)

/** Host → viewer messages. */
@Serializable
sealed class ServerMessage {
    /** Full tab list + which tab the host is currently streaming. */
    @Serializable
    @SerialName("layout")
    data class Layout(
        val tabs: List<BrowserTabNode>,
        val activeTabId: String? = null,
        val sessionName: String? = null,
    ) : ServerMessage()

    /** Initial full DOM (rrweb FullSnapshot event JSON) for the focused tab. */
    @Serializable
    @SerialName("domSnapshot")
    data class DomSnapshot(
        val tabId: String,
        val event: String,
        val viewportW: Int = 0,
        val viewportH: Int = 0,
    ) : ServerMessage()

    /** Incremental DOM change (rrweb incremental event JSON). The hot path. */
    @Serializable
    @SerialName("domMutation")
    data class DomMutation(
        val tabId: String,
        val event: String,
    ) : ServerMessage()

    /** Host acknowledges it switched its active stream to [tabId]. */
    @Serializable
    @SerialName("domFocusAck")
    data class DomFocusAck(val tabId: String) : ServerMessage()

    /** Navigation / chrome status for a tab (drives the viewer's URL bar + buttons). */
    @Serializable
    @SerialName("navStatus")
    data class NavStatus(
        val tabId: String,
        val url: String,
        val title: String = "",
        val favicon: String? = null,
        val loading: Boolean = false,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
    ) : ServerMessage()

    @Serializable
    @SerialName("presence")
    data class Presence(val viewers: Int) : ServerMessage()

    @Serializable
    @SerialName("control")
    data class Control(val granted: Boolean) : ServerMessage()

    @Serializable
    @SerialName("pending")
    data object Pending : ServerMessage()

    @Serializable
    @SerialName("grant")
    data class Grant(val key: String, val expiresAt: Long, val control: Boolean) : ServerMessage()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String? = null) : ServerMessage()

    // --- WebRTC signaling (host → viewer) ---
    /** ICE server config so the viewer can build its RTCPeerConnection. */
    @Serializable
    @SerialName("rtcConfig")
    data class RtcConfig(val iceServers: List<RtcIceServer> = emptyList(), val enabled: Boolean = true) : ServerMessage()

    /** Host peer's SDP answer to the viewer's offer. */
    @Serializable
    @SerialName("rtcAnswer")
    data class RtcAnswer(val sdp: String) : ServerMessage()

    /** Trickled ICE candidate from the host peer. */
    @Serializable
    @SerialName("rtcIce")
    data class RtcIce(val candidate: String) : ServerMessage()
}

@Serializable
data class RtcIceServer(val urls: String, val username: String? = null, val credential: String? = null)

/** Viewer → host messages. */
@Serializable
sealed class ClientMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(val name: String? = null, val clientId: String? = null, val key: String? = null) : ClientMessage()

    /** Viewer focused a tab → host switches its single active stream to it. */
    @Serializable
    @SerialName("focusTab")
    data class FocusTab(val tabId: String) : ClientMessage()

    @Serializable
    @SerialName("requestControl")
    data class RequestControl(val tabId: String? = null) : ClientMessage()

    // --- control-only (applied to the host's real page) ---
    @Serializable
    @SerialName("navigate")
    data class Navigate(val tabId: String, val url: String) : ClientMessage()

    @Serializable
    @SerialName("back")
    data class Back(val tabId: String) : ClientMessage()

    @Serializable
    @SerialName("forward")
    data class Forward(val tabId: String) : ClientMessage()

    @Serializable
    @SerialName("reload")
    data class Reload(val tabId: String) : ClientMessage()

    /** Click on the rrweb mirror node [id]. */
    @Serializable
    @SerialName("click")
    data class Click(val tabId: String, val id: Int) : ClientMessage()

    /** Set value of input/contenteditable node [id]. */
    @Serializable
    @SerialName("input")
    data class Input(val tabId: String, val id: Int, val value: String) : ClientMessage()

    /** Dispatch a keystroke at node [id]. */
    @Serializable
    @SerialName("key")
    data class Key(val tabId: String, val id: Int, val key: String = "", val code: String = "") : ClientMessage()

    /** Scroll node [id] (or document when null) to (x, y). */
    @Serializable
    @SerialName("scroll")
    data class Scroll(val tabId: String, val id: Int? = null, val x: Int = 0, val y: Int = 0) : ClientMessage()

    // --- native input (control-only; dispatched through the engine's input
    // pipeline as TRUSTED events — preferred over the semantic Click/Input/Key
    // path, which synthesizes untrusted DOM events) ---

    /** Mouse event at viewport CSS-px (x, y). kind: down|up|move|drag. button: 0=primary 1=middle 2=secondary. */
    @Serializable
    @SerialName("ptr")
    data class Pointer(
        val tabId: String,
        val kind: String,
        val x: Int,
        val y: Int,
        val button: Int = 0,
        val clicks: Int = 1,
    ) : ClientMessage()

    /** Wheel rotation at viewport (x, y) with CSS-px deltas. */
    @Serializable
    @SerialName("whl")
    data class Wheel(val tabId: String, val x: Int, val y: Int, val dx: Float = 0f, val dy: Float = 0f) : ClientMessage()

    /** Raw keystroke for native dispatch. kind: keydown|keyup. [ch] is the printable char, if any. */
    @Serializable
    @SerialName("keyn")
    data class KeyNative(
        val tabId: String,
        val kind: String,
        val key: String = "",
        val code: String = "",
        val ch: String = "",
        val shift: Boolean = false,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    ) : ClientMessage()

    @Serializable
    @SerialName("newTab")
    data class NewTab(val url: String? = null) : ClientMessage()

    @Serializable
    @SerialName("closeTab")
    data class CloseTab(val tabId: String) : ClientMessage()

    // --- WebRTC signaling (viewer → host); viewer is the offerer ---
    /** Viewer's SDP offer (creates the data channels). */
    @Serializable
    @SerialName("rtcOffer")
    data class RtcOffer(val sdp: String) : ClientMessage()

    /** Trickled ICE candidate from the viewer. */
    @Serializable
    @SerialName("rtcIce")
    data class RtcIce(val candidate: String) : ClientMessage()
}

/**
 * Semantic control payload injected into the host page by
 * `BrowserHandle.applyCoBrowseControl`. Encoded with [ControlJson] so absent
 * fields are omitted (e.g. `{"kind":"click","id":42}`).
 */
/**
 * Native keystroke payload for `BrowserHandle.dispatchCoBrowseInput` — encoded
 * with [ControlJson] so key/char strings are JSON-escaped properly.
 */
@Serializable
data class NativeKeyPayload(
    val kind: String,
    val key: String = "",
    val code: String = "",
    val ch: String = "",
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

@Serializable
data class ControlPayload(
    val kind: String,
    val id: Int? = null,
    val value: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val key: String? = null,
    val code: String? = null,
)

// ---- Codec helpers (mirror BossTerm's ShareProtocol) ----

fun encodeServer(m: ServerMessage): String = ShareJson.encodeToString(ServerMessage.serializer(), m)

fun decodeClient(s: String): ClientMessage? =
    runCatching { ShareJson.decodeFromString(ClientMessage.serializer(), s) }.getOrNull()

fun encodeKex(k: Kex): String = ShareJson.encodeToString(Kex.serializer(), k)

/** Parse a plaintext first-frame as a [Kex]; null if it's not one (e.g. a plaintext Hello). */
fun decodeKex(s: String): Kex? =
    runCatching { ShareJson.decodeFromString(Kex.serializer(), s).takeIf { it.salt.isNotBlank() } }.getOrNull()
