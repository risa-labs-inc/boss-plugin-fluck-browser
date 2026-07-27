package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.DashboardContentProvider
import ai.rever.boss.plugin.api.InternalBrowserTabData
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.ScreenCaptureProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.api.TabUpdateProvider
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.api.UrlHistoryEntry
import ai.rever.boss.plugin.api.UrlHistoryProvider
import ai.rever.boss.plugin.api.ZoomSettingsProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserContextMenuInfo
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.browser.PopupNavigation
import ai.rever.boss.plugin.dynamic.fluckbrowser.share.BrowserShareManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle.Callbacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.URLEncoder
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import kotlin.math.abs

/**
 * Fluck Browser tab component (Dynamic Plugin)
 *
 * Uses BrowserService from host for full browser functionality.
 * Features:
 * - URL bar with navigation and smart URL processing
 * - Back/forward/reload/stop controls
 * - Smart zoom indicator (only shows when zoomed)
 * - Loading indicator with stop functionality
 * - Security indicator (HTTPS lock icon)
 * - Bookmark star button
 * - Context menu with navigation, clipboard, and link operations
 * - Title and favicon updates
 * - Download integration (via host)
 */
class FluckBrowserTabComponent(
    ctx: ComponentContext,
    override val config: TabInfo,
    private val pluginContext: PluginContext
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = FluckBrowserTabType

    private val browserService: BrowserService? = pluginContext.browserService
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Store factory for lazy provider creation (provider created after tab is registered)
    private val tabUpdateProviderFactory: TabUpdateProviderFactory? = pluginContext.tabUpdateProviderFactory

    // All persistent UI state is hoisted onto the Component, which survives
    // tab switches. Otherwise each tab switch would dispose the JxBrowser
    // instance AND drop every state field; even if the handle were
    // preserved, the navigation/loading/title listeners attached on first
    // create would keep writing into the now-dead state objects the OLD
    // composition allocated, while the NEW composition saw stale defaults
    // (e.g. error = "Initializing browser…", urlBarText = initialUrl) and
    // never recovered.
    //
    // Disposal happens on lifecycle.onDestroy (i.e. tab close), not on
    // transient composition exit.
    internal val state = FluckBrowserTabState()

    init {
        lifecycle.subscribe(
            callbacks = object : Callbacks {
                override fun onDestroy() {
                    BrowserShareManager.unregisterTab(config.id)
                    val handle = state.browserHandle
                    state.browserHandle = null
                    // Off the UI thread so closing a tab never hitches the app.
                    if (handle != null) disposeBrowserHandleOffThread(handle)
                    // Adopt any in-flight (or completed-but-unconsumed) creation:
                    // it runs on the never-cancelled browserCreationScope precisely
                    // so its result stays retrievable here — dispose whatever it
                    // eventually produces instead of leaking the browser + renderer
                    // process.
                    state.browserCreation?.let { pending ->
                        state.browserCreation = null
                        abandonBrowserCreation(pending)
                    }
                    coroutineScope.cancel()
                }
            }
        )
    }

    @Composable
    override fun Content() {
        // NOTE: no isAvailable() call here. On hosts before the lazy-availability
        // fix, isAvailable() booted the whole Chromium engine synchronously inside
        // this composition — a multi-second UI freeze on cold start. Engine problems
        // now surface through createBrowser's error/retry UI instead.
        if (browserService != null) {
            // Extract initial URL from config - handle both FluckBrowserTabData and built-in FluckTabInfo
            val initialUrl = getInitialUrl(config)

            FluckBrowserTabContent(
                initialUrl = initialUrl,
                browserService = browserService,
                coroutineScope = coroutineScope,
                hoistedState = state,
                tabUpdateProviderFactory = tabUpdateProviderFactory,
                tabId = config.id,
                tabTypeId = config.typeId,
                dashboardContentProvider = pluginContext.dashboardContentProvider,
                secretDataProvider = pluginContext.secretDataProvider,
                bookmarkDataProvider = pluginContext.getPluginAPI(BookmarkDataProvider::class.java) ?: pluginContext.bookmarkDataProvider,
                activeTabsProvider = pluginContext.activeTabsProvider,
                zoomSettingsProvider = pluginContext.zoomSettingsProvider,
                urlHistoryProvider = pluginContext.urlHistoryProvider,
                screenCaptureProvider = pluginContext.screenCaptureProvider,
                onOpenInNewTab = { url ->
                    pluginContext.splitViewOperations?.openUrlInActivePanel(
                        url = url,
                        title = "New Tab",
                        forceNewTab = true
                    )
                },
                onCloseTab = {
                    pluginContext.activeTabsProvider?.closeTab(config.id)
                }
            )
        } else {
            // Fallback stub content when browser service not available
            FluckBrowserStubContent()
        }
    }

    /**
     * Extract initial URL from TabInfo config.
     * Handles both FluckBrowserTabData (plugin) and FluckTabInfo (built-in) via reflection.
     */
    private fun getInitialUrl(tabInfo: TabInfo): String {
        // Try our own data class first
        if (tabInfo is FluckBrowserTabData) {
            return tabInfo.initialUrl
        }

        // Try to get 'url' property via reflection (for built-in FluckTabInfo)
        return try {
            val urlProperty = tabInfo::class.members.find { it.name == "url" }
            val url = urlProperty?.call(tabInfo) as? String
            if (!url.isNullOrBlank()) url else FluckBrowserTabData.DEFAULT_URL
        } catch (e: Exception) {
            // Try 'currentUrl' property as fallback
            try {
                val currentUrlProperty = tabInfo::class.members.find { it.name == "currentUrl" }
                val currentUrl = currentUrlProperty?.call(tabInfo) as? String
                if (!currentUrl.isNullOrBlank()) currentUrl else FluckBrowserTabData.DEFAULT_URL
            } catch (e2: Exception) {
                FluckBrowserTabData.DEFAULT_URL
            }
        }
    }
}

/**
 * Platform detection for keyboard shortcut modifier keys.
 * On macOS, we use Meta (⌘), on other platforms we use Ctrl.
 */
private val isMacOS: Boolean by lazy {
    System.getProperty("os.name").lowercase().contains("mac")
}

/**
 * Check if the primary modifier key is pressed (Cmd on macOS, Ctrl on others).
 */
private fun KeyEvent.isPrimaryModifierPressed(): Boolean {
    return if (isMacOS) isMetaPressed else isCtrlPressed
}

internal enum class BrowserMouseNavigation {
    BACK,
    FORWARD
}

/**
 * Maps only the auxiliary buttons owned by the browser chrome overlay.
 *
 * Button 2 (middle-click) deliberately maps to null because its dedicated
 * pointer handler resolves the target before page scripts can rewrite it.
 */
internal fun browserMouseNavigationForButton(awtButton: Int?): BrowserMouseNavigation? =
    when (awtButton) {
        4, 6, 8 -> BrowserMouseNavigation.BACK
        5, 7, 9 -> BrowserMouseNavigation.FORWARD
        else -> null
    }

/**
 * Resolves a middle-click target from the pressed viewport point.
 *
 * Compose reports backing-pixel coordinates for the off-screen browser view.
 * Chromium's own devicePixelRatio is the authoritative conversion to CSS
 * pixels and also accounts for fractional display scaling and page zoom.
 *
 * Links return a `link:` result for direct BOSS-tab creation; submit controls
 * submit through a temporary `_blank` target so the popup bridge can preserve
 * POST data.
 */
internal fun middleClickTargetAtPointScript(x: Float, y: Float): String = """
    (() => {
        const deviceScale = window.devicePixelRatio || 1;
        let element = document.elementFromPoint(
            $x / deviceScale,
            $y / deviceScale
        );
        if (!element) return null;

        const link = element.closest('a[href], area[href]');
        if (link && link.href) {
            const rawHref = typeof link.href === 'string'
                ? link.href
                : link.href.baseVal;
            if (!rawHref) return null;

            const resolvedUrl = new URL(rawHref, document.baseURI);
            if (resolvedUrl.protocol !== 'http:' && resolvedUrl.protocol !== 'https:') {
                return null;
            }
            return 'link:' + resolvedUrl.href;
        }

        const submitter = element.closest(
            'button[type="submit"], input[type="submit"], input[type="image"]'
        );
        if (!submitter || !submitter.form) return null;

        const previousTarget = submitter.getAttribute('formtarget');
        submitter.setAttribute('formtarget', '_blank');
        try {
            // Deliberate side effect: this is the only path that preserves POST
            // data through the browser popup callback. The coordinate lookup is
            // exact (no rounding), and the attribute restore below is best-effort
            // if the page or renderer disappears during submission.
            if (typeof submitter.form.requestSubmit === 'function') {
                submitter.form.requestSubmit(submitter);
            } else {
                submitter.click();
            }
        } finally {
            window.setTimeout(() => {
                if (previousTarget === null) {
                    submitter.removeAttribute('formtarget');
                } else {
                    submitter.setAttribute('formtarget', previousTarget);
                }
            }, 0);
        }
        return 'submitted';
    })();
""".trimIndent()

internal fun middleClickUrlFromScriptResult(result: Any?): String? {
    val url = (result as? String)
        ?.takeIf { it.startsWith("link:") }
        ?.removePrefix("link:")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
    return url.takeIf { scheme == "http" || scheme == "https" }
}

/**
 * How a native popup should be handled while a middle-click target is being
 * resolved in the renderer.
 */
internal enum class MiddleClickPopupDisposition {
    FORWARD,
    BUFFERED,
    SUPPRESS
}

internal data class MiddleClickGestureStart(
    val token: Long,
    val stalePopupsToForward: List<PopupNavigation>
)

internal data class MiddleClickResolution(
    val accepted: Boolean,
    val popupsToForward: List<PopupNavigation> = emptyList(),
    val finishAfterReleaseToken: Long? = null
)

/**
 * Coordinates the Compose press path with Chromium's native popup callback.
 *
 * A native popup can race the renderer JavaScript round-trip. While resolution
 * is pending, popups are buffered instead of opened. A resolved HTTP(S) link
 * suppresses the buffered/native popup and opens the original URL directly;
 * a miss or form submission forwards the buffered popup so POST data and
 * Chromium-only targets are preserved.
 *
 * Suppression is scoped to the active physical middle-click gesture. Release
 * ends it after a short callback-delivery grace period, and any subsequent
 * pointer press cancels it immediately, so unrelated user popups are not
 * swallowed by a fixed-duration timer.
 */
internal class MiddleClickPopupCoordinator {
    private enum class Resolution {
        PENDING,
        DIRECT_LINK
    }

    private data class Gesture(
        val token: Long,
        var resolution: Resolution = Resolution.PENDING,
        var released: Boolean = false,
        val bufferedPopups: MutableList<PopupNavigation> = mutableListOf()
    )

    private val lock = Any()
    private var nextToken = 0L
    private var gesture: Gesture? = null

    fun begin(): MiddleClickGestureStart = synchronized(lock) {
        val stalePopups = gesture?.bufferedPopups?.toList().orEmpty()
        nextToken += 1
        gesture = Gesture(token = nextToken)
        MiddleClickGestureStart(
            token = nextToken,
            stalePopupsToForward = stalePopups
        )
    }

    fun onPopup(navigation: PopupNavigation): MiddleClickPopupDisposition =
        synchronized(lock) {
            val current = gesture ?: return@synchronized MiddleClickPopupDisposition.FORWARD
            when (current.resolution) {
                Resolution.PENDING -> {
                    current.bufferedPopups += navigation
                    MiddleClickPopupDisposition.BUFFERED
                }
                Resolution.DIRECT_LINK -> MiddleClickPopupDisposition.SUPPRESS
            }
        }

    fun complete(token: Long, directLinkResolved: Boolean): MiddleClickResolution =
        synchronized(lock) {
            val current = gesture
            if (current == null || current.token != token) {
                return@synchronized MiddleClickResolution(accepted = false)
            }

            if (!directLinkResolved) {
                gesture = null
                return@synchronized MiddleClickResolution(
                    accepted = true,
                    popupsToForward = current.bufferedPopups.toList()
                )
            }

            current.resolution = Resolution.DIRECT_LINK
            current.bufferedPopups.clear()
            MiddleClickResolution(
                accepted = true,
                finishAfterReleaseToken = current.token.takeIf { current.released }
            )
        }

    fun release(): Long? = synchronized(lock) {
        val current = gesture ?: return@synchronized null
        current.released = true
        current.token.takeIf { current.resolution == Resolution.DIRECT_LINK }
    }

    fun finish(token: Long) {
        synchronized(lock) {
            val current = gesture
            if (
                current?.token == token &&
                current.released &&
                current.resolution == Resolution.DIRECT_LINK
            ) {
                gesture = null
            }
        }
    }

    fun cancel(): List<PopupNavigation> = synchronized(lock) {
        val buffered = gesture?.bufferedPopups?.toList().orEmpty()
        gesture = null
        buffered
    }
}

