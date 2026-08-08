package ai.rever.boss.plugin.dynamic.fluckbrowser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.LocalContextMenuRepresentation
import ai.rever.boss.plugin.ui.BossPopupAnchoring
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossPopup
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                    // Also asks the host to leave fullscreen, so closing a tab mid-video
                    // cannot leave its detached fullscreen window on screen.
                    val handle = state.releaseBrowserHandle()
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
    return url.takeIf { isWebUrl(it) }
}

/**
 * Whether a context-menu request should open a menu.
 *
 * The show-effect restarts whenever the tab re-enters composition, which would otherwise
 * re-open the menu for a request already honoured. [shownRequest] is the last request the
 * effect ran for — a request is consumed whether its run reached `show` or was cancelled
 * on the way there, because replaying a cancelled one opens a menu the user never asked
 * for, at a cursor they have since moved. Losing that menu costs another right-click;
 * replaying it puts UI on screen unbidden.
 *
 * Pure so the gate is testable; the composable applies the result.
 */
internal fun shouldOpenContextMenu(
    request: Int,
    shownRequest: Int
): Boolean = request > 0 && request != shownRequest

/**
 * Run [open] for [request] if the gate allows it, marking the request consumed either way.
 *
 * The consume-on-cancellation half is the point: it is what stops a run that was cut short
 * from being replayed when the tab re-enters composition. Structured so the invariant is
 * testable — it has already been got wrong in both directions.
 */
internal suspend fun runContextMenuRequest(
    request: Int,
    shownRequest: Int,
    markShown: (Int) -> Unit,
    open: suspend () -> Unit
) {
    if (!shouldOpenContextMenu(request, shownRequest)) return
    try {
        open()
    } finally {
        markShown(request)
    }
}

/**
 * Whether a dismissed menu should clear the pending target.
 *
 * Dismissal is asynchronous: showing a new menu hides the old one, which fires the old
 * menu's dismiss handler *after* the new target is in place. Only the request the closing
 * menu was built from may clear it.
 */
internal fun shouldClearContextMenuTarget(
    dismissedRequest: Int,
    currentRequest: Int
): Boolean = dismissedRequest == currentRequest

/**
 * Whether a page-supplied URL is safe for the menu to act on.
 *
 * An href reaches us straight from the document, so `javascript:` and `data:` are both
 * possible. Navigating one is not a new privilege — clicking the link would do the same —
 * but a menu entry runs it without the page's own affordance, and `data:` in particular
 * makes an attacker-chosen document look like it came from the menu. Web schemes only.
 */
internal fun isWebUrl(url: String): Boolean {
    val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
    return scheme == "http" || scheme == "https"
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
 * The dropdown state after forgetting [deletedUrl]: the remaining suggestions and where
 * the keyboard highlight belongs.
 *
 * The highlight follows the *entry* the user had chosen, not its index. Deleting a row
 * above the selection shifts everything below it up one, so keeping the old index would
 * silently move the highlight onto a different suggestion — and the next Enter would
 * navigate somewhere the user never selected. Easy to hit, because the ✕ appears on the
 * row under the pointer regardless of which row the arrow keys are on.
 *
 * Pure so the index arithmetic is testable; the composable just applies the result.
 */
internal fun suggestionsAfterDelete(
    suggestions: List<UrlHistoryEntry>,
    deletedUrl: String,
    selectedIndex: Int,
): Pair<List<UrlHistoryEntry>, Int> {
    val deletedIndex = suggestions.indexOfFirst { it.url == deletedUrl }
    val remaining = suggestions.filterNot { it.url == deletedUrl }

    val newIndex =
        when {
            remaining.isEmpty() || selectedIndex < 0 -> -1
            // Removed above the selection: everything below shifted up by one.
            deletedIndex in 0 until selectedIndex -> selectedIndex - 1
            else -> selectedIndex.coerceAtMost(remaining.lastIndex)
        }
    return remaining to newIndex
}

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
 * Tab hibernation (memory saver) configuration. A browser tab that has been in the background
 * past [currentIdleMs] disposes its live browser (freeing the Chromium process tree) and is
 * recreated from its current URL when shown again.
 *
 * **On by default**, opt out with `BOSS_TAB_HIBERNATION=false`. It shipped opt-in, which meant it
 * effectively never ran - see [enabled].
 */
internal object TabHibernation {
    /** The host system property carrying the resource tier's idle timeout. */
    internal const val HOST_IDLE_PROPERTY = "boss.browser.hibernationIdleMs"

    private val FALSY = setOf("0", "false", "no", "off")

    internal const val DEFAULT_IDLE_MS = 10 * 60 * 1000L

    /**
     * On by default, opt **out** via `BOSS_TAB_HIBERNATION=false`.
     *
     * It shipped opt-in, which meant that in practice it never ran: a 40-tab session kept 40 live
     * Chromium process trees for anyone who had not set an environment variable they had no reason
     * to know about. Reclaiming memory from tabs nobody is looking at is the whole point, and the
     * cost of being wrong is a reload on return, not lost work - the tab, its URL, its title and
     * its history all survive.
     */
    /** The host system property by which a tier or preference can switch hibernation off. */
    internal const val HOST_ENABLED_PROPERTY = "boss.browser.hibernationEnabled"

    /**
     * Whether hibernation runs at all, read per call like [currentIdleMs].
     *
     * Environment first, then the host. The PR argues that an environment variable nobody knows
     * about is not a real control surface; with the default flipped, that argument applies to the
     * *off* switch, and the host had no way to say "not on this machine" without an api release.
     */
    fun currentlyEnabled(
        fromEnvironment: String? = System.getenv("BOSS_TAB_HIBERNATION"),
        fromHost: String? = System.getProperty(HOST_ENABLED_PROPERTY),
    ): Boolean = resolveEnabled(fromEnvironment ?: fromHost)

    /**
     * How long a backgrounded tab waits before hibernating.
     *
     * Precedence: the operator's env var, then the host's resource tier, then ten minutes. The
     * tier sits below the env var for the same reason it does host-side - an explicit environment
     * setting is the outer authority and the escape hatch when a tier turns out to be wrong.
     *
     * The tier arrives as a system property rather than through plugin-api because plugins cannot
     * see host classes; this mirrors how `boss.power.onBattery` reaches the battery accelerant
     * below. Absent (older host, or a host that never published it) simply means the default.
     *
     * Read **per call**, not captured at class-load, exactly as the battery property is. As a
     * `val` this silently kept the default for the whole session if anything touched
     * [TabHibernation] before the host published - a load-order dependency with no error and no
     * log line, which is the failure mode this file is otherwise written against.
     */
    fun currentIdleMs(
        // Injectable for the same reason accelerate()'s thresholds are: reading the env var
        // straight from here made the test that pins this read fail on any machine that had
        // BOSS_TAB_HIBERNATION_IDLE_MS set, which is precisely the developer running it.
        fromEnvironment: String? = System.getenv("BOSS_TAB_HIBERNATION_IDLE_MS"),
    ): Long = resolveIdleMs(fromEnvironment, System.getProperty(HOST_IDLE_PROPERTY))

    /** Pure, so `TabHibernationConfigTest` can pin the opt-out without an environment. */
    internal fun resolveEnabled(raw: String?): Boolean {
        // Only an explicit falsy value turns it off. An unrecognized value must not read as
        // "off": someone writing BOSS_TAB_HIBERNATION=enabled plainly wants it enabled, and
        // silently disabling memory reclamation is the worst available reading of that.
        return raw?.trim()?.lowercase() !in FALSY
    }

    /** Pure, so the precedence is testable. Non-positive values are ignored, not obeyed. */
    internal fun resolveIdleMs(
        fromEnvironment: String?,
        fromHost: String?,
    ): Long =
        fromEnvironment?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?: fromHost?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?: DEFAULT_IDLE_MS

    // Memory-pressure-driven hibernation (roadmap Phase 3): when free system memory is scarce,
    // hibernate idle background tabs much sooner to give memory back while it's needed. Only ever
    // SHORTENS the wait for already-backgrounded tabs — the foreground tab never arms the timer
    // (see the DisposableEffect), so responsiveness is unaffected. Tunable, fails safe to the
    // baseline from currentIdleMs().
    private val pressureIdleMs: Long =
        System.getenv("BOSS_TAB_HIBERNATION_PRESSURE_IDLE_MS")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?: 60_000L
    // Named for what it now compares. The env var keeps its old spelling for compatibility, but
    // the field used to hold a *free*-pages fraction, which is the metric this change proved wrong.
    // Rejected, not clamped. coerceIn(0.0, 1.0) turned a nonsensical 2 into 1.0 - and since
    // availableFraction is itself clamped to that range, `available < 1.0` is true on essentially
    // every machine, so the accelerant stayed permanent and the clamp merely relabelled the bug it
    // claimed to fix. A value outside (0, 1) is not an instruction, so fall through to the default.
    //
    // Note this is stricter than the timeouts' `> 0` guard in one direction: 0 is rejected here
    // rather than accepted as "never accelerate". Turning the accelerant off is what
    // BOSS_TAB_HIBERNATION_PRESSURE_IDLE_MS equal to the baseline is for.
    private val pressureAvailableFraction: Double =
        System.getenv("BOSS_TAB_HIBERNATION_PRESSURE_FRACTION")?.trim()?.toDoubleOrNull()
            ?.takeIf { it > 0.0 && it < 1.0 } ?: 0.15

    // Battery-aware (roadmap Phase 2). On battery, hibernate idle background tabs sooner to save
    // power. The AC/battery signal is detected in the host (PowerSource) and published to the
    // boss.power.onBattery system property — read here with no dependency on the host module.
    // Gated behind the same BOSS_BATTERY_AWARE opt-in the host uses; off by default.
    internal val batteryAwareEnabled: Boolean =
        System.getenv("BOSS_BATTERY_AWARE")?.trim()?.lowercase() in listOf("1", "true", "yes", "on")
    private val batteryIdleMs: Long =
        System.getenv("BOSS_TAB_HIBERNATION_BATTERY_IDLE_MS")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?: (2 * 60 * 1000L)

    /**
     * The idle delay to use right now. Starts at [currentIdleMs] and takes the shortest of any
     * applicable accelerant: the memory-pressure delay when available system memory is below
     * [pressureAvailableFraction] of total, and the battery delay when running on battery. Only
     * ever shortens - never exceeds [currentIdleMs] - and fails safe to it on any read error.
     *
     * Re-evaluated periodically while a tab waits, not once when it backgrounds; see
     * [awaitIdleWindow].
     */
    fun effectiveIdleMs(): Long =
        accelerate(
            baseline = currentIdleMs(),
            availableFraction = runCatching { HibernationMemory.availableFraction() }.getOrNull(),
            onBattery = System.getProperty("boss.power.onBattery") == "true",
        )

    /**
     * Why a tab should not hibernate right now, if it should not.
     *
     * Separate states rather than one boolean, because the reason has to survive as far as the
     * log line and the caller's decision. [PLAYING_MEDIA] and [FULLSCREEN] currently get the same
     * treatment in [awaitQuiet] - both are transient and both are waited out - but they are not
     * interchangeable: conflating busy-ness into one flag produced a genuinely worse outcome than
     * not deferring at all, and the distinction is what lets [awaitQuiet] tell a changed reason
     * from a continuing one.
     */
    enum class BusyState(val skipReason: String) {
        /** Nothing in the way; hibernate. */
        IDLE("idle"),

        /** Audible playback. Transient by nature: wait, and it will stop. */
        PLAYING_MEDIA("tab still playing audio"),

        /**
         * Video is fullscreen in a detached host window. Known locally rather than probed:
         * the tab Composable is out of composition, so "backgrounded" says nothing about
         * whether the user is watching. Transient like [PLAYING_MEDIA], and waited out the
         * same way - hibernating here would dispose the handle out from under the window
         * the user is actually looking at.
         */
        FULLSCREEN("tab still in fullscreen"),
    }

    /**
     * The reason this tab should not hibernate right now, fullscreen taking precedence.
     *
     * Fullscreen is answered locally rather than by [busyState], because the JS probe runs
     * inside the document and the document knows nothing about which window the host is
     * rendering it into. Extracted from the hibernation job so the selection itself is
     * testable, not just the policy it feeds.
     */
    internal suspend fun busyStateFor(isInFullscreen: Boolean, handle: BrowserHandle?): BusyState =
        when {
            isInFullscreen -> BusyState.FULLSCREEN
            handle == null -> BusyState.IDLE
            else -> busyState(handle)
        }

    /**
     * Whether hibernating this tab right now would cut audible playback.
     *
     * **Media only, deliberately.** This also tried to detect unsaved typing, and three successive
     * attempts were each wrong in a new way: matching any changed input exempted every SPA with a
     * populated search box; adding a visibility check left autofilled credentials exempting a tab
     * for ~20 hours, because `defaultValue` reflects the `value=""` attribute that login forms do
     * not set, so an autofilled password is indistinguishable from a typed one. Meanwhile the case
     * that actually loses work - `contenteditable` drafts in Gmail, Slack, Notion - was never
     * covered by any of them, because the DOM does not answer "has a human typed here" by
     * enumeration.
     *
     * A page-load `input` listener setting a dirty flag would answer it properly, and is the right
     * follow-up; it needs an injection point at navigation, and getting that wrong fails toward
     * losing the work it is meant to protect. Not something to land at the end of a review series.
     *
     * So the shipped guarantee is narrow and true: **hibernation never cuts audio**, and a
     * backgrounded tab may be reloaded, which discards unsaved input the same way Chrome's own
     * memory saver does. That is documented in the README next to the opt-out.
     *
     * Done in JavaScript because `BrowserHandle` exposes no audio state; JxBrowser's
     * `browser.audio()` is not surfaced through plugin-api, and adding it means an api release,
     * then a host release, then this.
     *
     * **Known gaps**, all toward hibernating when perhaps it should not: only the top document is
     * visible, so cross-origin iframes are missed, and pure Web Audio playback has no media
     * element to find.
     *
     * Failure reads as [BusyState.IDLE], so a page that cannot run this still hibernates. The
     * opposite would let one broken evaluation exempt a tab for the rest of the session.
     */
    suspend fun busyState(handle: BrowserHandle): BusyState =
        try {
            withContext(Dispatchers.IO) {
                // The 2s bound is advisory, not a guarantee: withTimeoutOrNull can only abandon a
                // call that suspends, and if executeJavaScript blocks internally this IO thread
                // stays parked until the renderer answers. Hence Dispatchers.IO rather than the
                // caller's Main - a wedged renderer must not take the UI with it.
                busyStateFromScriptResult(
                    withTimeoutOrNull(BUSY_CHECK_TIMEOUT_MS) { handle.executeJavaScript(BUSY_SCRIPT) },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Throwable, not Exception: a plugin-classloader NoClassDefFoundError out of
            // executeJavaScript would otherwise escape and kill the job silently, which is the
            // failure mode this file is written against. effectiveIdleMs uses runCatching, which
            // is already Throwable-wide.
            BusyState.IDLE
        }

    /**
     * Waits for a backgrounded tab to become quiet. Returns the state it settled on:
     * [BusyState.IDLE] means hibernate now, anything else is the reason not to.
     *
     * Busy states are distinguished for their reason strings; both non-idle states get the same
     * treatment, because both are transient and both are worth waiting out:
     *
     *  - [BusyState.PLAYING_MEDIA] is transient, so it is worth waiting out. Rechecks back off
     *    from [MEDIA_RECHECK_MS] toward [MAX_RECHECK_MS] so a three-hour video does not cost a
     *    JS eval every 30 seconds for three hours, and gives up after [MAX_RECHECKS] - at which
     *    point the tab is **left alone**, not hibernated. Cutting audio is the thing being
     *    avoided; a bound on polling must not become a licence to do it anyway.
     *  - [BusyState.FULLSCREEN] rides the same path deliberately. Bailing out of the job entirely
     *    would be simpler, but nothing re-arms the timer while a tab stays backgrounded, so a
     *    fullscreen video watched once would exempt its tab from hibernation until the next tab
     *    switch. Waiting means the tab hibernates on its own once fullscreen ends, and the
     *    [MAX_RECHECKS] bound applies the same "leave it alone" ending.
     *
     * Pure but for the two callbacks, so the policy is testable without a browser.
     */
    suspend fun awaitQuiet(
        probe: suspend () -> BusyState,
        onWait: suspend (Long) -> Unit,
        maxRechecks: Int = MAX_RECHECKS,
    ): BusyState {
        var interval = MEDIA_RECHECK_MS
        var previous: BusyState? = null
        var rechecks = 0
        var total = 0
        while (true) {
            val busy = probe()
            if (busy == BusyState.IDLE) return BusyState.IDLE
            // Counted unconditionally, because the per-reason budget below resets. A tab that
            // alternates - toggling fullscreen on an audible video does exactly this - would
            // otherwise reset on every single iteration, never terminate, and never let the
            // interval climb past its first doubling: polling pinned at the 30s floor forever.
            if (total >= maxRechecks * 2) return busy
            total++
            // A changed reason starts a fresh wait rather than inheriting the old one's
            // position. The backoff and the budget both exist to bound the cost of probing an
            // audible page; carrying a five-minute interval and a spent budget over from a
            // finished fullscreen session would charge audio for a wait it never asked for -
            // and worse, could hand a newly audible tab an already-exhausted budget, which
            // reads as "left alone" and exempts it from hibernation for no reason.
            if (previous != null && busy != previous) {
                interval = MEDIA_RECHECK_MS
                rechecks = 0
            }
            previous = busy
            // Checked before sleeping, not after. Sleeping on the final attempt parked a live
            // coroutine for up to MAX_RECHECK_MS on a result already decided.
            if (rechecks == maxRechecks) return busy
            onWait(interval)
            rechecks++
            interval = (interval * 2).coerceAtMost(MAX_RECHECK_MS)
        }
    }

    /**
     * Maps the raw script result to a state.
     *
     * Separate and pure because it is the one link in this chain with no other coverage - the
     * policy is tested against a fake probe, and the script itself cannot be unit-tested without a
     * JS engine - and because its failure is silent and points the wrong way: anything unexpected
     * reads as [BusyState.IDLE], which hibernates a tab that may be mid-playback, with no error
     * and no log line. `executeJavaScript` returns `Any?`, so a wrapper type or a quoted JSON
     * string would otherwise collapse every branch. Normalising first is cheap insurance against a
     * marshalling change nobody would notice. Mirrors `middleClickUrlFromScriptResult` above.
     */
    internal fun busyStateFromScriptResult(result: Any?): BusyState =
        when (result?.toString()?.trim()?.trim('"')?.lowercase()) {
            "media" -> BusyState.PLAYING_MEDIA
            else -> BusyState.IDLE
        }

    private const val BUSY_CHECK_TIMEOUT_MS = 2_000L

    /** Shortest gap between rechecks of an audible tab. Doubles up to [MAX_RECHECK_MS]. */
    internal const val MEDIA_RECHECK_MS = 30_000L

    internal const val MAX_RECHECK_MS = 5 * 60_000L

    /**
     * After this many rechecks a still-audible tab is left alone rather than polled forever.
     *
     * With the backoff below that is roughly 3 hours of wall clock. A tab still playing then keeps
     * its process tree until the user visits it again, which is the deliberate trade: a bound on
     * polling must not become a licence to cut the audio.
     */
    internal const val MAX_RECHECKS = 40

    private const val BUSY_SCRIPT =
        "(function(){try{" +
            "return Array.prototype.slice.call(document.querySelectorAll('video,audio'))" +
            ".some(function(m){" +
            "return !m.paused && !m.ended && !m.muted && m.volume > 0 && m.currentTime > 0;" +
            "}) ? 'media' : '';" +
            "}catch(e){return '';}})()"

    /**
     * Sleeps until the tab has been idle long enough to hibernate, re-evaluating as it goes.
     *
     * The naive version - `delay(effectiveIdleMs())` - sampled memory **once**, at the moment the
     * tab backgrounded, and then slept on that answer for ten to thirty minutes. So the pressure
     * accelerant could only ever see pressure that already existed when you switched tabs, and
     * never the normal case: pressure that builds afterwards, caused by the other tabs. That was
     * invisible while the metric was broken, because the accelerant fired unconditionally on
     * macOS; with a correct reading it would have been close to inert.
     *
     * Sleeping in chunks and re-asking makes a tab that backgrounded under no pressure still
     * hibernate promptly once pressure arrives. `HibernationMemory` caches for 30s, so the
     * re-asking is nearly free.
     *
     * Pure but for the two callbacks, so the shortening behaviour is testable without a clock.
     */
    suspend fun awaitIdleWindow(
        idleMsNow: suspend () -> Long,
        sleep: suspend (Long) -> Unit,
        chunkMs: Long = PRESSURE_RECHECK_CHUNK_MS,
    ) {
        var waited = 0L
        while (true) {
            val target = idleMsNow()
            if (waited >= target) return
            val remaining = target - waited
            // Coarse early, fine near the deadline. A flat chunk woke every backgrounded tab every
            // 30s for its whole window - at 40 tabs on the Full tier, ~1.3 wakeups a second
            // sustained for half an hour, which is the same argument used against the 30s busy
            // loop and works against the battery accelerant a few lines above. Resolution only
            // matters as the deadline approaches.
            val chunk = minOf(remaining, maxOf(chunkMs, remaining / 4))
            sleep(chunk)
            waited += chunk
        }
    }

    /**
     * Floor on how often the idle target is re-evaluated while a tab waits.
     *
     * A floor, not a fixed cadence - [awaitIdleWindow] sleeps in coarser steps while the deadline
     * is far off. Deliberately shorter than `HibernationMemory.CACHE_TTL_MS` rather than equal to
     * it: equal meant the cached reading was always just-expired on the next wake, so the cache
     * could never hit.
     */
    internal const val PRESSURE_RECHECK_CHUNK_MS = 30_000L

    /**
     * Pure accelerant arithmetic, so the "only ever shortens" contract is testable.
     *
     * [availableFraction] of null means the reading failed. That must leave [baseline] alone: it
     * is "we could not measure", not "there is no memory". Reading it as pressure would hibernate
     * every backgrounded tab after a minute on any machine whose memory we cannot see.
     */
    internal fun accelerate(
        baseline: Long,
        availableFraction: Double?,
        onBattery: Boolean,
        // Thresholds as parameters, defaulted from the environment. Reading them straight from
        // class-load state made the tests non-hermetic: they would change behaviour on any machine
        // with a BOSS_TAB_HIBERNATION_* variable set, which is exactly the developer most likely
        // to run them.
        pressureThreshold: Double = pressureAvailableFraction,
        pressureDelayMs: Long = pressureIdleMs,
        batteryDelayMs: Long = batteryIdleMs,
        batteryAware: Boolean = batteryAwareEnabled,
    ): Long {
        var delay = baseline
        if (availableFraction != null && availableFraction < pressureThreshold) {
            delay = minOf(delay, pressureDelayMs)
        }
        if (batteryAware && onBattery) {
            delay = minOf(delay, batteryDelayMs)
        }
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

/**
 * How long to wait for the host's `onExitFullscreen` before assuming it was dropped.
 *
 * A real host exit completes well inside this; it is a lost-callback deadline, not a budget.
 */
internal const val FULLSCREEN_EXIT_FALLBACK_MS = 2_000L

/**
 * What asking the host to leave fullscreen told us about the handle.
 *
 * [DEAD] and [THREW] both mean "no callback is coming", but they justify opposite recoveries,
 * which is the whole reason they are not one `false`.
 */
internal enum class HostExitOutcome {
    /** A live handle took the request; a callback is expected. */
    ACCEPTED,

    /**
     * Null or `!isValid`. A handle in this state cannot still own a fullscreen view, so the
     * tab can be restored locally with nothing to collide with.
     */
    DEAD,

    /**
     * The call threw while `isValid` was still true. This is evidence of a wedged host, not of
     * a gone one - `executeJavaScript` throwing `NoClassDefFoundError` across the plugin
     * classloader is a live failure mode in this file - so restoring the tab here would be
     * composing a second parent for a view the host may still hold.
     */
    THREW,
}

/** What the fullscreen placeholder should say, driven by [FluckBrowserTabState]. */
internal enum class FullscreenExitPhase {
    /** Fullscreen is live and nobody has asked to leave it. */
    IDLE,

    /** An exit was requested and the host has not called back yet. */
    EXITING,

    /**
     * The host accepted two exit requests and never called back, so its view is presumed
     * still parented in the fullscreen window. See [FluckBrowserTabState.scheduleFullscreenExitFallback]
     * for why this does not restore the tab.
     */
    FAILED,
}

internal class FluckBrowserTabState {
    // private set, with adoptBrowserHandle/releaseBrowserHandle as the only writers. The
    // fullscreen flag below is only correct because *every* handle transition clears it;
    // a public setter would let the next `browserHandle = x` added anywhere reintroduce the
    // stale-placeholder bug silently, with no test failing. Structural, not conventional.
    var browserHandle: BrowserHandle? by mutableStateOf<BrowserHandle?>(null)
        private set
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

    // Written by callbacks registered once on the BrowserHandle (setContextMenuCallback,
    // setFullscreenHandler), so they MUST live here rather than in a remember slot.
    // A remember-scoped MutableState is discarded when the host drops the inactive tab's
    // Composable from composition; the callback lambda captured the *old* instance and
    // would keep writing to it, leaving the UI observing a state nobody updates. That is
    // exactly how right-click went dead after the first tab switch.
    var contextMenuInfo: BrowserContextMenuInfo? by mutableStateOf(null)
    // Bumped once per right-click. A counter rather than a boolean so two right-clicks in
    // a row are two distinct values: keying the show-effect on a boolean silently drops
    // the second request whenever the first menu's dismissal hasn't reset it yet.
    var contextMenuRequest: Int by mutableStateOf(0)
    // The request a menu has already been opened for. LaunchedEffect restarts when the
    // tab re-enters composition, which would otherwise re-open the last menu — visible
    // if the tab is switched away from with a menu still up.
    var shownContextMenuRequest: Int = 0

    // Do NOT shadow this with a remember-local (see PR #15): setFullscreenHandler is installed
    // once per handle, so a remembered flag is orphaned by the first tab switch and the host
    // callbacks go on writing into a state object nobody observes.
    var isInFullscreen: Boolean by mutableStateOf(false)
        private set
    var fullscreenExitPhase: FullscreenExitPhase by mutableStateOf(FullscreenExitPhase.IDLE)
        private set

    // Main-thread confined along with the two fullscreen fields above: the host callbacks are
    // marshalled through the Component's scope before they reach markFullscreen*, and
    // Dispatchers.Main is single-threaded and FIFO, so the epoch check inside the fallback
    // cannot interleave with a re-entry.
    private var fullscreenExitFallbackJob: Job? = null
    // Bumped on every fullscreen transition so a fallback armed for one session can recognise
    // that it woke into a different one and do nothing.
    private var fullscreenEpoch = 0

    /**
     * Takes ownership of a freshly created handle.
     *
     * Clears fullscreen state first: a replacement handle never reports `onExitFullscreen` for a
     * session it was not part of, so a stale `true` would strand the tab on the placeholder.
     */
    fun adoptBrowserHandle(handle: BrowserHandle) {
        browserHandle?.let { previous ->
            // Not just a dangling reference: an undisposed BrowserHandle keeps a whole
            // Chromium process tree alive. This is now the single chokepoint for handle
            // transitions, so it is the place to actually free it rather than log about it.
            println("[FluckBrowser] Adopting a handle over a live one; disposing the previous")
            disposeBrowserHandleOffThread(previous)
        }
        clearFullscreenState()
        browserHandle = handle
    }

    /**
     * Releases the current handle and returns it for disposal.
     *
     * Asking the host to leave fullscreen first is what keeps a detached fullscreen window from
     * outliving the tab that owned it. `requestExitFullscreen` is a non-blocking post, so this is
     * safe on the lifecycle thread and callers may dispose immediately afterwards; BossConsole#36
     * (merged) also detaches a matching fullscreen view from `BrowserHandle` disposal, which is
     * what covers the invalid-handle case this cannot reach.
     */
    fun releaseBrowserHandle(): BrowserHandle? {
        val handle = browserHandle
        if (isInFullscreen) {
            requestHostExitFullscreen(handle)
        }
        browserHandle = null
        clearFullscreenState()
        return handle
    }

    /**
     * Asks the host to leave fullscreen and arms the lost-callback self-heal in one step.
     *
     * Arming is not left to the caller on purpose. A call site that posted the request and
     * forgot the fallback would strand the phase at [FullscreenExitPhase.EXITING] forever: the
     * debounce below would swallow every subsequent click and no timer would exist to notice.
     * That is the same "correct by convention, silent when broken" shape that `private set` on
     * [browserHandle] exists to remove.
     *
     * Returns true when a live handle accepted the request. False is terminal in both
     * directions: either there was nothing to exit, or the handle is gone and the tab has
     * already been restored locally.
     */
    fun requestExitFullscreen(coroutineScope: CoroutineScope): Boolean {
        if (!isInFullscreen) return false
        // Debounce. A pending request already owns this exit, and re-posting it on every click
        // would only reset the phase the user is waiting to see change.
        if (fullscreenExitPhase == FullscreenExitPhase.EXITING) return false
        when (requestHostExitFullscreen(browserHandle)) {
            HostExitOutcome.DEAD -> {
                // Nothing can still own a fullscreen view, so the restored tab has nothing
                // to collide with.
                clearFullscreenState()
                return false
            }
            HostExitOutcome.THREW -> {
                // A live handle that rejects the call is the wedged case, so this goes
                // straight to the terminal state rather than spending two windows arriving
                // at it. Both recoveries are offered there.
                fullscreenExitPhase = FullscreenExitPhase.FAILED
                return false
            }
            HostExitOutcome.ACCEPTED -> Unit
        }
        fullscreenExitPhase = FullscreenExitPhase.EXITING
        scheduleFullscreenExitFallback(coroutineScope)
        return true
    }

    /**
     * The user's override for an exit that never completed. Only acts from
     * [FullscreenExitPhase.FAILED].
     *
     * The plugin cannot tell the two failure modes apart. `handle.isValid` is true whether the
     * host is genuinely wedged holding the view, or exited cleanly and lost the callback - and
     * `BrowserHandle` exposes no fullscreen query to ask with (only `setFullscreenHandler` and
     * `requestExitFullscreen`), so there is no discriminator to write. Guessing wrong in one
     * direction shows a blank tab; guessing wrong in the other costs the page entirely.
     *
     * A user looking at their own screen knows which it is. So the terminal state offers both
     * and lets them say, rather than dead-ending on a tab they can only close.
     */
    fun restoreTabFromFailedExit() {
        if (fullscreenExitPhase != FullscreenExitPhase.FAILED) return
        println("[FluckBrowser] Restoring tab from a failed fullscreen exit, at the user's request")
        clearFullscreenState()
    }

    /**
     * Self-heals an exit whose host callback never arrives: retry once, then stop.
     *
     * Deliberately does **not** restore the tab when the handle is still live. Clearing
     * `isInFullscreen` would drop to the `browserHandle != null` branch and compose
     * `browserHandle.Content()` for a native view the host still has parented in its fullscreen
     * window - one parent per view, so the likely result is a blank tab *and* the loss of the
     * only exit affordance. Holding the placeholder in [FullscreenExitPhase.FAILED] keeps the
     * retry available, which is worth more than a tab that looks restored and is not.
     *
     * [FullscreenExitPhase.FAILED] is not a dead end: see [restoreTabFromFailedExit] for why
     * the user gets the final say there.
     */
    private fun scheduleFullscreenExitFallback(coroutineScope: CoroutineScope) {
        if (fullscreenExitFallbackJob?.isActive == true) return
        val scheduledEpoch = fullscreenEpoch
        fullscreenExitFallbackJob =
            coroutineScope.launch {
                delay(FULLSCREEN_EXIT_FALLBACK_MS)
                if (!isInFullscreen || scheduledEpoch != fullscreenEpoch) return@launch

                println("[FluckBrowser] Fullscreen exit callback timed out; retrying host request")
                when (requestHostExitFullscreen(browserHandle)) {
                    HostExitOutcome.DEAD -> {
                        println("[FluckBrowser] Fullscreen exit retry found a dead handle; restoring tab")
                        // Cancels the coroutine running this line. Safe only because nothing
                        // suspends afterwards - do not add a delay or a suspending log below.
                        clearFullscreenState()
                        return@launch
                    }
                    HostExitOutcome.THREW -> {
                        println("[FluckBrowser] Fullscreen exit retry threw on a live handle; holding the placeholder")
                        fullscreenExitPhase = FullscreenExitPhase.FAILED
                        return@launch
                    }
                    HostExitOutcome.ACCEPTED -> Unit
                }
                delay(FULLSCREEN_EXIT_FALLBACK_MS)
                if (!isInFullscreen || scheduledEpoch != fullscreenEpoch) return@launch

                println("[FluckBrowser] Fullscreen exit timed out twice; holding the placeholder for retry")
                fullscreenExitPhase = FullscreenExitPhase.FAILED
            }
    }

    /** Idempotent: a duplicate host enter must not drop a fallback armed for the live session. */
    fun markFullscreenEntered() {
        // The invariant has to hold in both directions, not just on release. These callbacks are
        // marshalled asynchronously onto the Component scope, so a late or duplicate host enter
        // can land after hibernation or crash recovery already dropped the handle. Fullscreen
        // with no handle is unexitable: the placeholder's click finds nothing to ask, clears
        // itself, and the content `when` falls through every branch to render nothing at all.
        if (browserHandle == null) return
        if (isInFullscreen) return
        cancelFullscreenExitFallback()
        fullscreenEpoch++
        fullscreenExitPhase = FullscreenExitPhase.IDLE
        isInFullscreen = true
    }

    fun markFullscreenExited() {
        clearFullscreenState()
    }

    private fun clearFullscreenState() {
        cancelFullscreenExitFallback()
        fullscreenEpoch++
        fullscreenExitPhase = FullscreenExitPhase.IDLE
        isInFullscreen = false
    }

    private fun cancelFullscreenExitFallback() {
        fullscreenExitFallbackJob?.cancel()
        fullscreenExitFallbackJob = null
    }

    private fun requestHostExitFullscreen(handle: BrowserHandle?): HostExitOutcome {
        // Checked before the call, so a handle that is both invalid and throwing reads as DEAD.
        if (handle == null || !handle.isValid) return HostExitOutcome.DEAD
        return runCatching {
            handle.requestExitFullscreen()
            HostExitOutcome.ACCEPTED
        }.onFailure { error ->
            println("[FluckBrowser] Failed to request fullscreen exit: ${error.message}")
        }.getOrDefault(HostExitOutcome.THREW)
    }
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
    // browserHandle is read-only here - every transition goes through
    // hoistedState.adoptBrowserHandle/releaseBrowserHandle so fullscreen
    // state can never outlive the handle that owns it.
    val browserHandle by hoistedState::browserHandle
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
    var contextMenuInfo by hoistedState::contextMenuInfo
    var contextMenuRequest by hoistedState::contextMenuRequest

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

    // Context-menu state lives on hoistedState (see FluckBrowserTabState) — it is written
    // by the once-registered setContextMenuCallback.

    // Cached secrets for context menu (loaded when context menu is shown)
    var cachedSecrets by remember { mutableStateOf<List<SecretEntryData>>(emptyList()) }

    // Secret dialog state
    var showAllSecretsDialog by remember { mutableStateOf(false) }
    var showQuickCreateDialog by remember { mutableStateOf(false) }
    var quickCreateWebsitePrefill by remember { mutableStateOf("") }
    var allSecrets by remember { mutableStateOf<List<SecretEntryData>>(emptyList()) }

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
                hoistedState.adoptBrowserHandle(handle)
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
                    // runCatching like the other call sites: a callback can land during a
                    // dispose or renderer-crash race, and an exception here would escape
                    // into the host's event dispatch rather than being contained.
                    val committedUrl = runCatching { handle.getCurrentUrl() }.getOrDefault("")
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

                        // Save history when page finishes loading (home has no history
                        // entry). Reads the committed URL for the same reason the title
                        // listener does: the URL bar holds what the user is typing, so
                        // mid-edit it could look home-ish and skip the flush for a page
                        // that really did load. Only asked for on the finished transition
                        // — reading it on the starting one was a wasted call into the
                        // engine whose value was then discarded.
                        val committedUrl = runCatching { handle.getCurrentUrl() }.getOrDefault("")
                        if (!isHomeUrl(committedUrl)) {
                            coroutineScope.launch {
                                urlHistoryProvider?.saveHistory()
                            }
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

                // Set up context menu callback. Registered once per BrowserHandle, so it
                // writes to the hoisted state — see the note on FluckBrowserTabState.
                // JxBrowser invokes this on its own thread; hop to Main so the two writes
                // land in one frame and the effect below observes them together.
                handle.setContextMenuCallback { info ->
                    // coroutineScope is already Main-dispatched; `immediate` keeps a
                    // callback that already arrives on the EDT from taking a dispatch it
                    // doesn't need — the menu still reads the pointer position, so every
                    // hop between the click and the read is drift.
                    coroutineScope.launch(Dispatchers.Main.immediate) {
                        hoistedState.contextMenuInfo = info
                        hoistedState.contextMenuRequest++
                    }
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
                        // Marshalled onto the Component's scope, not applied inline: the host may
                        // call back from a CEF thread, and these two mutate main-thread-confined
                        // fullscreen state. Dispatchers.Main is FIFO, so a rapid exit-then-enter
                        // still lands in order.
                        onEnterFullscreen = {
                            coroutineScope.launch { hoistedState.markFullscreenEntered() }
                        },
                        onExitFullscreen = {
                            coroutineScope.launch { hoistedState.markFullscreenExited() }
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
                        //
                        // Fullscreen belongs to the handle, not the tab: the replacement
                        // never reports onExitFullscreen for a session it wasn't part of,
                        // so a stale `true` would strand the tab on FullscreenPlaceholder
                        // with an exit button that can't do anything. releaseBrowserHandle
                        // makes that structural rather than a line to remember here.
                        hoistedState.releaseBrowserHandle()
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
                        hoistedState.releaseBrowserHandle()
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
    // Tab hibernation (memory saver), on by default. When a tab is backgrounded (this
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
            if (TabHibernation.currentlyEnabled() && browserHandle != null) {
                hoistedState.hibernationJob = coroutineScope.launch {
                    // Re-evaluated in chunks rather than slept once: pressure usually develops
                    // after a tab backgrounds, and a single up-front sample cannot see it. Each
                    // read is off the UI thread - coroutineScope is Dispatchers.Main, and on macOS
                    // this forks vm_stat and waits up to 5s for it, which on the EDT is a
                    // five-second frozen UI at the moment the user is switching tabs.
                    TabHibernation.awaitIdleWindow(
                        idleMsNow = { withContext(Dispatchers.IO) { TabHibernation.effectiveIdleMs() } },
                        sleep = { delay(it) },
                    )
                    // Audible and fullscreen tabs are waited out rather than cut off mid-play.
                    val busy =
                        TabHibernation.awaitQuiet(
                            probe = {
                                TabHibernation.busyStateFor(hoistedState.isInFullscreen, browserHandle)
                            },
                            onWait = { delay(it) },
                        )
                    if (busy != TabHibernation.BusyState.IDLE) {
                        // Logged because this whole feature is an argument against silent
                        // outcomes, and "my 40 tabs never released memory" is otherwise
                        // indistinguishable in the field from "hibernation is broken".
                        println("[FluckBrowser] Hibernation skipped: ${busy.skipReason}")
                        return@launch
                    }
                    println("[FluckBrowser] Hibernating idle tab to release its renderer")
                    val handle = hoistedState.releaseBrowserHandle()
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
    // remember so the handler keeps one identity across recompositions: the ✕'s gesture
    // detector is started once per row and would otherwise hold whichever instance existed
    // when it started, including a stale urlHistoryProvider.
    val onDeleteSuggestion: (UrlHistoryEntry) -> Unit =
        remember(urlHistoryProvider) {
            { entry: UrlHistoryEntry ->
                // runCatching for the same reason getCurrentUrl has it: this runs inside a
                // pointer callback, and a host whose provider predates deleteUrl would
                // throw past it into the host's event dispatch.
                runCatching { urlHistoryProvider?.deleteUrl(entry.url) }
                val (remaining, newIndex) =
                    suggestionsAfterDelete(urlSuggestions, entry.url, selectedDropdownIndex)
                urlSuggestions = remaining
                showUrlSuggestions = remaining.isNotEmpty()
                selectedDropdownIndex = newIndex
                autocompleteSuggestion = null
                // No saveHistory() here: the host persists a deletion itself, and holding
                // shift+Backspace on a full dropdown would otherwise launch a save per key
                // repeat, all writing the same file.
            }
        }

    BossTheme {
    // Route Compose's built-in text context menus (the URL bar's right-click Cut/Copy/Paste)
    // through a JPopupMenu instead of a lightweight Compose popup, which was cropped at the
    // browser's rendering area. Same mechanism the page's own context menu already uses, so the
    // two now match - see SwingContextMenuRepresentation.
    CompositionLocalProvider(LocalContextMenuRepresentation provides SwingContextMenuRepresentation) {
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
                    // distinctBy: the dropdown keys its rows by URL, and a duplicate key
                    // is fatal to a LazyColumn. The current host can't produce one, but
                    // the list is host-supplied data and a provider that later merges
                    // sources (history + bookmarks + open tabs) is exactly the shape that
                    // would. Deleting already removes every copy, so this loses nothing.
                    val suggestions =
                        urlHistoryProvider.getSuggestions(newValue.text, limit = 10)
                            .distinctBy { it.url }

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
                hoistedState.isInFullscreen -> {
                    // Fullscreen placeholder - browser is displayed in a separate fullscreen window
                    FullscreenPlaceholder(
                        phase = hoistedState.fullscreenExitPhase,
                        onExitClick = { hoistedState.requestExitFullscreen(coroutineScope) },
                        onRestoreAnyway = { hoistedState.restoreTabFromFailedExit() },
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

            // A heavyweight popup is a window: it outlives this Composable unless something
            // takes it down. AWT's grab means a *mouse* click elsewhere dismisses it on the
            // way, but a keyboard tab switch, a tab close, or crash-recovery swapping the
            // content underneath leaves it on screen — with items closing over the previous
            // tab's browserHandle, so Reload would fire into a disposed browser.
            DisposableEffect(Unit) {
                onDispose { SwingContextMenu.hide() }
            }

            // Context menu (Swing-based for hardware accelerated browser compatibility).
            // Keyed on the request counter, not on a visibility flag, so every right-click
            // re-opens the menu — including one fired while the previous menu is still up.
            LaunchedEffect(contextMenuRequest) {
                // Snapshot both up front: loading secrets below suspends, and a second
                // right-click landing meanwhile must not swap the target out from under
                // the menu we are building.
                val requestId = contextMenuRequest
                val menuInfo = contextMenuInfo
                // Read the pointer before the request is eligible to be consumed. Inside the
                // run, a null here (headless, or the pointer on no screen device) would burn
                // the request without ever showing anything — and unlike a cancelled run,
                // this one never got as far as wanting to draw, so there is nothing to
                // protect the user from replaying.
                val mouseLocation = java.awt.MouseInfo.getPointerInfo()?.location
                if (menuInfo != null && mouseLocation != null) {
                    runContextMenuRequest(
                        request = requestId,
                        shownRequest = hoistedState.shownContextMenuRequest,
                        markShown = { hoistedState.shownContextMenuRequest = it }
                    ) {
                        // Load secrets if we have formFieldInfo and a provider
                        val secretsForMenu: List<SecretEntryData> = if (menuInfo.formFieldInfo != null && secretDataProvider != null) {
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
                            info = menuInfo,
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
                                if (shouldClearContextMenuTarget(
                                        dismissedRequest = requestId,
                                        currentRequest = hoistedState.contextMenuRequest
                                    )
                                ) {
                                    hoistedState.contextMenuInfo = null
                                }
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

        // Floating URL autocomplete dropdown overlay (positioned below toolbar).
        //
        // The outer Box is the ANCHOR and keeps the placement this always had; BossPopup measures it
        // and, under HARDWARE_ACCELERATED, draws the list in an always-on-top window at that spot.
        // Drawn in place the list was invisible the moment it extended over the page, because
        // Chromium composites its own native window over the Compose scene - so the suggestions were
        // painted behind the content they overlap.
        //
        // AnchorBounds, not Cursor: the user is typing, so the pointer may be anywhere on screen and
        // the list must follow the URL bar instead. focusable = false for the same reason - the field
        // has to keep focus for typing to keep filtering, and it owns the arrow keys and Escape.
        if (showUrlSuggestions && urlSuggestions.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .align(Alignment.TopCenter)
                    .offset(y = 38.dp),
            ) {
                BossPopup(
                    onDismissRequest = { showUrlSuggestions = false },
                    focusable = false,
                    anchoring = BossPopupAnchoring.AnchorBounds,
                ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                elevation = 8.dp,
                backgroundColor = MaterialTheme.colors.surface
            ) {
                LazyColumn(
                    state = dropdownListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    // Keyed by URL so per-row state (the hover source below) follows the
                    // entry rather than the slot — without it, deleting a row hands its
                    // hover state to whichever suggestion shifts up into its place.
                    itemsIndexed(urlSuggestions, key = { _, entry -> entry.url }) { index, entry ->
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
                                // clickable's own interaction source reports hover, so no
                                // separate .hoverable() is needed.
                                .clickable(
                                    interactionSource = rowInteractionSource,
                                    indication = LocalIndication.current
                                ) {
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
                                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
                            //
                            // The slot is always laid out, even when the icon is hidden:
                            // adding it on hover would re-truncate the title and URL under
                            // the pointer. The whole 28.dp box is the target, not the
                            // 16.dp glyph — this deletes, and it sits next to a row that
                            // navigates on click.
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isRowHovered || index == selectedDropdownIndex) {
                                    @OptIn(ExperimentalComposeUiApi::class)
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            // A pointer handler rather than .clickable:
                                            // clickable is focusable, so clicking ✕ pulled
                                            // focus out of the URL bar and onFocusLost then
                                            // closed the whole dropdown 200ms later — you
                                            // could delete one entry, then had to retype to
                                            // delete a second. Primary button only: this
                                            // deletes, so a right-click reaching for a
                                            // context menu must not fire it.
                                            .onPointerEvent(PointerEventType.Release) { event ->
                                                if (event.button == PointerButton.Primary) {
                                                    event.changes.forEach { it.consume() }
                                                    onDeleteSuggestion(entry)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Remove from history",
                                            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
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
    } // End context-menu representation
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
internal fun buildContextMenuItems(
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
            text = "Cut",
            onClick = { browserHandle?.cut() }
        ))

        add(ContextMenuItem(
            text = "Copy",
            onClick = { browserHandle?.copySelection() }
        ))

        add(ContextMenuItem(
            text = "Paste",
            onClick = { browserHandle?.paste() }
        ))

        add(ContextMenuItem(
            text = "Select All",
            onClick = { browserHandle?.selectAll() }
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
            // Right-clicked on a link. Only the two entries that *act* on the href are
            // scheme-gated (same rule as a middle click); copying it is inert, and
            // refusing to copy a javascript: href would just be unhelpful.
            if (isWebUrl(linkUrl)) {
                add(ContextMenuItem(
                    text = "Open Link",
                    onClick = { onNavigate(linkUrl) }
                ))

                add(ContextMenuItem(
                    text = "Open Link in New Tab",
                    onClick = { onOpenInNewTab(linkUrl) }
                ))
            }

            add(ContextMenuItem(
                text = "Copy Link URL",
                onClick = { copyToClipboard(linkUrl) }
            ))
        }

        // Always offered, including on a link. Copying the page you are on is the one
        // entry whose availability shouldn't depend on what the pointer happened to be
        // over — and for a mailto:/javascript: href, where the open entries are gated
        // away, it is otherwise the only thing the menu could do and doesn't.
        add(ContextMenuItem(
            text = "Copy Page URL",
            onClick = {
                info?.pageUrl?.let { copyToClipboard(it) }
            }
        ))

        // Image actions, when the click landed on one. Independent of the link
        // branch above: images are routinely wrapped in an anchor, and both sets
        // of actions are meaningful there.
        val imageUrl = info?.imageUrl
        if (info?.hasImage == true && !imageUrl.isNullOrEmpty()) {
            add(ContextMenuItem(isDivider = true))

            // Same rule as the link entries: an image src is page-supplied, so only the
            // entry that opens it is gated. Copying is inert.
            if (isWebUrl(imageUrl)) {
                add(ContextMenuItem(
                    text = "Open Image in New Tab",
                    onClick = { onOpenInNewTab(imageUrl) }
                ))
            }

            add(ContextMenuItem(
                text = "Copy Image URL",
                onClick = { copyToClipboard(imageUrl) }
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
            // A lightweight popup paints into the Swing layer, which sits *behind* the
            // hardware-accelerated browser surface — the menu would be invisible over
            // page content. The host sets this globally, but the plugin cannot assume
            // the host it is loaded into did.
            isLightWeightPopupEnabled = false
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

        // Find the window to use as invoker. It must be the window that was actually
        // right-clicked: with several BOSS windows open (or focus sitting on a detached
        // browser window), the focused one need not contain the click, and the popup
        // would then be positioned against the wrong origin. Only frames and dialogs
        // are candidates — Window.getWindows() also returns the heavyweight windows
        // Swing creates for popups, including the menu we may be replacing.
        val clickPoint = java.awt.Point(screenX, screenY)
        val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        val candidates =
            Window.getWindows()
                .filter { it is java.awt.Frame || it is java.awt.Dialog }
                .filter { it.isShowing && it.bounds.contains(clickPoint) }
        val smallestFirst =
            compareBy<Window> { it.bounds.width.toLong() * it.bounds.height }
        val targetWindow: Window? =
            focusedWindow
                ?.takeIf { it is java.awt.Frame || it is java.awt.Dialog }
                ?.takeIf { it.isShowing && it.bounds.contains(clickPoint) }
                // getWindows() is not in z-order, and smallest-area is only a proxy for
                // topmost — it inverts when a fullscreen browser window covers a smaller
                // main frame. A heavyweight popup is owned by its invoker, so choosing the
                // window underneath would paint the menu behind the one on top. Active
                // first, then smallest: isActive is also true for every window the active
                // one owns, so a dialog over its owner leaves both active and the array
                // order would otherwise decide.
                ?: candidates.filter { it.isActive }.minWithOrNull(smallestFirst)
                ?: candidates.minWithOrNull(smallestFirst)
                // Deliberately not falling back to focusedWindow here: it is only reached
                // when focus sits outside the click, and it bypasses the frame/dialog
                // filter above — handing the invoker role to a heavyweight popup window is
                // exactly what that filter exists to prevent. The screen-location branch
                // below is the safer last resort.

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

    BossDialog(onDismissRequest = onDismiss) {
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

    BossDialog(onDismissRequest = onDismiss) {
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
private fun FullscreenPlaceholder(
    phase: FullscreenExitPhase,
    onExitClick: () -> Unit,
    onRestoreAnyway: () -> Unit,
) {
    // The exit round trip can take up to two FULLSCREEN_EXIT_FALLBACK_MS windows, which is long
    // enough that an unchanged placeholder reads as a broken one and the user keeps clicking.
    val (title, hint) = when (phase) {
        FullscreenExitPhase.IDLE ->
            "Tab is in fullscreen mode" to "Click here or press ESC to exit fullscreen"
        FullscreenExitPhase.EXITING ->
            "Exiting fullscreen..." to "Waiting for the browser window to close"
        FullscreenExitPhase.FAILED ->
            "Couldn't exit fullscreen" to
                "The browser window may still be open. Try again, or restore this tab if it has already closed."
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.BackgroundColor)
            // Only the idle state takes a whole-surface click. While EXITING every click is
            // swallowed by the debounce, and in FAILED the two outcomes differ enough that
            // "anywhere" is not an unambiguous choice - both get explicit affordances instead.
            .then(
                if (phase == FullscreenExitPhase.IDLE) {
                    Modifier.clickable { onExitClick() }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (phase == FullscreenExitPhase.EXITING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = BossThemeColors.TextSecondary,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Fullscreen,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = BossThemeColors.TextSecondary
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.h6,
                color = BossThemeColors.TextPrimary
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.body2,
                color = BossThemeColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp)
            )
            if (phase == FullscreenExitPhase.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onExitClick) {
                        Text("Try again")
                    }
                    // Deliberately the quieter of the two: it is the one that can leave a
                    // browser view with two parents if the user reads the situation wrong.
                    TextButton(onClick = onRestoreAnyway) {
                        Text("Restore tab anyway")
                    }
                }
            }
        }
    }
}