private const val MIDDLE_CLICK_RESOLUTION_TIMEOUT_MS = 500L
private const val MIDDLE_CLICK_POPUP_RELEASE_GRACE_MS = 150L

private suspend fun resolveMiddleClickTarget(
    handle: BrowserHandle?,
    x: Float,
    y: Float
): Any? {
    handle ?: return null
    return try {
        withTimeoutOrNull(MIDDLE_CLICK_RESOLUTION_TIMEOUT_MS) {
            handle.executeJavaScript(middleClickTargetAtPointScript(x, y))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * Process URL input with smart detection for:
 * - Full URLs (http://, https://, file://, etc.)
 * - Localhost patterns (localhost:3000, 127.0.0.1:8080)
 * - Domain-like patterns (github.com, example.org)
 * - Search queries (anything else)
 */
private fun processUrlInput(input: String): String {
    val trimmed = input.trim()
    val lowerTrimmed = trimmed.lowercase()

    // If it's already a full URL or special scheme, return as-is
    if (lowerTrimmed.startsWith("http://") || lowerTrimmed.startsWith("https://") ||
        lowerTrimmed.startsWith("file://") || lowerTrimmed.startsWith("javascript:") ||
        lowerTrimmed.startsWith("chrome://")) {
        return trimmed
    }

    // Check if it looks like a URL (contains dots and no spaces)
    val looksLikeUrl = trimmed.contains(".") && !trimmed.contains(" ")

    // Check for common URL patterns
    val urlPattern = Regex("""^([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(/.*)?$""")
    val isLikelyUrl = looksLikeUrl || urlPattern.matches(trimmed)

    // Check for localhost patterns
    val isLocalhost = trimmed.startsWith("localhost") ||
                     trimmed.matches(Regex("""^127\.0\.0\.1(:\d+)?(/.*)?$""")) ||
                     trimmed.matches(Regex("""^localhost(:\d+)?(/.*)?$"""))

    return when {
        isLocalhost -> "http://$trimmed"
        isLikelyUrl -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${URLEncoder.encode(trimmed, "UTF-8")}"
    }
}

/**
 * The home (dashboard) state: a blank URL or about:blank renders the host
 * dashboard instead of web content. Home has no document title or favicon of
 * its own — Chromium reports a blank title and never fires FaviconChanged for
 * it — so the tab's identity must be asserted explicitly (see
 * applyHomeTabIdentity in [FluckBrowserTabContent]).
 */
internal const val HOME_TITLE = "Home"

internal fun isHomeUrl(url: String): Boolean = url.isBlank() || url == "about:blank"

/**
 * Main browser tab content with URL bar, toolbar, and browser view.
 * Shows Dashboard for about:blank pages and browser content otherwise.
 */
/**
 * Persistent state for [FluckBrowserTabContent]. Owned by the parent
 * [FluckBrowserTabComponent] so it survives tab switches — the host
 * removes the inactive tab's Composable from composition, which would
 * otherwise reset every `remember`-scoped field and dispose the
 * BrowserHandle.
 *
 * The browser's navigation/loading/title listeners are wired against
 * these fields once on first init; subsequent re-entries into
 * composition just read the same observable state and the UI re-syncs
 * without re-creating the browser.
 */
/**
 * Tab hibernation (memory saver) configuration. When [enabled], a browser tab that's been in the
 * background past [idleMs] disposes its live browser (freeing the Chromium process tree) and is
 * recreated from its current URL when shown again. Gated off by default; opt in with
 * BOSS_TAB_HIBERNATION=true so it can be dogfooded before it becomes the default.
 */
internal object TabHibernation {
    val enabled: Boolean =
        System.getenv("BOSS_TAB_HIBERNATION")?.trim()?.lowercase() in listOf("1", "true", "yes", "on")
    val idleMs: Long =
        System.getenv("BOSS_TAB_HIBERNATION_IDLE_MS")?.trim()?.toLongOrNull() ?: (10 * 60 * 1000L)

    // Memory-pressure-driven hibernation (roadmap Phase 3): when free system memory is scarce,
    // hibernate idle background tabs much sooner to give memory back while it's needed. Only ever
    // SHORTENS the wait for already-backgrounded tabs — the foreground tab never arms the timer
    // (see the DisposableEffect), so responsiveness is unaffected. Tunable, fails safe to idleMs.
    private val pressureIdleMs: Long =
        System.getenv("BOSS_TAB_HIBERNATION_PRESSURE_IDLE_MS")?.trim()?.toLongOrNull() ?: 60_000L
    private val pressureFreeFraction: Double =
        System.getenv("BOSS_TAB_HIBERNATION_PRESSURE_FRACTION")?.trim()?.toDoubleOrNull() ?: 0.15

    // Battery-aware (roadmap Phase 2). On battery, hibernate idle background tabs sooner to save
    // power. The AC/battery signal is detected in the host (PowerSource) and published to the
    // boss.power.onBattery system property — read here with no dependency on the host module.
    // Gated behind the same BOSS_BATTERY_AWARE opt-in the host uses; off by default.
    private val batteryAwareEnabled: Boolean =
        System.getenv("BOSS_BATTERY_AWARE")?.trim()?.lowercase() in listOf("1", "true", "yes", "on")
    private val batteryIdleMs: Long =
        System.getenv("BOSS_TAB_HIBERNATION_BATTERY_IDLE_MS")?.trim()?.toLongOrNull() ?: (2 * 60 * 1000L)

    private val osBean: com.sun.management.OperatingSystemMXBean? =
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            as? com.sun.management.OperatingSystemMXBean)

    /**
     * The idle delay to use right now. Starts at the normal [idleMs] and takes the shortest of any
     * applicable accelerant: the memory-pressure delay when free system memory is below
     * [pressureFreeFraction] of total, and the battery delay when running on battery (opt-in). Only
     * ever shortens — never exceeds [idleMs] — and fails safe to [idleMs] on any read error.
     */
    fun effectiveIdleMs(): Long {
        var delay = idleMs
        try {
            val os = osBean
            if (os != null) {
                val total = os.totalMemorySize
                val free = os.freeMemorySize
                if (total > 0 && free.toDouble() / total.toDouble() < pressureFreeFraction)
                    delay = minOf(delay, pressureIdleMs)
            }
        } catch (e: Throwable) {
            // keep current delay
        }
        if (batteryAwareEnabled && System.getProperty("boss.power.onBattery") == "true")
            delay = minOf(delay, batteryIdleMs)
        return delay
    }
}

/**
 * Browser creations run on their own never-cancelled scope: createBrowser() is a
 * blocking Chromium IPC call with no cancellation points, so cancelling its
 * coroutine can only orphan the browser it eventually returns (a cancelled
 * Deferred discards its result — the live browser and renderer process would
 * leak). Instead the creation always runs to completion, and whoever owns the
 * Deferred — the tab effect, or onDestroy for a closing tab — disposes the
 * result.
 */
private val browserCreationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

// Cached pool (not one raw Thread per call): threads are reused under bursts of
// tab closes and expire after 60s idle, so nothing lingers in the steady state.
//
// Lifetime note (applies to browserCreationScope above too): these are top-level
// and intentionally never shut down. Fluck Browser is a system plugin the host
// refuses to unload, so the classloader lives for the process; the pool holds no
// non-daemon threads and the scope holds no threads at all when idle. If the
// plugin ever becomes unloadable, wire cancellation/shutdown into
// FluckBrowserDynamicPlugin.dispose().
private val browserDisposeExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
    Thread(r, "fluck-browser-dispose").apply { isDaemon = true }
}

/** Dispose a handle off the UI thread — dispose() ends in a blocking Chromium IPC round-trip. */
internal fun disposeBrowserHandleOffThread(handle: BrowserHandle) {
    browserDisposeExecutor.execute {
        try {
            handle.dispose()
        } catch (t: Throwable) {
            println("[FluckBrowser] Browser dispose failed: ${t.message}")
        }
    }
}

/**
 * Abandon a pending browser creation nobody will consume: whenever it completes
 * (immediately, if it already has), dispose whatever browser it produced so the
 * handle can't leak. Used by tab close and by an explicit Retry on a wedged boot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun abandonBrowserCreation(pending: Deferred<BrowserHandle?>) {
    pending.invokeOnCompletion {
        val orphan = runCatching { pending.getCompleted() }.getOrNull()
        if (orphan != null) disposeBrowserHandleOffThread(orphan)
    }
}

/** The result of an already-completed creation, or null if it completed exceptionally. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun completedBrowserOrNull(creation: Deferred<BrowserHandle?>): BrowserHandle? =
    runCatching { creation.getCompleted() }.getOrNull()

internal class FluckBrowserTabState {
    var browserHandle: BrowserHandle? by mutableStateOf<BrowserHandle?>(null)
    // In-flight (or completed-but-unconsumed) browser creation. Runs on
    // [browserCreationScope] so a tab switch mid-boot neither cancels it nor
    // spawns a duplicate when the tab re-enters composition.
    //
    // Thread confinement: this var is read/written only from the main thread —
    // the init effect (composition), onRetry (a click handler), and onDestroy
    // (Essenty lifecycle callbacks fire on the main thread). Only the Deferred
    // OBJECT crosses threads (invokeOnCompletion is thread-safe), never this
    // field, so no @Volatile is needed.
    var browserCreation: Deferred<BrowserHandle?>? = null
    // Restart key for the init effect: bumped by Retry (retryCount = 0 is a
    // no-op key when the first attempt failed at 0) and by the late-adoption
    // nudge. Hoisted — NOT remember-scoped — so the single state instance
    // survives tab switches and one nudge registration per deferred suffices.
    var initNonce: Int by mutableStateOf(0)
    // One-shot guard: the deferred that already has a late-adoption nudge
    // registered, so repeated timeout passes don't pile callbacks onto it.
    var lateAdoptNudged: Deferred<BrowserHandle?>? = null
    // Pending idle-hibernation timer (armed when the tab is backgrounded, cancelled if it returns).
    var hibernationJob: kotlinx.coroutines.Job? = null
    var isInitializing: Boolean by mutableStateOf(true)
    var isLoading: Boolean by mutableStateOf(false)
    var urlBarText: TextFieldValue by mutableStateOf(TextFieldValue(""))
    var pageTitle: String by mutableStateOf("New Tab")
    var zoomLevel: Double by mutableStateOf(1.0)
    var error: String? by mutableStateOf("Initializing browser...")
    var canGoBack: Boolean by mutableStateOf(false)
    var canGoForward: Boolean by mutableStateOf(false)
    var isBookmarked: Boolean by mutableStateOf(false)
    var navigationHistory: MutableList<Pair<String, String>> by mutableStateOf(mutableListOf())
    var historyIndex: Int by mutableStateOf(-1)
}

@Composable
internal fun FluckBrowserTabContent(
    initialUrl: String,
    browserService: BrowserService,
    coroutineScope: CoroutineScope,
    /**
     * Persistent state owned by the parent [FluckBrowserTabComponent].
     * Survives tab switches so the JxBrowser instance and all UI state are
     * reused instead of re-created on every re-entry into composition.
     */
    hoistedState: FluckBrowserTabState = remember { FluckBrowserTabState() },
    tabUpdateProviderFactory: TabUpdateProviderFactory? = null,
    tabId: String = "",
    tabTypeId: TabTypeId = TabTypeId("", ""),
    dashboardContentProvider: DashboardContentProvider? = null,
    secretDataProvider: SecretDataProvider? = null,
    bookmarkDataProvider: BookmarkDataProvider? = null,
    activeTabsProvider: ActiveTabsProvider? = null,
    zoomSettingsProvider: ZoomSettingsProvider? = null,
    urlHistoryProvider: UrlHistoryProvider? = null,
    screenCaptureProvider: ScreenCaptureProvider? = null,
    onOpenInNewTab: (String) -> Unit = {},
    onCloseTab: () -> Unit = {}
) {
    // Browser state — all hoisted into the parent Component so it survives
    // tab switches. The JxBrowser instance, the listeners attached to it,
    // and the observable state they write into all share the same lifetime
    // (tab open → tab close), independent of how many times this Composable
    // enters and leaves composition.
    //
    // First-time init: when the Component is freshly constructed,
    // hoistedState.urlBarText is empty; we seed it from initialUrl.
    LaunchedEffect(Unit) {
        if (hoistedState.urlBarText.text.isEmpty()) {
            hoistedState.urlBarText = TextFieldValue(initialUrl, TextRange(initialUrl.length))
        }
    }
    // Property-reference delegation: `var x by ::y` compiles to direct
    // get/set on hoistedState, so the local names below behave the same
    // as before but their backing storage lives on the Component.
    var browserHandle by hoistedState::browserHandle
    var isInitializing by hoistedState::isInitializing
    var isLoading by hoistedState::isLoading
    var urlBarText by hoistedState::urlBarText
    var pageTitle by hoistedState::pageTitle
    var zoomLevel by hoistedState::zoomLevel
    var error by hoistedState::error
    var canGoBack by hoistedState::canGoBack
    var canGoForward by hoistedState::canGoForward
    var isBookmarked by hoistedState::isBookmarked
    var navigationHistory by hoistedState::navigationHistory
    var historyIndex by hoistedState::historyIndex

    // Local-only state (not shared across composition) — derived/transient,
    // doesn't need to survive tab switches.
    var isUserEditingUrl by remember { mutableStateOf(false) }
    var lastUserEditTime by remember { mutableStateOf(0L) }
    val middleClickPopupCoordinator = remember { MiddleClickPopupCoordinator() }
    val handlePopupNavigation: (PopupNavigation) -> Unit = { navigation ->
        val body = navigation.postData
        val contentType = navigation.contentType
        if (body != null && contentType != null) {
            browserService.stashPopupPost(navigation.url, body, contentType)
        }
        onOpenInNewTab(navigation.url)
    }
    val finishMiddleClickAfterRelease: (Long) -> Unit = { token ->
        coroutineScope.launch {
            delay(MIDDLE_CLICK_POPUP_RELEASE_GRACE_MS)
            middleClickPopupCoordinator.finish(token)
        }
    }
    val completeMiddleClick: (Long, String?) -> Unit = { token, target ->
        val resolution = middleClickPopupCoordinator.complete(
            token = token,
            directLinkResolved = target != null
        )
        resolution.popupsToForward.forEach(handlePopupNavigation)
        if (resolution.accepted && target != null) {
            onOpenInNewTab(target)
        }
        resolution.finishAfterReleaseToken?.let(finishMiddleClickAfterRelease)
    }

    // Co-browse share dialog. The links arrive reactively (the Cloudflare tunnel URL
    // resolves a few seconds after Share), so observe the manager's shareInfo flow.
    var shareDialogOpen by remember { mutableStateOf(false) }
    var shareMaskInputs by remember { mutableStateOf(false) }
    var thisTabInitiatedShare by remember { mutableStateOf(false) }
    val liveShareInfo by BrowserShareManager.shareInfo.collectAsState()
    if (shareDialogOpen) {
        // The dialog collects shareInfo/viewerCount itself (inside its Window) so its
        // own composition subscribes — a separate Window does NOT reliably recompose
        // on the parent's collectAsState, which broke the live link/QR hot-reload
        // when the Cloudflare tunnel resolves.
        ShareLinkDialog(
            maskInputs = shareMaskInputs,
            onToggleMask = { checked ->
                shareMaskInputs = checked
                BrowserShareManager.share(tabId, checked)
            },
            onRefreshLink = { BrowserShareManager.refreshLink() },
            onStopSharing = {
                BrowserShareManager.unshare()
                shareDialogOpen = false
                thisTabInitiatedShare = false
            },
            onDismiss = { shareDialogOpen = false },
        )
    }

    // Co-browse approval prompts: rendered as non-modal floating banners in the
    // tab's top-right overlay (see the root Box below), BossTerm-style. Gated to the
    // initiating tab so multiple open browser tabs don't stack duplicate banners; the
    // host notificationProvider toast remains the fallback when no tab is visible.
    val pendingApprovals by BrowserShareManager.pendingRequests.collectAsState()

    // URL history autocomplete state
    var showUrlSuggestions by remember { mutableStateOf(false) }
    var urlSuggestions by remember { mutableStateOf<List<UrlHistoryEntry>>(emptyList()) }
    var autocompleteSuggestion by remember { mutableStateOf<String?>(null) }
    var selectedDropdownIndex by remember { mutableStateOf(-1) }
    val dropdownListState = rememberLazyListState()

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuInfo by remember { mutableStateOf<BrowserContextMenuInfo?>(null) }

    // Cached secrets for context menu (loaded when context menu is shown)
    var cachedSecrets by remember { mutableStateOf<List<SecretEntryData>>(emptyList()) }

    // Secret dialog state
    var showAllSecretsDialog by remember { mutableStateOf(false) }
    var showQuickCreateDialog by remember { mutableStateOf(false) }
    var quickCreateWebsitePrefill by remember { mutableStateOf("") }
    var allSecrets by remember { mutableStateOf<List<SecretEntryData>>(emptyList()) }

    // Fullscreen state - tracks when browser content is displayed in a fullscreen window
    var isInFullscreen by remember { mutableStateOf(false) }

    // Retry state for browser creation. retryCount drives the auto-retry backoff;
    // initNonce (hoisted, see FluckBrowserTabState) guarantees the init effect
    // re-runs on every explicit Retry and on a late-completing boot.
    var retryCount by remember { mutableStateOf(0) }
    var initNonce by hoistedState::initNonce
    val maxRetries = 3

    // Recovery state - prevents infinite recovery loops
    var recoveryAttempts by remember { mutableStateOf(0) }
    val maxRecoveryAttempts = 5

    // navigationHistory / historyIndex live on hoistedState (declared above).

    // Show dashboard for about:blank pages - matches bundled browser exactly
    val currentUrl = urlBarText.text
    val showDashboard = isHomeUrl(currentUrl)

    // Security indicator derived from current URL
    val isSecure = currentUrl.startsWith("https://")

    // Lazily created provider - by the time LaunchedEffect runs, the tab should be registered
    var tabUpdateProvider by remember { mutableStateOf<TabUpdateProvider?>(null) }

    // Initialize browser with retry mechanism
    LaunchedEffect(retryCount, initNonce) {
        if (browserHandle != null) return@LaunchedEffect

        try {
            // Create the TabUpdateProvider now (tab should be registered by this point)
            if (tabUpdateProvider == null) {
                tabUpdateProvider = tabUpdateProviderFactory?.createProvider(tabId, tabTypeId)
            }

            // Apply exponential backoff delay for retries. The shift is capped:
            // crash-recovery reuses this counter and could otherwise drive the
            // shift toward overflow.
            if (retryCount > 0) {
                val delayMs = 100L * (1 shl (retryCount - 1).coerceAtMost(6)) // 100ms..6.4s
                delay(delayMs)
            }

            // Create the browser OFF the main dispatcher: on a cold start this call
            // boots the whole Chromium engine, and even warm it spawns a renderer
            // process — blocking work that used to run inside this LaunchedEffect on
            // Dispatchers.Main and freeze the entire app UI (which also meant the old
            // 3s timeout could never fire: the timer ran on the very thread that was
            // blocked). The deferred runs on the never-cancelled browserCreationScope
            // and is stashed in hoisted state, so a mid-boot tab switch neither
            // cancels the creation nor starts a duplicate when the tab comes back —
            // re-entry awaits the same in-flight (or completed-but-unconsumed)
            // creation. The timeout is generous because a slow boot now only delays
            // this tab's spinner, not the app.
            //
            // Fresh creations start at the CURRENT url (preserved in hoisted state),
            // so a hibernation wake or crash-recovery returns to the page the user
            // was on, not the tab's first URL. A REUSED deferred deliberately keeps
            // the URL captured when it was launched: it exists only while a boot is
            // already in flight, and the browser it produces reports its real
            // location through the navigation listener anyway.
            val creation = hoistedState.browserCreation
                ?: browserCreationScope.async {
                    // urlBarText is Compose state read here on an IO thread — safe
                    // (snapshot reads are thread-consistent), and deliberately
                    // snapshotted at launch time per the comment above.
                    browserService.createBrowser(
                        BrowserConfig(url = urlBarText.text.ifBlank { initialUrl })
                    )
                }.also { hoistedState.browserCreation = it }
            var handle = withTimeoutOrNull(20_000L) { creation.await() }
            // Snapshot completion ONCE. The deferred completes on an IO thread, so
            // re-reading isCompleted later races the boot finishing right after the
            // watchdog fired: a stale read could clear the deferred while a live
            // browser sits inside it — a permanent renderer leak plus a wrong
            // "failed to create" error. With a single snapshot: completed==true
            // recovers the late result below; completed==false keeps the deferred
            // cached, where re-entry, Retry, or onDestroy all own it safely.
            val completed = creation.isCompleted
            if (handle == null && completed) {
                handle = completedBrowserOrNull(creation)
            }
            if (completed) hoistedState.browserCreation = null
            if (handle != null) {
                browserHandle = handle
                isInitializing = false

                // Register this tab+handle so the co-browse share server can enumerate
                // and stream it. Re-registers under the same tabId on a recovery re-init.
                if (tabId.isNotEmpty()) BrowserShareManager.registerTab(tabId, handle)

                // Home (the dashboard) has no document title or favicon, so no
                // TitleChanged/FaviconChanged follows a navigation to it — set the
                // tab's own identity and drop the previous page's stale favicon.
                fun applyHomeTabIdentity() {
                    pageTitle = HOME_TITLE
                    tabUpdateProvider?.updateTitle(HOME_TITLE)
                    tabUpdateProvider?.updateFavicon(null)
                }

                // A tab that opens directly on home (e.g. a restored dashboard tab)
                // may never fire navigation events for about:blank — apply up front.
                if (isHomeUrl(urlBarText.text)) applyHomeTabIdentity()

                // Add listeners - matches bundled browser exactly
                handle.addNavigationListener { url ->
                    // Only update URL bar if user isn't actively editing
                    // AND sufficient time has passed since last input (300ms buffer for Tab completion)
                    val timeSinceEdit = System.currentTimeMillis() - lastUserEditTime
                    if (!isUserEditingUrl && timeSinceEdit > 300) {
                        urlBarText = TextFieldValue(url, TextRange(url.length))
                    }

                    canGoBack = handle.canGoBack()
                    canGoForward = handle.canGoForward()

                    // Back/forward can land on home (about:blank) — apply the home
                    // identity BEFORE history tracking below, so the history entry
                    // records "Home" (not the previous page's title) and the tab
                    // never keeps a blank title + the last page's favicon.
                    if (isHomeUrl(url)) applyHomeTabIdentity()

                    // Track navigation history for workspace persistence
                    // Only add new entry if URL is different from current position
                    if (navigationHistory.isEmpty() || navigationHistory.lastOrNull()?.second != url) {
                        // Truncate forward history if we're not at the end
                        if (historyIndex < navigationHistory.size - 1) {
                            while (navigationHistory.size > historyIndex + 1) {
                                navigationHistory.removeAt(navigationHistory.size - 1)
                            }
                        }
                        navigationHistory.add(Pair(pageTitle, url))
                        historyIndex = navigationHistory.size - 1
                    }

                    // Update the tab's URL in the host (for bookmark/workspace persistence)
                    tabUpdateProvider?.updateUrl(url)

                    // Load saved zoom level for this domain (zoom persistence feature).
                    // Zoom is scoped per browser (host sets ZoomMode.PER_BROWSER), so a
                    // tab keeps its current level across navigations. A domain with no
                    // saved zoom must therefore reset to the default level — otherwise
                    // the previous domain's zoom carries over to the new site.
                    zoomSettingsProvider?.let { provider ->
                        val domain = provider.extractDomain(url)
                        if (domain != null) {
                            val targetZoom = provider.getZoomForDomain(domain) ?: 1.0
                            if (abs(targetZoom - zoomLevel) > 0.001) {
                                zoomLevel = targetZoom
                                handle.setZoomLevel(targetZoom)
                            }
                        }
                    }

                    // Check if URL is bookmarked
                    bookmarkDataProvider?.let { provider ->
                        val tabConfig = ai.rever.boss.plugin.workspace.TabConfig(
                            type = "browser",
                            title = pageTitle,
                            url = url
                        )
                        isBookmarked = provider.isTabBookmarked(tabConfig)
                    }
                }
                handle.addTitleListener { title ->
                    // Home (about:blank) reports a blank or literal "about:blank"
                    // title — never let it blank the tab chip; the navigation
                    // listener already applied the home identity.
                    if (title.isBlank() || title == "about:blank") return@addTitleListener

                    pageTitle = title

                    // Update the tab's title in the tab bar via the host
                    tabUpdateProvider?.updateTitle(title)

                    // Add URL to history with title (URL history feature).
                    //
                    // Record the URL the browser actually committed, not the URL bar text:
                    // the bar holds whatever the user typed until the navigation listener
                    // catches up (and it deliberately doesn't while they're still editing),
                    // so using it filed history entries under half-typed text. The host
                    // decides whether the navigation really loaded a page before keeping
                    // the entry — a mistyped host still fires this callback for its error
                    // page.
                    val committedUrl = handle.getCurrentUrl()
                    if (!isHomeUrl(committedUrl)) {
                        urlHistoryProvider?.addUrl(committedUrl, title)
                    }
                }
                handle.addLoadingListener { loading ->
                    isLoading = loading

                    // Page finished loading — browser is working, clear error/loading state
                    if (!loading) {
                        error = null
                        isInitializing = false
                    }

                    // Save history when page finishes loading (home has no history entry)
                    val currentUrlText = urlBarText.text
                    if (!loading && !isHomeUrl(currentUrlText)) {
                        coroutineScope.launch {
                            urlHistoryProvider?.saveHistory()
                        }
                    }
                }

                // Also update favicon when available
                handle.addFaviconListener { faviconUrl ->
                    tabUpdateProvider?.updateFavicon(faviconUrl)
                }

                // Listen for zoom changes (e.g., from pinch-to-zoom gestures)
                handle.addZoomListener { newZoom ->
                    zoomLevel = newZoom

                    // Save zoom level for this domain (zoom persistence feature)
                    zoomSettingsProvider?.let { provider ->
                        val domain = provider.extractDomain(urlBarText.text)
                        if (domain != null) {
                            provider.setZoomForDomain(domain, newZoom)
                            coroutineScope.launch {
                                provider.saveSettings()
                            }
                        }
                    }
                }

                // Set up context menu callback
                handle.setContextMenuCallback { info ->
                    contextMenuInfo = info
                    showContextMenu = true
                }

                // Route modifier-click, middle-click, and target="_blank" popup navigations to a BOSS tab.
                // Use the data-aware variant so form-submit popups (e.g. OncoEMR print)
                // preserve their POST body across the handoff — without this, the
                // destination server sees a GET and can't reconstruct the print job.
                handle.setOpenInNewTabWithDataCallback { nav ->
                    when (middleClickPopupCoordinator.onPopup(nav)) {
                        MiddleClickPopupDisposition.FORWARD -> handlePopupNavigation(nav)
                        MiddleClickPopupDisposition.BUFFERED,
                        MiddleClickPopupDisposition.SUPPRESS -> Unit
                    }
                }

                // Set up fullscreen handler for video fullscreen (e.g., YouTube)
                if (tabId.isNotEmpty()) {
                    handle.setFullscreenHandler(
                        tabId = tabId,
                        onEnterFullscreen = {
                            isInFullscreen = true
                        },
                        onExitFullscreen = {
                            isInFullscreen = false
                        }
                    )
                }

                // Initialize zoom level from browser
                zoomLevel = handle.getZoomLevel()
            } else if (completed) {
                // createBrowser() returned null — the engine reported failure.
                // (An exceptionally-completed deferred normally rethrows from
                // await() into the catch below; it reaches here only via the
                // late-completion recovery window, where completedBrowserOrNull
                // maps the failure to null. The deferred was dropped above either
                // way, so Retry starts fresh.)
                error = "Failed to create browser instance. The browser engine may not be available."
                isInitializing = false
            } else {
                // The 20s watchdog fired while the boot is still wedged in flight.
                // (withTimeoutOrNull returns null rather than throwing, so this is
                // the timeout path — there is no TimeoutCancellationException to
                // catch.) The in-flight deferred stays cached so a tab-switch
                // re-entry keeps waiting on the same boot; an explicit Retry
                // abandons it (see onRetry) and starts fresh.
                println("[FluckBrowser] Browser creation timed out after 20s")
                error = "Browser initialization timed out. The browser engine may not be available in this environment."
                isInitializing = false
                // If the wedged boot completes AFTER the error is shown while the
                // tab just sits there, nudge the init effect to re-run: it awaits
                // the completed deferred instantly and adopts the browser in place.
                // Without this, a late success idles unconsumed until Retry/close —
                // and Retry would dispose it and boot a second renderer for
                // nothing. Snapshot-state writes are thread-safe, so bumping the
                // nonce from the completer's IO thread is fine; a stale bump
                // (already-adopted, tab closed) hits the browserHandle != null
                // early-return or a dead composition and is harmless. One nudge
                // per deferred: initNonce is hoisted (survives tab switches), so
                // repeated timeout passes don't need to re-register.
                if (hoistedState.lateAdoptNudged !== creation) {
                    hoistedState.lateAdoptNudged = creation
                    creation.invokeOnCompletion { hoistedState.initNonce++ }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The effect itself was cancelled (tab switch / tab close mid-boot).
            // Do NOT clear browserCreation: re-entry must reuse the in-flight
            // creation (or onDestroy adopts it), otherwise we'd boot a duplicate
            // and leak the first browser.
            throw e
        } catch (e: Exception) {
            // The creation completed exceptionally. Drop it — a failed deferred
            // must never be reused, or every auto-retry (and the Retry button)
            // would just re-throw this same cached failure instead of calling
            // createBrowser() again.
            hoistedState.browserCreation = null
            if (retryCount < maxRetries) {
                retryCount++
            } else {
                error = e.message ?: "Unknown error"
                isInitializing = false
            }
        }
    }

    // Browser validity check and recovery mechanism
    // Poll browser validity to detect engine resets and trigger recovery
    LaunchedEffect(browserHandle) {
        if (browserHandle != null) {
            while (true) {
                delay(500) // Check every 500ms for fast recovery

                val handle = browserHandle
                if (handle != null && !handle.isValid) {
                    // Browser became invalid - trigger recovery
                    if (recoveryAttempts < maxRecoveryAttempts) {
                        recoveryAttempts++
                        println("[FluckBrowser] Browser invalid, triggering recovery (attempt $recoveryAttempts/$maxRecoveryAttempts)")

                        // Save current URL for recovery
                        val currentUrl = urlBarText.text

                        // Reset state to trigger reinitialization. Dispose the
                        // invalid handle too — even a crashed/stale handle still
                        // holds listener registrations and view state worth
                        // releasing, and dispose() is safe on invalid handles.
                        browserHandle = null
                        disposeBrowserHandleOffThread(handle)
                        isInitializing = true
                        error = "Browser crashed. Recovering..."

                        // Restore URL after small delay (home needs no restore)
                        delay(100)
                        if (!isHomeUrl(currentUrl)) {
                            urlBarText = TextFieldValue(currentUrl, TextRange(currentUrl.length))
                        }

                        // Increment retry count to trigger LaunchedEffect
                        retryCount++
                    } else {
                        // Max recovery attempts reached
                        error = "Browser recovery failed after $maxRecoveryAttempts attempts. Please close and reopen this tab."
                        browserHandle = null
                        disposeBrowserHandleOffThread(handle)
                        isInitializing = false
                    }
                    break
                }
            }
        }
    }

    // Reset recovery counter on successful browser initialization
    LaunchedEffect(browserHandle) {
        if (browserHandle != null && browserHandle?.isValid == true) {
            if (recoveryAttempts > 0) {
                println("[FluckBrowser] Browser recovered successfully, resetting recovery counter")
                recoveryAttempts = 0
            }
        }
    }

    // Load all secrets for dialogs
    LaunchedEffect(secretDataProvider) {
        if (secretDataProvider != null) {
            try {
                val result = secretDataProvider.getUserSecrets(limit = 1000)
                allSecrets = result.getOrNull()?.data ?: emptyList()
            } catch (e: Exception) {
                // Silently fail
            }
        }
    }

    // No *immediate* DisposableEffect-disposal of the BrowserHandle here. Disposing it on every
    // composition exit would kill the JxBrowser instance on every tab switch, forcing a full
    // reload on the next switch back. The handle is owned by the parent Component and disposed in
    // its lifecycle.onDestroy callback (i.e. only on tab close).
    //
    // Tab hibernation (memory saver), gated off by default. When a tab is backgrounded (this
    // Composable leaves composition on a tab switch), arm an idle timer on the Component's
    // surviving coroutineScope; if the tab is still in the background when it fires, dispose the
    // live browser to free its Chromium process tree. The hoisted state (current URL, title,
    // history) survives, so returning to the tab recreates the browser at the current URL via the
    // create effect above (which runs on composition re-entry whenever browserHandle == null).
    // Showing the tab again before the timer fires cancels it — no reload, no cost.
    DisposableEffect(Unit) {
        hoistedState.hibernationJob?.cancel()
        hoistedState.hibernationJob = null
        onDispose {
            if (TabHibernation.enabled && browserHandle != null) {
                hoistedState.hibernationJob = coroutineScope.launch {
                    delay(TabHibernation.effectiveIdleMs())
                    val handle = browserHandle
                    browserHandle = null
                    isInitializing = true
                    // Off the UI thread so hibernating a background tab can't hitch
                    // the foreground UI.
                    if (handle != null) disposeBrowserHandleOffThread(handle)
                }
            }
        }
    }
    // Keep the URL-bar star in sync with external collection edits — e.g. when the
    // user removes a bookmark from the bookmarks panel, isBookmarked must reflect that.
    val bookmarkCollections = bookmarkDataProvider?.collections?.collectAsState(initial = emptyList())?.value
    LaunchedEffect(bookmarkCollections, currentUrl, pageTitle, bookmarkDataProvider) {
        bookmarkDataProvider?.let { provider ->
            val tabConfig = ai.rever.boss.plugin.workspace.TabConfig(
                type = "browser",
                title = pageTitle,
                url = currentUrl
            )
            isBookmarked = provider.isTabBookmarked(tabConfig)
        }
    }

    // No DisposableEffect for the BrowserHandle here. Disposing it on
    // composition exit would kill the JxBrowser instance every time the
    // host removes the inactive tab from the composition (i.e. on every
    // tab switch), forcing a full reload on the next switch back. The
    // handle is owned by the parent Component and disposed in its
    // lifecycle.onDestroy callback (i.e. only on tab close).

    // Forget a suggestion instead of navigating to it — for the entries you never want
    // offered again (a typo that once loaded an error page, a URL you'd rather not have
    // surface). Reached from the ✕ on a dropdown row and from shift+Delete on the
    // highlighted one; both land here so they behave the same. Declared outside the
    // layout because the URL bar and the dropdown that floats over it are siblings.
    //
    // The inline ghost text is dropped rather than recomputed: it previews the first
    // suggestion, and leaving it pointing at an entry that no longer exists would
    // autocomplete the URL we were just asked to forget. The next keystroke rebuilds it
    // from what's left.
    val onDeleteSuggestion: (UrlHistoryEntry) -> Unit = { entry ->
        urlHistoryProvider?.deleteUrl(entry.url)
        val remaining = urlSuggestions.filterNot { it.url == entry.url }
        urlSuggestions = remaining
        showUrlSuggestions = remaining.isNotEmpty()
        selectedDropdownIndex = selectedDropdownIndex.coerceAtMost(remaining.lastIndex)
        autocompleteSuggestion = null
        coroutineScope.launch {
            urlHistoryProvider?.saveHistory()
        }
    }

    BossTheme {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .onPreviewKeyEvent { keyEvent ->
                    // Handle keyboard shortcuts: Cmd+R (reload), Cmd+0 (reset zoom),
                    // Cmd++/= (zoom in), Cmd+- (zoom out)
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isPrimaryModifierPressed()) {
                        when (keyEvent.key) {
                            Key.R -> {
                                // Reload - Cmd+R / Ctrl+R
                                browserHandle?.reload()
                                true
                            }
                            Key.Zero -> {
                                // Reset zoom - Cmd+0 / Ctrl+0
                                browserHandle?.resetZoom()
                                true
                            }
                            Key.Equals, Key.NumPadAdd -> {
                                // Zoom in - Cmd++ or Cmd+= / Ctrl++ or Ctrl+=
                                browserHandle?.zoomIn()
                                true
                            }
                            Key.Minus, Key.NumPadSubtract -> {
                                // Zoom out - Cmd+- / Ctrl+-
                                browserHandle?.zoomOut()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
        ) {
        // URL bar with navigation controls.
        // The co-browse share (QR) button is opt-in: hidden unless enabled in
        // Settings > Browser > Tab Sharing. The host mirrors that toggle to this JVM
        // system property (read inline so a recompose after toggling reflects it).
        val shareButtonEnabled = System.getProperty("boss.fluck.showShareButton") == "true"
        BrowserToolbar(
            onShare = if (shareButtonEnabled) {
                {
                    BrowserShareManager.share(tabId, shareMaskInputs)
                    shareDialogOpen = true
                    thisTabInitiatedShare = true
                }
            } else null,
            isSharing = liveShareInfo != null,
            urlBarText = urlBarText,
            onUrlBarTextChange = { newValue ->
                isUserEditingUrl = true
                lastUserEditTime = System.currentTimeMillis()
                urlBarText = newValue
                selectedDropdownIndex = -1

                // Get autocomplete suggestion and dropdown items
                // Only compute when text is not empty and cursor is not selecting text
                if (newValue.text.isNotEmpty() && newValue.selection.collapsed && urlHistoryProvider != null) {
                    val suggestions = urlHistoryProvider.getSuggestions(newValue.text, limit = 10)

                    // Set inline autocomplete (first suggestion with protocol stripped)
                    if (suggestions.isNotEmpty()) {
                        val suggestion = suggestions.first()
                        val suggestionUrl = suggestion.url
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .removePrefix("www.")

                        // Only suggest if the stripped URL starts with the input
                        if (suggestionUrl.lowercase().startsWith(newValue.text.lowercase()) &&
                            suggestionUrl.length > newValue.text.length) {
                            autocompleteSuggestion = suggestionUrl
                        } else {
                            autocompleteSuggestion = null
                        }
                    } else {
                        autocompleteSuggestion = null
                    }

                    // Set dropdown suggestions
                    urlSuggestions = suggestions
                    showUrlSuggestions = suggestions.isNotEmpty()
                } else {
                    autocompleteSuggestion = null
                    urlSuggestions = emptyList()
                    showUrlSuggestions = false
                }
            },
            onNavigate = { url ->
                // Reflect the resolved URL in the bar BEFORE kicking off the load so
                // the user sees the autocomplete commit immediately instead of waiting
                // for the browser's NavigationStarted event to round-trip.
                urlBarText = TextFieldValue(url, TextRange(url.length))
                // Clear editing state to allow URL bar updates during navigation
                isUserEditingUrl = false
                lastUserEditTime = 0L
                showUrlSuggestions = false
                autocompleteSuggestion = null
                selectedDropdownIndex = -1
                coroutineScope.launch {
                    browserHandle?.loadUrl(url)
                }
            },
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onBack = { browserHandle?.goBack() },
            onForward = { browserHandle?.goForward() },
            onReload = { browserHandle?.reload() },
            onStop = { browserHandle?.stop() },
            isLoading = isLoading,
            isSecure = isSecure,
            zoomLevel = zoomLevel,
            onZoomChange = { level ->
                zoomLevel = level
                browserHandle?.setZoomLevel(level)
            },
            isBookmarked = isBookmarked,
            onBookmarkClick = {
                // Add or remove bookmark using the host API
                bookmarkDataProvider?.let { provider ->
                    val tabConfig = TabConfig(
                        type = "browser",
                        title = pageTitle,
                        url = currentUrl
                    )
                    if (isBookmarked) {
                        // Remove bookmark
                        val bookmarkInfo = provider.findBookmarkForTab(tabConfig)
                        if (bookmarkInfo != null) {
                            val (collectionId, bookmarkId) = bookmarkInfo
                            provider.removeBookmark(collectionId, bookmarkId)
                        }
                        isBookmarked = false
                    } else {
                        // Add bookmark to the favorites collection (by isFavorite flag, not literal name).
                        // Falls back to the first available collection so the save never silently no-ops
                        // when the user has renamed/removed "Favorites".
                        val target = provider.collections.value.firstOrNull { it.isFavorite }
                            ?: provider.collections.value.firstOrNull()
                        if (target != null) {
                            val bookmark = Bookmark(
                                tabConfig = tabConfig,
                                workspaceName = "Default"
                            )
                            provider.addBookmark(target.name, bookmark)
                            isBookmarked = true
                        }
                    }
                } ?: run {
                    println("📚 BOOKMARK: provider is null, fallback toggle")
                    // Fallback to simple toggle if provider not available
                    isBookmarked = !isBookmarked
                }
            },
            urlSuggestions = urlSuggestions,
            showUrlSuggestions = showUrlSuggestions,
            autocompleteSuggestion = autocompleteSuggestion,
            selectedDropdownIndex = selectedDropdownIndex,
            dropdownListState = dropdownListState,
            onSuggestionSelected = { suggestion ->
                urlBarText = TextFieldValue(suggestion.url, TextRange(suggestion.url.length))
                isUserEditingUrl = false
                lastUserEditTime = 0L
                showUrlSuggestions = false
                autocompleteSuggestion = null
                selectedDropdownIndex = -1
                coroutineScope.launch {
                    browserHandle?.loadUrl(suggestion.url)
                }
            },
            onDismissSuggestions = {
                showUrlSuggestions = false
                autocompleteSuggestion = null
                selectedDropdownIndex = -1
            },
            onAcceptAutocomplete = {
                if (autocompleteSuggestion != null) {
                    urlBarText = TextFieldValue(autocompleteSuggestion!!, TextRange(autocompleteSuggestion!!.length))
                    autocompleteSuggestion = null
                }
            },
            onSelectedDropdownIndexChange = { newIndex ->
                selectedDropdownIndex = newIndex
            },
            onSuggestionDeleted = onDeleteSuggestion,
            onFocusLost = {
                // Hide dropdown when focus is lost (with delay to allow click events)
                coroutineScope.launch {
                    delay(200)
                    showUrlSuggestions = false
                    isUserEditingUrl = false
                }
            }
        )

        // Browser content or Dashboard.
        // weight(1f) (instead of fillMaxSize) reserves exactly the height REMAINING below the
        // BrowserToolbar. In HARDWARE_ACCELERATED mode the browser is a heavyweight native
        // surface whose on-screen bounds track this composable; a fillMaxSize child can be
        // measured against the Column's full height and let that surface extend up over the
        // lightweight URL bar (the Windows overlap). Weighting bounds it to the area under the bar.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                error != null -> {
                    BrowserErrorContent(
                        error = error!!,
                        isLoading = isInitializing,
                        onRetry = {
                            error = "Initializing browser..."
                            // An explicit Retry abandons any cached creation — for a
                            // wedged (timed-out) boot, re-awaiting it would just time
                            // out forever. Its eventual result gets adopted and
                            // disposed, then a fresh boot starts.
                            hoistedState.browserCreation?.let { pending ->
                                hoistedState.browserCreation = null
                                abandonBrowserCreation(pending)
                            }
                            retryCount = 0
                            initNonce++
                            isInitializing = true
                        },
                        onResetTab = {
                            // Open current URL in a new tab, then close this one
                            val currentUrl = urlBarText.text.ifBlank { initialUrl }
                            onOpenInNewTab(currentUrl)
                            onCloseTab()
                        }
                    )
                }
                isInFullscreen -> {
                    // Fullscreen placeholder - browser is displayed in a separate fullscreen window
                    FullscreenPlaceholder(
                        onExitClick = {
                            // Request exit through the browser's fullscreen API
                            browserHandle?.requestExitFullscreen()
                        }
                    )
                }
                showDashboard && dashboardContentProvider != null -> {
                    // Show host's dashboard for about:blank pages
                    dashboardContentProvider.DashboardContent(
                        onNavigate = { url ->
                            coroutineScope.launch {
                                browserHandle?.loadUrl(url)
                            }
                        }
                    )
                }
                browserHandle != null -> {
                    // Resolve middle-click targets on press, before page-level auxclick
                    // handlers can rewrite hrefs to telemetry endpoints.
                    // Back/forward auxiliary buttons remain owned by the overlay as well.
                    @OptIn(ExperimentalComposeUiApi::class)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Press) { event ->
                                // Access native AWT MouseEvent for extended button detection.
                                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent
                                val isMiddleClick =
                                    event.button == PointerButton.Tertiary ||
                                        awtEvent?.button == java.awt.event.MouseEvent.BUTTON2

                                if (isMiddleClick) {
                                    val gesture = middleClickPopupCoordinator.begin()
                                    gesture.stalePopupsToForward.forEach(handlePopupNavigation)
                                    val position = event.changes.firstOrNull()?.position
                                    event.changes.forEach { it.consume() }
                                    if (position != null) {
                                        coroutineScope.launch {
                                            val scriptResult = resolveMiddleClickTarget(
                                                handle = browserHandle,
                                                x = position.x,
                                                y = position.y
                                            )
                                            completeMiddleClick(
                                                gesture.token,
                                                middleClickUrlFromScriptResult(scriptResult)
                                            )
                                        }
                                    } else {
                                        completeMiddleClick(gesture.token, null)
                                    }
                                    return@onPointerEvent
                                }

                                middleClickPopupCoordinator.cancel()
                                    .forEach(handlePopupNavigation)

                                when (browserMouseNavigationForButton(awtEvent?.button)) {
                                    BrowserMouseNavigation.BACK -> {
                                        if (browserHandle?.canGoBack() == true) {
                                            browserHandle?.goBack()
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    BrowserMouseNavigation.FORWARD -> {
                                        if (browserHandle?.canGoForward() == true) {
                                            browserHandle?.goForward()
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    null -> Unit
                                }
                            }
                            .onPointerEvent(PointerEventType.Release) { event ->
                                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent
                                val isMiddleClick =
                                    event.button == PointerButton.Tertiary ||
                                        awtEvent?.button == java.awt.event.MouseEvent.BUTTON2
                                if (isMiddleClick) {
                                    event.changes.forEach { it.consume() }
                                    middleClickPopupCoordinator.release()
                                        ?.let(finishMiddleClickAfterRelease)
                                }
                            }
                    ) {
                        browserHandle?.Content()
                    }
                }
            }

            // Context menu (Swing-based for hardware accelerated browser compatibility)
            LaunchedEffect(showContextMenu) {
                if (showContextMenu && contextMenuInfo != null) {
                    val mouseLocation = java.awt.MouseInfo.getPointerInfo()?.location
                    if (mouseLocation != null) {
                        // Load secrets if we have formFieldInfo and a provider
                        val secretsForMenu: List<SecretEntryData> = if (contextMenuInfo?.formFieldInfo != null && secretDataProvider != null) {
                            try {
                                val result = secretDataProvider.getUserSecrets(limit = 100)
                                result.getOrNull()?.data ?: emptyList()
                            } catch (e: Exception) {
                                emptyList<SecretEntryData>()
                            }
                        } else {
                            emptyList<SecretEntryData>()
                        }

                        val menuItems = buildContextMenuItems(
                            info = contextMenuInfo,
                            browserHandle = browserHandle,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            onNavigate = { url ->
                                coroutineScope.launch {
                                    browserHandle?.loadUrl(url)
                                }
                            },
                            onOpenInNewTab = onOpenInNewTab,
                            secrets = secretsForMenu,
                            coroutineScope = coroutineScope,
                            onShowAllSecrets = {
                                showAllSecretsDialog = true
                            },
                            onAddNewSecret = { websitePrefill ->
                                quickCreateWebsitePrefill = websitePrefill
                                showQuickCreateDialog = true
                            },
                            isBookmarked = isBookmarked,
                            onAddBookmark = {
                                // Add or remove bookmark using the host API
                                bookmarkDataProvider?.let { provider ->
                                    val tabConfig = TabConfig(
                                        type = "browser",
                                        title = pageTitle,
                                        url = currentUrl
                                    )
                                    if (isBookmarked) {
                                        // Remove bookmark
                                        val bookmarkInfo = provider.findBookmarkForTab(tabConfig)
                                        if (bookmarkInfo != null) {
                                            val (collectionId, bookmarkId) = bookmarkInfo
                                            provider.removeBookmark(collectionId, bookmarkId)
                                        }
                                        isBookmarked = false
                                    } else {
                                        // Add bookmark to the favorites collection (by isFavorite flag,
                                        // not literal name). Falls back to the first available collection
                                        // so the save never silently no-ops when "Favorites" was renamed.
                                        val target = provider.collections.value.firstOrNull { it.isFavorite }
                                            ?: provider.collections.value.firstOrNull()
                                        if (target != null) {
                                            val bookmark = Bookmark(
                                                tabConfig = tabConfig,
                                                workspaceName = "Default"
                                            )
                                            provider.addBookmark(target.name, bookmark)
                                            isBookmarked = true
                                        }
                                    }
                                }
                            }
                        )
                        SwingContextMenu.show(
                            screenX = mouseLocation.x,
                            screenY = mouseLocation.y,
                            items = menuItems,
                            onDismiss = {
                                showContextMenu = false
                                contextMenuInfo = null
                            }
                        )
                    }
                }
            }

            // Secret Selection Dialog
            if (showAllSecretsDialog) {
                SecretSelectionDialog(
                    currentUrl = currentUrl,
                    secrets = allSecrets,
                    browserHandle = browserHandle,
                    coroutineScope = coroutineScope,
                    onDismiss = { showAllSecretsDialog = false },
                    onAddNewSecret = { websitePrefill ->
                        showAllSecretsDialog = false
                        quickCreateWebsitePrefill = websitePrefill
                        showQuickCreateDialog = true
                    }
                )
            }

            // Quick Create Secret Dialog
            if (showQuickCreateDialog) {
                QuickCreateSecretDialog(
                    websitePrefill = quickCreateWebsitePrefill,
                    secretDataProvider = secretDataProvider,
                    coroutineScope = coroutineScope,
                    onDismiss = { showQuickCreateDialog = false },
                    onSecretCreated = {
                        // Reload secrets after creation
                        coroutineScope.launch {
                            try {
                                val result = secretDataProvider?.getUserSecrets(limit = 1000)
                                allSecrets = result?.getOrNull()?.data ?: emptyList()
                            } catch (e: Exception) {
                                // Silently fail
                            }
                        }
                        showQuickCreateDialog = false
                    }
                )
            }

            // Loading indicator — overlay at the top of the browser content area so
            // the 2.dp bar doesn't shift everything below it on every reload.
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colors.primary
                )
            }
        }
        } // End Column

        // Floating URL autocomplete dropdown overlay (positioned below toolbar)
        if (showUrlSuggestions && urlSuggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.5f) // Half the width of the screen
                    .wrapContentHeight()
                    .align(Alignment.TopCenter)
                    .offset(y = 38.dp), // Position below the navigation bar
                elevation = 8.dp,
                backgroundColor = MaterialTheme.colors.surface
            ) {
                LazyColumn(
                    state = dropdownListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    itemsIndexed(urlSuggestions) { index, entry ->
                        val rowInteractionSource = remember { MutableInteractionSource() }
                        val isRowHovered by rowInteractionSource.collectIsHoveredAsState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index == selectedDropdownIndex)
                                        MaterialTheme.colors.primary.copy(alpha = 0.1f)
                                    else
                                        MaterialTheme.colors.surface
                                )
                                .hoverable(rowInteractionSource)
                                .clickable {
                                    urlBarText = TextFieldValue(entry.url, TextRange(entry.url.length))
                                    showUrlSuggestions = false
                                    autocompleteSuggestion = null
                                    selectedDropdownIndex = -1
                                    isUserEditingUrl = false
                                    lastUserEditTime = 0L
                                    coroutineScope.launch {
                                        browserHandle?.loadUrl(entry.url)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Icon to indicate type
                            Icon(
                                imageVector = if (entry.title.contains("Google Search", ignoreCase = true))
                                    Icons.Filled.Search
                                else
                                    Icons.Outlined.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.title.ifBlank { entry.domain },
                                    style = MaterialTheme.typography.body2,
                                    color = MaterialTheme.colors.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    text = entry.url,
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1
                                )
                            }
                            // Forget this entry. Shown for the row under the pointer and
                            // for the arrow-key selection (whose shift+Delete does the
                            // same thing), so the affordance is discoverable either way.
                            if (isRowHovered || index == selectedDropdownIndex) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove from history",
                                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeleteSuggestion(entry) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Co-browse approval banners (BossTerm-style): non-modal cards in the
        // top-right, one per waiting viewer. Shown in the tab that initiated the
        // share; the Share window's PendingRequestsList covers the no-visible-tab case.
        if (thisTabInitiatedShare && pendingApprovals.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingApprovals.forEach { req ->
                    ShareRequestToast(
                        deviceName = req.deviceName,
                        wantsControl = req.wantsControl,
                        verifyCode = liveShareInfo?.e2eCode,
                        onApprove = { BrowserShareManager.approveRequest(req.id) },
                        onDeny = { BrowserShareManager.denyRequest(req.id) },
                    )
                }
            }
        }
    } // End Box
    } // End BossTheme
}

/**
 * Context menu item data class.
 */
data class ContextMenuItem(
    val text: String = "",
    val isDivider: Boolean = false,
    val onClick: () -> Unit = {}
)

/**
 * Extract main domain from URL for secret matching.
 * e.g., "https://mail.google.com/inbox" -> "google.com"
 */
private fun extractMainDomain(url: String): String? {
    return try {
        val uri = java.net.URI(url)
        val host = uri.host ?: return null

        // Handle localhost
        if (host == "localhost" || host.startsWith("127.")) {
            return host
        }

        // Get the effective TLD+1 (main domain)
        val parts = host.split(".")
        if (parts.size >= 2) {
            // Common multi-part TLDs
            val multiPartTlds = setOf("co.uk", "com.au", "co.jp", "co.nz", "com.br", "co.in")
            val lastTwo = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"

            if (multiPartTlds.contains(lastTwo) && parts.size >= 3) {
                "${parts[parts.size - 3]}.$lastTwo"
            } else {
                lastTwo
            }
        } else {
            host
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Match secrets against a domain.
 * Returns secrets where the website field matches the domain.
 */
private fun matchSecretsForDomain(
    domain: String,
    secrets: List<SecretEntryData>,
    maxResults: Int = 5
): List<SecretEntryData> {
    val lowerDomain = domain.lowercase()

    return secrets.filter { secret ->
        val secretDomain = extractMainDomain(secret.website)?.lowercase()
            ?: secret.website.lowercase()

        secretDomain.contains(lowerDomain) || lowerDomain.contains(secretDomain) ||
                secret.website.lowercase().contains(lowerDomain)
    }.take(maxResults)
}

/**
 * Get display name for a website.
 * Extracts a clean, readable name from the website URL.
 */
private fun getDisplayName(website: String): String {
    return try {
        val domain = extractMainDomain(website) ?: website
        // Capitalize first letter of domain
        domain.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    } catch (e: Exception) {
        website
    }
}

/**
 * Build context menu items based on browser state.
 */
private fun buildContextMenuItems(
    info: BrowserContextMenuInfo?,
    browserHandle: BrowserHandle?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onNavigate: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    secrets: List<SecretEntryData> = emptyList(),
    coroutineScope: CoroutineScope? = null,
    onShowAllSecrets: () -> Unit = {},
    onAddNewSecret: (websitePrefill: String) -> Unit = {},
    isBookmarked: Boolean = false,
    onAddBookmark: () -> Unit = {}
): List<ContextMenuItem> = buildList {
    // Check if form field is focused (editable element)
    if (info?.isEditable == true) {
        // Form field context menu (matches original focusedFieldInfo != null case)

        // Edit operations for text fields (first, like main branch)
        add(ContextMenuItem(
            text = "Copy",
            onClick = { browserHandle?.copySelection() }
        ))

        add(ContextMenuItem(
            text = "Paste",
            onClick = { browserHandle?.paste() }
        ))

        add(ContextMenuItem(isDivider = true))

        // Secret menu items (matches SecretContextMenuBuilder.buildSecretMenu)
        val formFieldInfo = info.formFieldInfo
        if (formFieldInfo != null) {
            val domain = extractMainDomain(info.pageUrl)

            // Header
            add(ContextMenuItem(
                text = "🔑 Fill Credential",
                onClick = {}  // Header, non-clickable
            ))

            if (domain != null && secrets.isNotEmpty()) {
                val matchedSecrets = matchSecretsForDomain(domain, secrets)

                if (matchedSecrets.isNotEmpty()) {
                    add(ContextMenuItem(isDivider = true))

                    // Add matched secrets
                    matchedSecrets.forEach { secret ->
                        val displayName = getDisplayName(secret.website)
                        val usernamePreview = if (secret.username.length > 25) {
                            secret.username.take(22) + "..."
                        } else {
                            secret.username
                        }

                        add(ContextMenuItem(
                            text = "$displayName ($usernamePreview)",
                            onClick = {
                                // Fill credentials using the browser handle
                                coroutineScope?.launch {
                                    browserHandle?.fillCredentials(
                                        username = secret.username,
                                        password = secret.password,
                                        fillBoth = true
                                    )
                                }
                            }
                        ))
                    }
                } else {
                    // No matches for this domain
                    add(ContextMenuItem(
                        text = "No matching secrets for $domain",
                        onClick = {}  // Informational
                    ))
                }
            }

            add(ContextMenuItem(isDivider = true))

            // "Show All Secrets" option
            add(ContextMenuItem(
                text = "Show All Secrets...",
                onClick = onShowAllSecrets
            ))

            // "Add New Secret" option (with domain pre-filled)
            add(ContextMenuItem(
                text = "Add New Secret",
                onClick = { onAddNewSecret(domain ?: "") }
            ))

            add(ContextMenuItem(isDivider = true))
        }

        // Reload
        add(ContextMenuItem(
            text = "Reload",
            onClick = { browserHandle?.reload() }
        ))

        // Copy Page URL
        add(ContextMenuItem(
            text = "Copy Page URL",
            onClick = {
                info.pageUrl.let { copyToClipboard(it) }
            }
        ))

        // Developer tools
        add(ContextMenuItem(
            text = "Inspect Element",
            onClick = { browserHandle?.showDevTools() }
        ))
    } else {
        // Default context menu

        // Navigation items (only show if available)
        if (canGoBack) {
            add(ContextMenuItem(
                text = "Back",
                onClick = { browserHandle?.goBack() }
            ))
        }

        if (canGoForward) {
            add(ContextMenuItem(
                text = "Forward",
                onClick = { browserHandle?.goForward() }
            ))
        }

        // Always show reload
        add(ContextMenuItem(
            text = "Reload",
            onClick = { browserHandle?.reload() }
        ))

        add(ContextMenuItem(isDivider = true))

        // Picture-in-Picture option if clicking on a video
        if (info?.hasVideo == true) {
            add(ContextMenuItem(
                text = "Picture in Picture",
                onClick = { browserHandle?.requestPictureInPicture() }
            ))
            add(ContextMenuItem(isDivider = true))
        }

        // Copy selected text
        val selectedText = info?.selectedText
        if (!selectedText.isNullOrEmpty()) {
            add(ContextMenuItem(
                text = "Copy",
                onClick = {
                    copyToClipboard(selectedText)
                }
            ))

            // Search selected text in new tab
            add(ContextMenuItem(
                text = "Search with Google",
                onClick = {
                    val encodedQuery = URLEncoder.encode(selectedText, "UTF-8")
                    val searchUrl = "https://www.google.com/search?q=$encodedQuery"
                    onNavigate(searchUrl)
                }
            ))
        }

        // Copy URL - copies link URL if on a link, otherwise copies page URL
        val linkUrl = info?.linkUrl
        if (!linkUrl.isNullOrEmpty()) {
            // Right-clicked on a link
            add(ContextMenuItem(
                text = "Copy Link URL",
                onClick = { copyToClipboard(linkUrl) }
            ))

            add(ContextMenuItem(
                text = "Open Link in New Tab",
                onClick = { onOpenInNewTab(linkUrl) }
            ))
        } else {
            // Not on a link - copy current page URL
            add(ContextMenuItem(
                text = "Copy Page URL",
                onClick = {
                    info?.pageUrl?.let { copyToClipboard(it) }
                }
            ))
        }

        add(ContextMenuItem(isDivider = true))

        // Bookmark option
        add(ContextMenuItem(
            text = if (isBookmarked) "Remove Bookmark" else "Add Bookmark",
            onClick = onAddBookmark
        ))

        // Developer tools (always at the end)
        add(ContextMenuItem(
            text = "Inspect Element",
            onClick = { browserHandle?.showDevTools() }
        ))
    }
}

/**
 * Swing-based context menu for browser in HARDWARE_ACCELERATED mode.
 * Uses native AWT JPopupMenu which is heavyweight and can appear above
 * the browser view, unlike Compose's lightweight Popup component.
 */
object SwingContextMenu {
    private var currentPopup: JPopupMenu? = null

    fun show(
        screenX: Int,
        screenY: Int,
        items: List<ContextMenuItem>,
        onDismiss: () -> Unit = {}
    ) {
        // Dismiss any existing popup first
        currentPopup?.let {
            it.isVisible = false
        }

        val popup = JPopupMenu().apply {
            // Dark theme colors matching BOSS style
            background = AwtColor(0x2B, 0x2B, 0x2B)
            border = BorderFactory.createLineBorder(AwtColor(0x3C, 0x3F, 0x41), 1)
        }

        // Add items to popup
        items.forEach { item ->
            if (item.isDivider) {
                val separator = JSeparator().apply {
                    background = AwtColor(0x2B, 0x2B, 0x2B)
                    foreground = AwtColor(0x3C, 0x3F, 0x41)
                }
                popup.add(separator)
            } else {
                val menuItem = JMenuItem(item.text).apply {
                    background = AwtColor(0x2B, 0x2B, 0x2B)
                    foreground = AwtColor.WHITE
                    font = Font(".AppleSystemUIFont", Font.PLAIN, 13)
                    border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
                    isOpaque = true
                    addActionListener {
                        item.onClick()
                        onDismiss()
                    }
                }
                popup.add(menuItem)
            }
        }

        // Add listener to track popup dismissal
        popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
                currentPopup = null
                onDismiss()
            }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })

        currentPopup = popup

        // Find the window to use as invoker
        var targetWindow: Window? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow

        // If no focused window, find window at mouse position
        if (targetWindow == null) {
            val mousePoint = java.awt.Point(screenX, screenY)
            targetWindow = Window.getWindows()
                .filter { it.isVisible && it.bounds.contains(mousePoint) }
                .maxByOrNull { it.bounds.width * it.bounds.height }

            targetWindow?.toFront()
            targetWindow?.requestFocus()
        }

        if (targetWindow != null) {
            // Convert screen coordinates to window-relative
            val windowLocation = targetWindow.locationOnScreen
            val relativeX = screenX - windowLocation.x
            val relativeY = screenY - windowLocation.y
            popup.show(targetWindow, relativeX, relativeY)
        } else {
            // Fallback: show at screen location
            popup.location = java.awt.Point(screenX, screenY)
            popup.isVisible = true
        }
    }

    fun hide() {
        currentPopup?.let {
            it.isVisible = false
            currentPopup = null
        }
    }
}

/**
 * Copy text to system clipboard.
 */
private fun copyToClipboard(text: String) {
    try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (e: Exception) {
        // Silently fail - clipboard operations can fail in certain environments
    }
}

// Share window palette — sourced from the reactive BOSS theme tokens so the
// co-browse window re-skins with the host (previously hardcoded to match
// BossTerm's SettingsTheme).
private val ShareBg get() = BossThemeColors.BackgroundColor
private val ShareSurface get() = BossThemeColors.SurfaceColor
private val ShareAccent get() = BossThemeColors.AccentColor
private val ShareBorder get() = BossThemeColors.BorderColor
private val ShareTextMuted get() = BossThemeColors.TextMuted
private val ShareDanger get() = BossThemeColors.ErrorColor
private val ShareApprove get() = BossThemeColors.SuccessColor

/**
 * Full-width pending-request row for the share window (BossTerm's PendingRequestsList
 * style): device name + what it asked for on the left, Deny / Approve on the right.
 */
@Composable
private fun PendingRequestRow(
    deviceName: String,
    wantsControl: Boolean,
    verifyCode: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(color = ShareSurface, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(deviceName, color = BossThemeColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "wants to ${if (wantsControl) "control" else "view"}" + (verifyCode?.let { " · 🔒 $it" } ?: ""),
                    color = ShareTextMuted, fontSize = 11.sp
                )
            }
            TextButton(onClick = onDeny, colors = ButtonDefaults.textButtonColors(contentColor = ShareDanger)) {
                Text("Deny")
            }
            Button(
                onClick = onApprove,
                colors = ButtonDefaults.buttonColors(backgroundColor = ShareApprove, contentColor = BossThemeColors.TextPrimary)
            ) { Text("Approve") }
        }
    }
}

/**
 * BossTerm-style non-modal approval banner: a floating dark card prompting the host
 * to approve/deny one viewer's request. Stacked in the shared tab's top-right overlay.
 */
@Composable
private fun ShareRequestToast(
    deviceName: String,
    wantsControl: Boolean,
    verifyCode: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        color = ShareSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ShareBorder),
        elevation = 6.dp,
    ) {
        Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Browser sharing", color = ShareTextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "$deviceName wants to ${if (wantsControl) "control" else "view"} this tab",
                color = BossThemeColors.TextPrimary, fontSize = 13.sp
            )
            verifyCode?.let { code ->
                Spacer(Modifier.height(2.dp))
                Text("🔒 Approve only if their code is $code", color = ShareTextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDeny, colors = ButtonDefaults.textButtonColors(contentColor = ShareDanger)) {
                    Text("Deny")
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(backgroundColor = ShareApprove, contentColor = BossThemeColors.TextPrimary)
                ) { Text("Approve") }
            }
        }
    }
}

/**
 * Co-browse share window — a separate OS window (BossTerm-style): QR + View/Control
 * toggle, live status with a hot-reloading link, E2E code, links, mask, Stop sharing.
 */
@Composable
private fun ShareLinkDialog(
    maskInputs: Boolean,
    onToggleMask: (Boolean) -> Unit,
    onRefreshLink: () -> Unit,
    onStopSharing: () -> Unit,
    onDismiss: () -> Unit,
) {
    Window(
        onCloseRequest = onDismiss,
        title = "BOSS — Share Tab",
        resizable = false,
        state = rememberWindowState(size = DpSize(560.dp, 720.dp)),
    ) {
      MaterialTheme(
          colors = darkColors(
              primary = ShareAccent, onPrimary = BossThemeColors.TextPrimary,
              surface = ShareSurface, onSurface = BossThemeColors.TextPrimary,
              background = ShareBg, error = ShareDanger,
          )
      ) {
        // Collected INSIDE the Window so this composition subscribes directly —
        // makes the link/QR hot-reload when the Cloudflare tunnel resolves.
        val info by BrowserShareManager.shareInfo.collectAsState()
        val viewerCount by BrowserShareManager.viewerCount.collectAsState()
        val pending by BrowserShareManager.pendingRequests.collectAsState()
        val current = info
        if (current == null) {
            Surface(color = ShareBg, modifier = Modifier.fillMaxSize()) {}
            return@MaterialTheme
        }
        val loopbackOnly = current.viewUrl.contains("://127.0.0.1") || current.viewUrl.contains("://localhost")
        val muted = ShareTextMuted
        // View vs Control: the QR + links update to whichever is selected.
        var showControl by remember { mutableStateOf(false) }
        val link = if (showControl) current.controlUrl else current.viewUrl
        val qr = remember(link) { qrImageBitmap(link) }
        Surface(color = ShareBg, modifier = Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Headline = the editable session name (what viewers see). Compact
                // BasicTextField (BossTerm-style), not Material's tall OutlinedTextField.
                var nameField by remember { mutableStateOf(current.sessionName) }
                Column {
                    Text("Session name", color = muted, fontSize = 11.sp)
                    BasicTextField(
                        value = nameField,
                        onValueChange = { nameField = it; BrowserShareManager.setSessionName(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = BossThemeColors.TextPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(ShareAccent),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(ShareBg, RoundedCornerShape(6.dp))
                                    .border(1.dp, ShareBorder, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) { inner() }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
                Text(
                    "Open a link on another device to watch live; the control link also lets it click and type.",
                    style = MaterialTheme.typography.caption, color = muted
                )

                // --- Pending approval requests (full-width rows, like BossTerm) ---
                if (pending.isNotEmpty()) {
                    Text("Pending requests", color = BossThemeColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    pending.forEach { req ->
                        PendingRequestRow(
                            deviceName = req.deviceName,
                            wantsControl = req.wantsControl,
                            verifyCode = current.e2eCode,
                            onApprove = { BrowserShareManager.approveRequest(req.id) },
                            onDeny = { BrowserShareManager.denyRequest(req.id) },
                        )
                    }
                }

                // --- QR section (QR, then View/Control toggle, then caption) ---
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = if (showControl) "Control link QR" else "View link QR",
                            modifier = Modifier.size(200.dp).background(Color.White).padding(8.dp)
                        )
                    }
                    ShareModeToggle(showControl = showControl, onChange = { showControl = it })
                    Text(
                        if (showControl) "QR encodes the Control link \u2014 scanning grants click/type access."
                        else "QR encodes the View link (read-only).",
                        style = MaterialTheme.typography.caption,
                        color = if (showControl) MaterialTheme.colors.error else muted,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                }

                // --- Live status + refresh ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current.tunnelPending) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                            Text("Creating public link\u2026", style = MaterialTheme.typography.caption, color = muted)
                        } else {
                            Box(Modifier.size(8.dp).background(BossThemeColors.SuccessColor, CircleShape))
                            Text(
                                when (viewerCount) {
                                    0 -> "Live \u2014 waiting for viewers"
                                    1 -> "Live \u2014 1 viewer"
                                    else -> "Live \u2014 $viewerCount viewers"
                                },
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                    TextButton(onClick = onRefreshLink, enabled = !current.tunnelPending) {
                        Text(if (current.tunnelPending) "Refreshing\u2026" else "\u21BB Refresh link", style = MaterialTheme.typography.caption)
                    }
                }

                // --- Links section: E2E code, both rows, reachability ---
                current.e2eCode?.let { code ->
                    Text(
                        "\uD83D\uDD12 End-to-end encrypted \u00B7 code $code \u2014 the relay can't read this session. " +
                            "The same code shows on the viewer; matching codes confirm the key.",
                        style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary
                    )
                }
                ShareLinkRow("View (read-only)", current.viewUrl)
                ShareLinkRow("Control (can type)", current.controlUrl)
                Text(
                    if (loopbackOnly)
                        "Reachable only on this machine \u2014 a public link is being created (or cloudflared isn't installed)."
                    else
                        "Public link is ephemeral \u2014 use \u21BB Refresh link to mint a new one.",
                    style = MaterialTheme.typography.caption, color = muted
                )
                if (!current.secure) {
                    Text(
                        "\u26A0 Not encrypted \u2014 this link is plaintext. Use it only on a trusted LAN.",
                        style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error
                    )
                }

                // --- Privacy: input masking (fluck-specific) ---
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = maskInputs, onCheckedChange = onToggleMask)
                        Text("Mask typed input", style = MaterialTheme.typography.body2)
                    }
                    Text(
                        "Form values stream as \u2022\u2022\u2022 (passwords are always masked).",
                        style = MaterialTheme.typography.caption, color = muted,
                        modifier = Modifier.padding(start = 48.dp)
                    )
                }
            } // end scrollable content

            // Pinned footer \u2014 always visible without scrolling.
            Box(Modifier.fillMaxWidth().height(1.dp).background(ShareBorder))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onStopSharing, colors = ButtonDefaults.textButtonColors(contentColor = ShareDanger)) {
                    Text("Stop sharing")
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(backgroundColor = ShareAccent, contentColor = BossThemeColors.TextPrimary)
                ) { Text("Close") }
            }
          }
        }
      }
    }
}

/** Two-segment View/Control toggle shown under the share QR. */
@Composable
private fun ShareModeToggle(showControl: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            listOf(false to "View", true to "Control").forEach { (control, label) ->
                val selected = showControl == control
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) MaterialTheme.colors.primary else Color.Transparent,
                    modifier = Modifier.weight(1f).clickable { onChange(control) }
                ) {
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.body2,
                        color = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** Render [text] as a crisp QR code (tight modules, integer upscale) into a Compose ImageBitmap. */
private fun qrImageBitmap(text: String, target: Int = 480): ImageBitmap? = runCatching {
    val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 1, 1, hints)
    val n = matrix.width
    val scale = (target / n).coerceAtLeast(1)
    val px = n * scale
    val image = java.awt.image.BufferedImage(px, px, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val black = 0xFF000000.toInt(); val white = 0xFFFFFFFF.toInt()
    for (my in 0 until n) for (mx in 0 until n) {
        val color = if (matrix.get(mx, my)) black else white
        for (dy in 0 until scale) for (dx in 0 until scale) image.setRGB(mx * scale + dx, my * scale + dy, color)
    }
    image.toComposeImageBitmap()
}.getOrNull()

@Composable
private fun ShareLinkRow(label: String, url: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.06f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    url,
                    style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(url))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy link",
                        tint = if (copied) BossThemeColors.SuccessColor else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun BrowserToolbar(
    urlBarText: TextFieldValue,
    onUrlBarTextChange: (TextFieldValue) -> Unit,
    onNavigate: (String) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    isSecure: Boolean,
    zoomLevel: Double,
    onZoomChange: (Double) -> Unit,
    isBookmarked: Boolean,
    onBookmarkClick: () -> Unit,
    urlSuggestions: List<UrlHistoryEntry> = emptyList(),
    showUrlSuggestions: Boolean = false,
    autocompleteSuggestion: String? = null,
    selectedDropdownIndex: Int = -1,
    dropdownListState: LazyListState = rememberLazyListState(),
    onSuggestionSelected: (UrlHistoryEntry) -> Unit = {},
    onDismissSuggestions: () -> Unit = {},
    onAcceptAutocomplete: () -> Unit = {},
    onSelectedDropdownIndexChange: (Int) -> Unit = {},
    onSuggestionDeleted: (UrlHistoryEntry) -> Unit = {},
    onFocusLost: () -> Unit = {},
    onShare: (() -> Unit)? = null,
    isSharing: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    // Auto-scroll to selected suggestion when using arrow keys
    LaunchedEffect(selectedDropdownIndex) {
        if (selectedDropdownIndex >= 0 && urlSuggestions.isNotEmpty()) {
            dropdownListState.animateScrollToItem(selectedDropdownIndex)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Share (co-browse) button — a QR icon, like BossTerm. Green while a share
        // session is live so an active stream is always visible. (Connecting to a
        // remote session is handled at the workspace level, not here.)
        if (onShare != null) {
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCode2,
                    contentDescription = if (isSharing) "Sharing is live — manage" else "Share this tab",
                    tint = if (isSharing) BossThemeColors.SuccessColor else BossThemeColors.TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Back button
        IconButton(
            onClick = onBack,
            enabled = canGoBack,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        // Forward button
        IconButton(
            onClick = onForward,
            enabled = canGoForward,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Forward",
                tint = if (canGoForward) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        // Refresh/Stop button - changes based on loading state
        // Matches bundled browser: when not loading, navigates to URL bar text (not just reload)
        IconButton(
            onClick = {
                if (isLoading) {
                    // Stop the current navigation
                    onStop()
                } else {
                    // Reload/navigate to URL - matches bundled browser exactly
                    val urlToLoad = if (autocompleteSuggestion != null &&
                        urlBarText.text == autocompleteSuggestion.take(urlBarText.text.length)) {
                        processUrlInput(autocompleteSuggestion)
                    } else {
                        val input = urlBarText.text.trim()
                        processUrlInput(input)
                    }
                    onDismissSuggestions()
                    onNavigate(urlToLoad)
                }
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                contentDescription = if (isLoading) "Stop" else "Refresh",
                tint = BossThemeColors.TextPrimary,
                modifier = Modifier.size(18.dp)
            )
        }

        // URL text field with bookmark star, zoom indicator, and autocomplete dropdown
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = urlBarText,
                onValueChange = onUrlBarTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            onFocusLost()
                        }
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        when {
                            keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Tab -> {
                                // Accept autocomplete suggestion with Tab
                                if (autocompleteSuggestion != null) {
                                    onAcceptAutocomplete()
                                    true
                                } else {
                                    false
                                }
                            }
                            keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter -> {
                                // Resolve the URL the user actually wants. Priority:
                                //  1. Explicitly selected dropdown item (arrow-key navigation).
                                //  2. Inline autocomplete suggestion (ghost text shown after the cursor).
                                //     Picks the first urlSuggestion, which is what the inline ghost
                                //     was previewing. Enter should commit it instead of forcing the
                                //     user to press Tab/Right first and then Enter.
                                //  3. Plain typed text run through processUrlInput.
                                val urlToLoad = when {
                                    selectedDropdownIndex >= 0 && selectedDropdownIndex < urlSuggestions.size -> {
                                        urlSuggestions[selectedDropdownIndex].url
                                    }
                                    autocompleteSuggestion != null && urlSuggestions.isNotEmpty() -> {
                                        urlSuggestions.first().url
                                    }
                                    else -> {
                                        val input = urlBarText.text.trim()
                                        processUrlInput(input)
                                    }
                                }
                                onDismissSuggestions()
                                onNavigate(urlToLoad)
                                true
                            }
                            keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown -> {
                                if (showUrlSuggestions && urlSuggestions.isNotEmpty()) {
                                    val newIndex = (selectedDropdownIndex + 1).coerceAtMost(urlSuggestions.size - 1)
                                    onSelectedDropdownIndexChange(newIndex)
                                }
                                true
                            }
                            keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp -> {
                                if (showUrlSuggestions && urlSuggestions.isNotEmpty()) {
                                    val newIndex = (selectedDropdownIndex - 1).coerceAtLeast(-1)
                                    onSelectedDropdownIndexChange(newIndex)
                                }
                                true
                            }
                            keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionRight -> {
                                // Accept inline autocomplete when cursor is at the end of input
                                if (autocompleteSuggestion != null &&
                                    urlBarText.selection.collapsed &&
                                    urlBarText.selection.start == urlBarText.text.length) {
                                    onAcceptAutocomplete()
                                    onDismissSuggestions()
                                    true
                                } else {
                                    false
                                }
                            }
                            keyEvent.type == KeyEventType.KeyDown &&
                                keyEvent.isShiftPressed &&
                                (keyEvent.key == Key.Delete || keyEvent.key == Key.Backspace) &&
                                showUrlSuggestions &&
                                selectedDropdownIndex in urlSuggestions.indices -> {
                                // Forget the highlighted suggestion (Chrome's shift+delete).
                                // Both keys are accepted because the key labelled "delete" on a
                                // Mac keyboard reports as Backspace. Gated on a row having been
                                // highlighted with the arrow keys, so shift+backspace still edits
                                // text the moment no suggestion is selected.
                                onSuggestionDeleted(urlSuggestions[selectedDropdownIndex])
                                true
                            }
                            keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape -> {
                                onDismissSuggestions()
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colors.surface)
                            .border(
                                1.dp,
                                MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                            )
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Security indicator (lock icon for HTTPS)
                        if (isSecure) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Secure connection",
                                tint = BossThemeColors.SuccessColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // URL input area
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // Placeholder when empty
                            if (urlBarText.text.isEmpty()) {
                                Text(
                                    "Enter URL or search",
                                    style = MaterialTheme.typography.body2,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            // Show autocomplete using AnnotatedString approach
                            if (autocompleteSuggestion != null &&
                                urlBarText.text.isNotEmpty() &&
                                autocompleteSuggestion.lowercase().startsWith(urlBarText.text.lowercase())) {

                                // Build styled text: user's input (transparent) + autocomplete suffix (gray)
                                val annotatedString = buildAnnotatedString {
                                    // User's input in transparent (so actual input shows through)
                                    withStyle(SpanStyle(color = Color.Transparent)) {
                                        append(urlBarText.text)
                                    }
                                    // Autocomplete suffix in gray
                                    withStyle(SpanStyle(color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f))) {
                                        append(autocompleteSuggestion.substring(urlBarText.text.length))
                                    }
                                }

                                Text(
                                    text = annotatedString,
                                    style = MaterialTheme.typography.body2,
                                    maxLines = 1
                                )
                            }

                            // Actual text field
                            innerTextField()
                        }

                        // Bookmark star button
                        IconButton(
                            onClick = onBookmarkClick,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (isBookmarked) "Remove from Bookmarks" else "Add to Bookmarks",
                                tint = if (isBookmarked) Color(0xFFFFD700) else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Zoom level indicator (only shown when not at 100%)
                        if (abs(zoomLevel - 1.0) > 0.001) {
                            Text(
                                text = "${(zoomLevel * 100).toInt()}%",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .clickable { onZoomChange(1.0) }
                            )
                        }
                    }
                }
            )

            // Dropdown is rendered as floating overlay in parent, not here
        }
    }
}

/**
 * Error/loading content when browser fails to load or is initializing.
 * Shows message with Retry and Reset Tab buttons.
 * When isLoading=true, shows a spinner; otherwise shows a warning icon.
 */
@Composable
internal fun BrowserErrorContent(
    error: String,
    isLoading: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onResetTab: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = BossThemeColors.WarningColor,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = error,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onRetry != null) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.primary,
                            contentColor = MaterialTheme.colors.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry Loading")
                    }
                }

                if (onResetTab != null) {
                    OutlinedButton(onClick = onResetTab) {
                        Text("Reset Tab")
                    }
                }
            }
        }
    }
}

/**
 * Stub content when browser service is not available.
 */
@Composable
internal fun FluckBrowserStubContent() {
    ai.rever.boss.plugin.ui.BossTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = "Browser",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colors.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Browser",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    backgroundColor = MaterialTheme.colors.surface,
                    elevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Browser Not Available",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.error
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "The browser service is not available. This may be due to licensing or initialization issues.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "This may be due to licensing or initialization issues.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// SECRET DIALOGS
// ============================================================

private val BossDarkBackground get() = BossThemeColors.BackgroundColor
private val BossDarkBorder get() = BossThemeColors.BorderColor
private val BossDarkTextSecondary get() = BossThemeColors.TextSecondary

/**
 * Dialog for browsing and selecting secrets for auto-fill.
 */
@Composable
private fun SecretSelectionDialog(
    currentUrl: String,
    secrets: List<SecretEntryData>,
    browserHandle: BrowserHandle?,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
    onAddNewSecret: (websitePrefill: String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // Filter secrets based on search query
    val filteredSecrets = remember(secrets, searchQuery) {
        if (searchQuery.isBlank()) {
            secrets
        } else {
            val query = searchQuery.lowercase().trim()
            secrets.filter { secret ->
                secret.website.lowercase().contains(query) ||
                    secret.username.lowercase().contains(query) ||
                    secret.notes?.lowercase()?.contains(query) == true ||
                    secret.tags.any { it.lowercase().contains(query) }
            }
        }
    }

    // Extract domain for highlighting matched secrets
    val currentDomain = remember(currentUrl) { extractMainDomain(currentUrl) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(700.dp)
                .height(600.dp),
            elevation = 8.dp,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colors.primary)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colors.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Select Secret to Fill",
                            style = MaterialTheme.typography.h6,
                            color = MaterialTheme.colors.onPrimary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colors.onPrimary
                        )
                    }
                }

                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colors.primary),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colors.surface,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search by website, username, or tags...",
                                            style = MaterialTheme.typography.body1,
                                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear search",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                Divider()

                // Secrets list
                if (filteredSecrets.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                if (searchQuery.isNotEmpty()) "No secrets match your search" else "No secrets available",
                                style = MaterialTheme.typography.body1,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                            if (currentDomain != null) {
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(onClick = {
                                    onAddNewSecret(currentDomain)
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Add Secret for $currentDomain")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredSecrets) { secret ->
                            SecretListItem(
                                secret = secret,
                                isMatched = currentDomain != null &&
                                    (secret.website.lowercase().contains(currentDomain.lowercase()) ||
                                     currentDomain.lowercase().contains(extractMainDomain(secret.website)?.lowercase() ?: "")),
                                onClick = {
                                    coroutineScope.launch {
                                        browserHandle?.fillCredentials(
                                            username = secret.username,
                                            password = secret.password,
                                            fillBoth = true
                                        )
                                        onDismiss()
                                    }
                                }
                            )
                        }
                    }
                }

                // Footer with actions
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${filteredSecrets.size} secret${if (filteredSecrets.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )

                    if (currentDomain != null) {
                        TextButton(onClick = { onAddNewSecret(currentDomain) }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add New Secret")
                        }
                    }
                }
            }
        }
    }
}

/**
 * List item for a secret entry.
 */
@Composable
private fun SecretListItem(
    secret: SecretEntryData,
    isMatched: Boolean,
    onClick: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = if (isMatched) 4.dp else 2.dp,
        backgroundColor = if (isMatched)
            MaterialTheme.colors.primary.copy(alpha = 0.1f)
        else
            MaterialTheme.colors.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                if (isMatched) Icons.Default.CheckCircle else Icons.Outlined.Language,
                contentDescription = null,
                tint = if (isMatched)
                    MaterialTheme.colors.primary
                else
                    MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Secret info
            Column(modifier = Modifier.weight(1f)) {
                // Website
                Text(
                    getDisplayName(secret.website),
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Username
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        secret.username,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                    )
                }

                // Password preview
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (showPassword) secret.password else "••••••••",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    IconButton(
                        onClick = { showPassword = !showPassword },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Tags (if any)
                if (secret.tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        secret.tags.take(3).forEach { tag ->
                            Surface(
                                color = MaterialTheme.colors.primary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (secret.tags.size > 3) {
                            Text(
                                "+${secret.tags.size - 3}",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // Match indicator
                if (isMatched) {
                    Text(
                        "✓ Matches current website",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Fill button
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Default.Login,
                    contentDescription = "Fill credentials",
                    tint = MaterialTheme.colors.primary
                )
            }
        }
    }
}

/**
 * Quick create secret dialog for browser integration.
 */
@Composable
private fun QuickCreateSecretDialog(
    websitePrefill: String,
    secretDataProvider: SecretDataProvider?,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
    onSecretCreated: () -> Unit
) {
    var website by remember { mutableStateOf(websitePrefill) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(380.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Save Credentials",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    "Save login credentials for this website",
                    color = BossDarkTextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Website field
                QuickDialogTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = "Website",
                    placeholder = "e.g., github.com"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Username field
                QuickDialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Username / Email",
                    placeholder = "Enter username or email"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Password field
                QuickDialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    placeholder = "Enter password",
                    isPassword = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword }
                )

                // Error message
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        errorMessage!!,
                        color = BossThemeColors.ErrorColor,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancel", color = BossDarkTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (website.isNotBlank() && username.isNotBlank() && password.isNotBlank() && secretDataProvider != null) {
                                isLoading = true
                                errorMessage = null
                                coroutineScope.launch {
                                    try {
                                        val request = CreateSecretRequestData(
                                            website = website,
                                            username = username,
                                            password = password
                                        )
                                        val result = secretDataProvider.createSecret(request)
                                        result.fold(
                                            onSuccess = {
                                                isLoading = false
                                                onSecretCreated()
                                            },
                                            onFailure = { error ->
                                                isLoading = false
                                                errorMessage = error.message ?: "Failed to create secret"
                                            }
                                        )
                                    } catch (e: Exception) {
                                        isLoading = false
                                        errorMessage = e.message ?: "Failed to create secret"
                                    }
                                }
                            }
                        },
                        enabled = !isLoading && website.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossThemeColors.TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save", color = BossThemeColors.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: (() -> Unit)? = null
) {
    Column {
        Text(
            label,
            color = BossDarkTextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BossDarkBackground, RoundedCornerShape(4.dp))
                .border(1.dp, BossDarkBorder, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                cursorBrush = SolidColor(BossThemeColors.SuccessColor),
                visualTransformation = if (isPassword && !showPassword)
                    PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                color = BossDarkTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (isPassword && onTogglePassword != null) {
                IconButton(
                    onClick = onTogglePassword,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle password visibility",
                        tint = BossDarkTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ============================================================
// FULLSCREEN SUPPORT
// ============================================================

/**
 * Placeholder shown in the tab when browser content is displayed in fullscreen mode.
 * Clicking this placeholder exits fullscreen and returns browser content to the tab.
 */
@Composable
private fun FullscreenPlaceholder(onExitClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.BackgroundColor)
            .clickable { onExitClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Fullscreen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = BossThemeColors.TextSecondary
            )
            Text(
                text = "Tab is in fullscreen mode",
                style = MaterialTheme.typography.h6,
                color = BossThemeColors.TextPrimary
            )
            Text(
                text = "Click here or press ESC to exit fullscreen",
                style = MaterialTheme.typography.body2,
                color = BossThemeColors.TextSecondary
            )
        }
    }
}
