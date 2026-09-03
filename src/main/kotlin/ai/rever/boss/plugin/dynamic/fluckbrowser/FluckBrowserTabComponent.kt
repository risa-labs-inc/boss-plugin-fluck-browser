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
import ai.rever.boss.plugin.api.LocalIsPanelActive
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.ScreenCaptureProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
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
import ai.rever.boss.plugin.dynamic.fluckbrowser.menu.NativeContextMenu
import ai.rever.boss.plugin.dynamic.fluckbrowser.menu.NativeMenuNode
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.channels.Channel
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
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import kotlin.math.abs

/**
 * Keeps [AddressBarFocusRegistry] told about this tab's address bar, for as long as it is composed.
 *
 * Its own composable purely to scope the invalidation: reading `LocalIsPanelActive.current` in
 * `FluckBrowserTabContent`'s body would subscribe that entire 7000-line function to panel-active
 * changes, so every click between splits would recompose the whole tab. Here it recomposes this.
 *
 * @param onFocus focuses and selects the field. Invoked from the host's key dispatch on the UI
 *   thread, NOT from composition, so it may touch state directly.
 */
@Composable
private fun AddressBarRegistration(
    tabId: String,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
) {
    // The effect keys on (tabId, windowId, panelActive) but CAPTURES onFocus, which is a fresh
    // lambda every recomposition. Safe today only because everything that lambda touches is a
    // remembered MutableState or lives on hoistedState - an argument about the caller, not about
    // this code. One line makes the effect independent of it.
    val currentOnFocus by rememberUpdatedState(onFocus)
    // A plain call, not a snapshot state read, and used as a DisposableEffect key: a window id
    // that changed WITHOUT a recomposition would leave a stale entry. Safe only because a tab
    // changing windows rebuilds this composition (the same fact the registry's identity keying is
    // built on), which is where that assumption is cashed. getWindowId() is a field read
    // host-side, so calling it per recomposition costs nothing worth remembering.
    val windowId = LocalWindowIdProvider.current?.getWindowId()
    val panelActive = LocalIsPanelActive.current

    if (windowId == null) {
        // Say so once per reason, rather than leaving this tab silently unable to answer Cmd+L.
        // An effect, not a bare call: a composable body re-runs on every recomposition.
        // Unit, not windowId: it is statically null in this branch, so keying on it would imply
        // a variation that cannot happen.
        DisposableEffect(Unit) {
            AddressBarFocusRegistry.noteUnregisterable(tabId, "the host reported no window id")
            onDispose {}
        }
        return
    }

    // Not gated on a non-blank tabId. The registry ranks on window, panel and composition order
    // and never reads the id for anything but a log line, so refusing an id-less tab would cost
    // it Cmd+L in exchange for nothing.
    DisposableEffect(tabId, windowId, panelActive) {
        val token =
            AddressBarFocusRegistry.register(
                tabId = tabId,
                windowId = windowId,
                panelActive = panelActive,
            ) {
                // Not wrapped in runCatching: requestFocus throws when the requester is not
                // attached to a node, and the registry's own catch turns that into the `false`
                // its caller reads. Catching it here as well made that false unreachable in
                // production and left the failure silent.
                //
                // Registered is very nearly the same as focusable here, because BrowserToolbar is
                // composed UNCONDITIONALLY alongside this effect - fullscreen and the boot spinner
                // replace only the weighted Box below the toolbar, not the toolbar itself. What is
                // left is attach timing (the effect runs before the field is laid out on the first
                // frame) and any future change that gates the toolbar, which is why the throw is
                // caught rather than assumed away.
                focusRequester.requestFocus()
                currentOnFocus()
            }
        onDispose { AddressBarFocusRegistry.unregister(token) }
    }
}

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
    // (e.g. error = a stale message, urlBarText = initialUrl) and never recovered.
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
                    // Off the UI thread so closing a tab never hitches the app, and awaiting
                    // whatever was still running against the handle - see [ReleasedHandle].
                    disposeReleasedHandle(state.releaseBrowserHandle())
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
 * The URL of the page the user is looking at, as opposed to [draft] - whatever is currently in
 * the URL bar.
 *
 * The affordances that act on "the page I am on" (copy link, bookmark) must not act on a
 * half-typed string. `urlBarText` is not a loaded-URL field - it is an editable text box that
 * merely happens to hold the loaded URL most of the time - so [loaded] is tracked separately, off
 * the navigation listener, which reports the committed URL whether or not the box is being
 * edited.
 *
 * **No "is the user editing" flag.** An earlier shape took one, and it was not a sound gate:
 * `onFocusLost` clears editing state after 200ms WITHOUT restoring the box, so typing `htt` and
 * then clicking into the page leaves a stale draft with editing already false - the exact case the
 * flag was supposed to exclude. Preferring [loaded] unconditionally has no downside to trade
 * against that: when nothing is being edited the two agree anyway, except in the window where
 * `onNavigate` optimistically writes the resolved URL into the bar before the load commits, and
 * there the page the user is on is still the old one.
 *
 * Falls back to [draft] when [loaded] is blank, which is home (see [isHomeUrl] - `about:blank`
 * fires no navigation events, so home is represented here as "nothing loaded") and the state
 * before a brand-new tab's first navigation commits.
 */
internal fun visiblePageUrl(
    draft: String,
    loaded: String,
): String = if (loaded.isNotBlank()) loaded else draft

/** What the starting surface says for the first stretch of a boot. */
internal const val INITIALIZING_MESSAGE = "Initializing browser..."

/**
 * What it says once the boot has outlasted [BROWSER_CREATION_TIMEOUT_MS] and is *still running*.
 *
 * A cold first-install boot spawns the whole Chromium process tree, and on a fresh Windows machine
 * that can outlast the watchdog. That used to be reported as
 * "Browser initialization timed out. The browser engine may not be available in this environment.",
 * which described a failure that had not happened: the creation runs on the never-cancelled
 * browserCreationScope, and the late-adoption nudge picks its result up whenever it lands. Saying
 * so keeps the tab honest and keeps Retry / Reset Tab on screen for a boot that really is wedged.
 */
internal const val SLOW_BOOT_MESSAGE = "Still starting the browser engine - this is taking longer than usual."

/**
 * What the starting surface says once a boot has outlasted [BROWSER_CREATION_GIVE_UP_MS].
 *
 * The counterpart to [SLOW_BOOT_MESSAGE], and the reason a slow boot is not simply waited on
 * forever. "Taking longer than usual" is true of a cold first-install boot; it is not a useful
 * thing to say to somebody whose install has no Chromium binaries at all, where the old
 * "may not be available in this environment" wording was exactly right. A boot that has run this
 * long is no longer plausibly slow, so the tab says so - while keeping the deferred cached and the
 * late-adoption nudge armed, so a boot that does eventually land is still adopted in place.
 */
internal const val WEDGED_BOOT_MESSAGE =
    "The browser engine is not responding. It may not be available in this environment."

/** What the starting surface says while the tab rebuilds a browser that crashed. */
internal const val RECOVERING_MESSAGE = "Browser crashed. Recovering..."

/** How long a single boot attempt may run before the tab starts saying it is slow. */
internal const val BROWSER_CREATION_TIMEOUT_MS = 20_000L

/**
 * How long it may run before the tab stops calling it slow and calls it broken.
 *
 * Generous on purpose: everything up to here is a boot that might still land, and the whole point
 * of this change is not to accuse one of failing. Past it, a spinner is no longer informative.
 */
internal const val BROWSER_CREATION_GIVE_UP_MS = 120_000L

/**
 * Which surface a browser tab shows, given the state it is in.
 *
 * Pure, and extracted for one reason: the ordering IS the bug that shipped. `error` was checked
 * first and doubled as the "still starting" state, so a tab with a live, loading browser rendered
 * a warning triangle over it - ahead of fullscreen, the dashboard and the browser view alike.
 * [BrowserSurfaceTest] pins the two properties that were violated: a live handle is never
 * [BrowserSurface.ERROR], and no handle with no error is never nothing at all.
 */
internal enum class BrowserSurface {
    /** A real failure, with Retry / Reset Tab. */
    ERROR,

    /** The view lives in the host's fullscreen window; the tab shows a placeholder. */
    FULLSCREEN,

    /** No handle yet and nothing has failed: a spinner, with Retry / Reset Tab for a wedged boot. */
    STARTING,

    /** Home (blank / about:blank), rendered by the host's dashboard provider. */
    DASHBOARD,

    /** Web content. */
    BROWSER,
}

internal fun browserSurfaceFor(
    error: String?,
    isInFullscreen: Boolean,
    hasHandle: Boolean,
    showDashboard: Boolean,
    hasDashboardProvider: Boolean,
): BrowserSurface =
    when {
        error != null -> BrowserSurface.ERROR
        // Ahead of BOTH the fullscreen placeholder and the dashboard, and for the same reason in
        // each case: those surfaces act through a handle. The dashboard's links navigate through
        // it, so offering them before one exists would swallow the first click; the fullscreen
        // placeholder's only control asks the host to leave a fullscreen session that a handle
        // must own. Fullscreen with no handle is unreachable today because releaseBrowserHandle
        // clears the fullscreen state - but that is a fact about a different function, and
        // depending on it here is what makes an "unreachable" state a bug later. A spinner that
        // resolves into the page beats a placeholder whose button cannot do anything.
        //
        // It also covers the gap a hibernation wake opens: the old `when` had no branch for
        // "no handle, no error" and rendered an empty pane until the replacement browser arrived.
        !hasHandle -> BrowserSurface.STARTING
        isInFullscreen -> BrowserSurface.FULLSCREEN
        showDashboard && hasDashboardProvider -> BrowserSurface.DASHBOARD
        else -> BrowserSurface.BROWSER
    }

/** What the status surface shows: the text, and whether it is a spinner or a warning. */
internal data class BrowserStatus(
    val message: String,
    val isLoading: Boolean,
)

/**
 * Resolve the status surface's content from the same two fields [browserSurfaceFor] reads.
 *
 * Together they are the whole of the separation this file needed: `error` non-null is a failure and
 * shows a warning, `error` null is a boot still running and shows a spinner over [initMessage].
 * Deriving the message here rather than per branch is what removes the last place the two could
 * disagree - a `?:` in the error branch would have rendered "Initializing browser..." under a
 * warning triangle, which is the confusion the whole change exists to remove.
 */
internal fun browserStatusFor(
    error: String?,
    initMessage: String,
): BrowserStatus =
    if (error != null) {
        BrowserStatus(message = error, isLoading = false)
    } else {
        BrowserStatus(message = initMessage, isLoading = true)
    }

/**
 * The state a creation attempt leaves behind. Both fields are always set: a partial answer would
 * mean the next reader has to know which of them this outcome participates in, and the version of
 * this type that left [initMessage] nullable had one of its three call sites quietly ignore it.
 */
internal data class BootState(
    val error: String?,
    val initMessage: String,
)

/** What a creation attempt turned out to be. */
internal enum class BootOutcome {
    /** A handle came back and the tab took it. */
    ADOPTED,

    /** The creation finished without producing a browser. */
    FAILED,

    /** Past the first deadline and still running. Not a failure - see [SLOW_BOOT_MESSAGE]. */
    SLOW,

    /** Past the give-up deadline and still running - see [WEDGED_BOOT_MESSAGE]. */
    WEDGED,
}

/**
 * Map a creation attempt's outcome onto tab state.
 *
 * An enum rather than the pair of booleans this started as, because the interesting distinction is
 * not "completed or not" but how long a not-completed boot has had. [BootOutcome.SLOW] must not set
 * [BootState.error]; [BootOutcome.WEDGED] must, or an install with no engine at all shows a spinner
 * forever and never says why.
 *
 * Every outcome except [BootOutcome.SLOW] resets [BootState.initMessage]: the notices written on
 * the way here ([SLOW_BOOT_MESSAGE], [RECOVERING_MESSAGE]) are hoisted, so without the reset the
 * *next* time this tab has no handle - a hibernation wake, say - its spinner would still be
 * explaining a boot that finished long ago.
 */
internal fun bootStateFor(outcome: BootOutcome): BootState =
    when (outcome) {
        BootOutcome.ADOPTED -> BootState(error = null, initMessage = INITIALIZING_MESSAGE)
        BootOutcome.FAILED ->
            BootState(
                error = "Failed to create browser instance. The browser engine may not be available.",
                initMessage = INITIALIZING_MESSAGE,
            )
        BootOutcome.SLOW -> BootState(error = null, initMessage = SLOW_BOOT_MESSAGE)
        BootOutcome.WEDGED -> BootState(error = WEDGED_BOOT_MESSAGE, initMessage = INITIALIZING_MESSAGE)
    }

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
     * log line and the caller's decision. Every non-idle state gets the same treatment in
     * [awaitQuiet] - all are waited out - but they are not interchangeable: conflating busy-ness
     * into one flag produced a genuinely worse outcome than not deferring at all, and the
     * distinction is what lets [awaitQuiet] tell a changed reason from a continuing one.
     *
     * They are not all transient in the same sense, and the difference matters at the bound.
     * Playback and fullscreen end on their own within minutes. A call does not: it routinely
     * outlives [MAX_RECHECKS], so a popped-out tab reaches the "left alone" ending rather than
     * the "waited out" one, and keeps its process tree until the user visits it again. That is
     * the deliberate trade already written into [MAX_RECHECKS] - a bound on polling must not
     * become a licence to cut the call.
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

        /**
         * A Picture-in-Picture window is on screen showing this tab's video.
         *
         * Hibernating disposes the handle, which takes the pop-out with it - and a pop-out is by
         * definition on a backgrounded tab, so without this the idle timer arms the moment the
         * feature does its job and kills the call it just rescued. Waited out like the other
         * non-idle states, though a call - unlike playback - routinely outlives [MAX_RECHECKS]
         * and reaches the "left alone" ending rather than the "waited out" one.
         *
         * Probed rather than known locally, unlike [FULLSCREEN]: the pop-out belongs to the
         * document (`document.pictureInPictureElement`), and this is true whether the host popped
         * it out on a tab switch or the user asked for it from the context menu - the second case
         * was already being cut off before any of this existed.
         *
         * **Top document only**, like every other probe here: a pop-out opened from inside a
         * cross-origin iframe is invisible to it, and that tab hibernates. Same known gap the
         * media probe carries, and it fails toward hibernating rather than exempting.
         */
        PICTURE_IN_PICTURE("tab still in picture-in-picture"),

        /**
         * The host is showing this backgrounded tab somewhere - it is popped out.
         *
         * BossConsole's pop-out no longer uses either Picture-in-Picture API. It reparents the
         * tab's **real rendering surface** into a floating window (BossConsole#282), because a
         * hidden tab cannot be made to render a live call: its DOM never mounts new elements and
         * Meet's SFU will not even forward video for tiles the client is not drawing. So
         * [PICTURE_IN_PICTURE] probes for a window that is no longer opened, and this state is
         * what actually protects a popped-out call from the idle timer.
         *
         * **This is now the fallback, not the primary.** [busyStateFor] asks the host first
         * through `BrowserHandle.isPoppedOut`, which is exact; this probe only answers for a host
         * too old to have that member.
         *
         * The pairing with a playing `<video>` is a bound on the damage a wrong premise could do,
         * not a description of a call. Measured on this build (2026-08-30): a backgrounded tab
         * really does report `hidden` and an active one `visible`, both ways, so visibility alone
         * would in fact be correct here. The pairing stays because the cost of the premise being
         * wrong in some untested rendering mode is silently exempting every tab and disabling
         * hibernation, which exists to stop this app leaking process trees - and note that muted
         * autoplay `<video>` is common enough (hero videos, timelines, GIF-as-video) that the
         * pairing is a weaker bound than it looks.
         *
         * Mute state is deliberately not consulted, unlike [PLAYING_MEDIA]: a call's own
         * self-view is muted by definition and its remote audio may run through Web Audio.
         *
         * **Known gap this cannot close:** a call with every camera off has no playing video at
         * all, so on an old host it is not detected here and the tab hibernates. That is exactly
         * what the host flag exists for.
         */
        SHOWN_IN_POP_OUT("tab still on screen in a pop-out"),

        /**
         * The user has typed into this document since it loaded, and nothing has navigated since.
         *
         * This state exists because hibernation's own justification ("the cost of being wrong is
         * a reload on return, not lost work") is false for exactly one class of tab: one where
         * the user has typed something and not submitted it. A reload there IS lost work - a
         * half-written order form, text typed and abandoned for twenty minutes. Every other
         * guard in this enum protects something the user is passively consuming; this one
         * protects something they made.
         *
         * Detected by [DirtyInputMarker]'s trusted-keystroke listener, NOT by inspecting the DOM.
         * The DOM heuristic was attempted three times and withdrawn (d1552be): value-vs-default
         * matching exempted every SPA that writes into its own fields, and `defaultValue` cannot
         * tell an autofilled password from a typed one, which exempted login pages for ~20
         * hours. A trusted keystroke answers "has a human typed here" directly - autofill and
         * framework writes fire no trusted keydown, so both failure modes are structurally
         * impossible rather than patched. The re-added mapping deliberately reverses the
         * "'input' must not linger" pin that d1552be left behind; the predicate it buried is not
         * the one that returned.
         *
         * Unlike [PLAYING_MEDIA], typing does not drain on its own, so this routinely reaches
         * the [MAX_RECHECKS] "left alone" ending rather than the "waited out" one - the same
         * bounded trade [SHOWN_IN_POP_OUT] makes for calls. A leaked process tree costs memory
         * until the next visit; a discarded draft costs the user's work, and those are not the
         * same mistake.
         */
        USER_INPUT("tab holds unsubmitted typing"),
    }

    /**
     * The reason this tab should not hibernate right now, fullscreen taking precedence.
     *
     * Fullscreen is answered locally rather than by [busyState], because the JS probe runs
     * inside the document and the document knows nothing about which window the host is
     * rendering it into. Extracted from the hibernation job so the selection itself is
     * testable, not just the policy it feeds.
     */
    internal suspend fun busyStateFor(
        fullscreenBlocks: Boolean,
        handle: BrowserHandle?,
        popOutEnabled: Boolean = autoPopOutEnabled(),
    ): BusyState {
        if (fullscreenBlocks) return BusyState.FULLSCREEN
        if (handle == null) return BusyState.IDLE
        // Dispatchers.IO is what carries the weight here: poppedOutOrNull is a blocking
        // reflective call with no suspension point, so withTimeoutOrNull cannot abandon it - the
        // bound is advisory, exactly as busyState documents for its own probe. What IO buys is
        // that a host which blocks does not take the UI dispatcher down with it. The timeout
        // still matters for the surrounding coroutine, and reads as "no answer" - the null case.
        val hostSays =
            withTimeoutOrNull(BUSY_CHECK_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { poppedOutOrNull(handle) }
            }
        // Unconditional, NOT gated on popOutEnabled. With the switch off the host stops opening
        // new pop-outs, but one already on screen stays open - and hibernating then disposes the
        // handle while the user is watching that window, killing the call in front of them. The
        // switch exists to stop the feature acting, not to destroy what it already did. Acting on
        // a stale flag would cost one leaked process tree until the next tab visit; acting on this
        // one costs a visibly destroyed call, and those are not the same mistake.
        if (hostSays == true) return BusyState.SHOWN_IN_POP_OUT
        return busyState(handle).let {
            // The 'shown' inference exists for hosts that CANNOT answer. A host that answered
            // false is exact, and the inference must not override it - otherwise any
            // muted-autoplay page (hero videos, timelines, GIF-as-video) on a tab reporting
            // itself visible is exempt, which is the silent process-tree leak this file exists
            // to prevent. Same when the feature is switched off: there is nothing to protect.
            //
            // Degrading to IDLE is safe ONLY because the script answers audible playback BEFORE
            // it answers 'shown', so anything audible has already reported 'media' and never
            // reaches this line - that is the guarantee that hibernation never cuts audio.
            // Reordering the script without reading this would break it silently.
            //
            // PICTURE_IN_PICTURE is deliberately untouched: someone who opened PiP from the
            // context menu did it by hand and did not ask this setting anything.
            if (it == BusyState.SHOWN_IN_POP_OUT && (!popOutEnabled || hostSays == false)) {
                BusyState.IDLE
            } else {
                it
            }
        }
    }

    /**
     * Whether the host's automatic pop-out is switched on, published as a system property.
     *
     * Both halves of the feature read one key: the host decides whether to pop a call out, this
     * decides whether to keep a popped-out tab alive, and they must agree. With it off there is
     * nothing to protect, so honouring it here keeps a stale `isPoppedOut` - a window the host has
     * not closed yet, say - from exempting a tab the user has asked to be left alone.
     *
     * A system property rather than plugin-api for the reason this file already documents about
     * the resource tier: plugins cannot read host settings, and the alternative is an api release.
     * Absent or unparseable means ON, matching the host's default: a plugin running against a host
     * too old to publish the key must not switch the guard off and start cutting calls.
     */
    internal fun autoPopOutEnabled(
        fromEnvironment: String? = System.getenv(AUTO_POP_OUT_KEY),
        fromHost: String? = System.getProperty(AUTO_POP_OUT_KEY),
    ): Boolean = (fromEnvironment ?: fromHost)?.trim()?.lowercase() !in FALSY

    /** Resolved once per host class; empty means the host has no such member. */
    private val poppedOutMethods =
        java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Method>>()

    /** Host classes already warned about, so a deterministic failure does not log per probe. */
    private val poppedOutWarned = java.util.concurrent.ConcurrentHashMap.newKeySet<Class<*>>()

    /**
     * Whether the host is showing this tab in a pop-out: true, false, or **null** for a host
     * with no such member.
     *
     * Tri-state on purpose. Collapsing "old host" and "a current host saying no" into `false`
     * left the `'shown'` inference authoritative on current hosts too, which is not what it is
     * for: a host that answered is exact, and a muted-autoplay page must not talk its way past
     * it into a permanent exemption.
     *
     * Reflective because `BrowserHandle.isPoppedOut` is defaulted and recent: naming it directly
     * would compile against a newer api than an installed host may carry, and a plugin
     * referencing a member the host's copy lacks is rejected wholesale by
     * BinaryCompatibilityValidator - which for this plugin reads as "my browser disappeared".
     *
     * `isPoppedOut`, NOT `getIsPoppedOut`: Kotlin leaves an `is`-prefixed Boolean property's
     * getter named after the property (verified with javap against the host class).
     * `setAccessible`, because the lookup resolves against the host's IMPLEMENTATION class -
     * which is the point, it carries members this plugin never compiled against - and a
     * non-public impl would otherwise throw IllegalAccessException into a catch that reads it as
     * "old host" and leaves the guard off for good.
     *
     * The `Method` is cached per class: `Class.getMethod` copies the declared-method array on
     * every call, and this runs on every probe.
     */
    internal fun poppedOutOrNull(handle: BrowserHandle): Boolean? {
        val type = handle.javaClass
        val resolved =
            poppedOutMethods.computeIfAbsent(type) {
                try {
                    val method = it.getMethod("isPoppedOut")
                    // Best-effort, and separately caught: an InaccessibleObjectException (a public
                    // method on a class in a non-exported module under JPMS) or a SecurityException
                    // would otherwise discard a Method that very likely invokes fine, cache the
                    // miss, and disable the guard for this host class for the life of the process -
                    // the exact outcome setAccessible is here to avoid.
                    runCatching { method.isAccessible = true }
                    java.util.Optional.of(method)
                } catch (_: NoSuchMethodException) {
                    // The expected, benign case: a host older than the member. Not warned.
                    java.util.Optional.empty()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    warnPoppedOut(type, "could not resolve isPoppedOut", e)
                    java.util.Optional.empty()
                }
            }
        val method = resolved.orElse(null) ?: return null
        return try {
            method.invoke(handle) as? Boolean
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // The member exists and the call failed, so the guard is off and nobody would
            // otherwise know. Null rather than false: "the host did not answer" is exactly this
            // case, and false would be taken as an exact "not popped out".
            warnPoppedOut(type, "could not read isPoppedOut", e)
            null
        }
    }

    /** Warns once per host class: awaitQuiet probes many times per cycle, per tab. */
    private fun warnPoppedOut(
        type: Class<*>,
        what: String,
        error: Throwable,
    ) {
        if (!poppedOutWarned.add(type)) return
        // println, matching every other hibernation line in this file, so the field check the PR
        // asks for lands in the same sink as the rest.
        println("[FluckBrowser] hibernation: $what from ${type.name}; pop-out guard off: $error")
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
     * A page-load `input` listener setting a dirty flag answers this properly and has since
     * landed - see [DirtyInputMarker] and its own read of `BUSY_SCRIPT` below, ordered after
     * this function's 'media' answer for the same reason media is checked first here: neither
     * answer may skip ahead of the other.
     *
     * So the shipped guarantee is narrow and true: **hibernation never cuts audio**, and (before
     * [DirtyInputMarker]) a backgrounded tab could be reloaded, discarding unsaved input the same
     * way Chrome's own memory saver does. That was documented in the README next to the opt-out;
     * the guard now narrows how often the reload case is actually reached.
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
     * Busy states are distinguished for their reason strings; every non-idle state gets the same
     * treatment, because each is worth waiting out:
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
            // The budget resets, the interval does not. Handing a newly audible tab a spent
            // budget would exempt it for no reason, so the count has to restart - but restarting
            // the interval as well pins an alternating tab at the 30s floor for the entire total
            // ceiling above, which is more probing than having no reset at all. The cost of a
            // monotonic interval is that the first recheck after a change can be late by up to
            // MAX_RECHECK_MS, which against hibernation windows measured in tens of minutes is
            // latency, not correctness.
            if (previous != null && busy != previous) {
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
     *
     * Sharing [normalizeJsStringResult] moved this onto its trailing trim (recovering whitespace
     * *inside* the quotes, e.g. `"' pip '"`), which this function's own inline logic never had -
     * such a value used to fall through to [BusyState.IDLE] and now resolves to
     * [BusyState.PICTURE_IN_PICTURE]. An improvement, but a real change to this decision path
     * from the host's actual `executeJavaScript` marshalling, not the pure deduplication the rest
     * of that refactor is.
     */
    internal fun busyStateFromScriptResult(result: Any?): BusyState =
        when (normalizeJsStringResult(result)?.lowercase()) {
            "pip" -> BusyState.PICTURE_IN_PICTURE
            "shown" -> BusyState.SHOWN_IN_POP_OUT
            "media" -> BusyState.PLAYING_MEDIA
            "input" -> BusyState.USER_INPUT
            else -> BusyState.IDLE
        }

    /**
     * The host's automatic-pop-out switch, published as a system property.
     *
     * `BOSS_BROWSER_*`, matching the swipe key in `HomeSwipeNavigation` - NOT the `boss.browser.*`
     * shape of `HOST_ENABLED_PROPERTY` and `HOST_IDLE_PROPERTY`, because this is the same
     * one-key-two-repos arrangement as the swipe setting rather than a hibernation tunable.
     * Cross-checked against `AutoPipSettingsManager.KEY` in BossConsole#282: a mismatch is silent
     * and one-directional, leaving the guard on while the off switch simply never works.
     *
     * Read from the environment as well as the property, as the swipe key is: an exported
     * variable is the operator's outer escape hatch when the host is not publishing.
     */
    internal const val AUTO_POP_OUT_KEY = "BOSS_BROWSER_AUTO_PIP"

    private const val BUSY_CHECK_TIMEOUT_MS = 2_000L

    /** Shortest gap between rechecks of an audible tab. Doubles up to [MAX_RECHECK_MS]. */
    internal const val MEDIA_RECHECK_MS = 30_000L

    internal const val MAX_RECHECK_MS = 5 * 60_000L

    /**
     * After this many rechecks a still-audible tab is left alone rather than polled forever.
     *
     * With the backoff below that is roughly 3 hours of wall clock for a tab whose reason stays
     * the same. A tab that alternates between reasons restarts this budget each time it changes,
     * and is bounded instead by the total ceiling in [awaitQuiet] at `maxRechecks * 2` probes -
     * with a monotonic interval saturating at [MAX_RECHECK_MS], up to roughly twice as long. A
     * tab still busy at either bound keeps its process tree until the user visits it again, which
     * is the deliberate trade: a bound on polling must not become a licence to cut the audio.
     */
    internal const val MAX_RECHECKS = 40

    /**
     * `internal` only so the ordering test can read it - it has no other caller outside this
     * object, and the script cannot be tested any other way without a JS engine.
     */
    internal const val BUSY_SCRIPT =
        "(function(){try{" +
            // Asked first: a PiP window is on screen showing this tab, and neither check below
            // would necessarily notice it.
            "if (document.pictureInPictureElement) return 'pip';" +
            // A site-owned pop-out is a separate window, not an element in this document, so
            // pictureInPictureElement stays null for it - the kind Google Meet opens.
            "if (window.documentPictureInPicture && documentPictureInPicture.window) return 'pip';" +
            // Audible playback is answered BEFORE the pop-out inference below, deliberately.
            // Both exempt the tab, so the order changes no hibernation decision on its own - but
            // it decides which REASON is reported, and that matters at the boundary: 'shown' is
            // DISCARDED when the host says this tab is not popped out, and discarding it while
            // it was covering audible playback would hibernate mid-play. With this order,
            // anything audible has already returned 'media' and is never at risk.
            "var media = Array.prototype.slice.call(document.querySelectorAll('video,audio'))" +
            ".some(function(m){" +
            "return !m.paused && !m.ended && !m.muted && m.volume > 0 && m.currentTime > 0;" +
            "});" +
            "if (media) return 'media';" +
            // Unsubmitted typing, answered AFTER 'media' (the audible-first guarantee the
            // ordering comment below leans on is untouched) but BEFORE 'shown', deliberately:
            // busyStateFor downgrades 'shown' to IDLE when the host answers "not popped out",
            // and a page that is both shown-inferred and dirty must report 'input' so that
            // downgrade can never hibernate a tab holding the user's typing.
            //
            // This reads a marker; it does NOT inspect the DOM. The DOM heuristic was attempted
            // three times and withdrawn (d1552be) - value-vs-default matching exempted every SPA,
            // and defaultValue cannot tell an autofilled password from a typed one, which
            // exempted login pages for ~20 hours. The marker is set only by a trusted keystroke
            // or IME composition start; see DirtyInputMarker for what sets it and the trades.
            // Shares DirtyInputMarker.DIRTY_FLAG_PROPERTY with the setter rather than a second
            // hardcoded literal, so a rename cannot desync setter from reader.
            "if (window.${DirtyInputMarker.DIRTY_FLAG_PROPERTY} === 1) return 'input';" +
            // The surface pop-out this app uses reparents the tab's real rendering surface into
            // a floating window, so neither PiP API reports it - the page is simply visible
            // while its tab is backgrounded. Paired with a playing video so a rendering mode
            // that never reported a backgrounded tab as hidden cannot exempt every tab. Mute is
            // not consulted here: a call's self-view is muted by definition, and anything
            // audible already returned above.
            "if (document.visibilityState === 'visible' && " +
            "Array.prototype.slice.call(document.querySelectorAll('video'))" +
            ".some(function(v){return !v.paused && !v.ended && v.currentTime > 0;})) " +
            "return 'shown';" +
            "return '';" +
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

/**
 * Installs [DirtyInputMarker.INSTALL_JS] on [handle], off the UI thread.
 *
 * One function rather than the identical `coroutineScope.launch { handle.executeJavaScript(...) }`
 * written twice (on adoption and on every navigation) - a future change to install error handling
 * or logging only has to land once, where missing one of the two call sites would silently
 * reintroduce the exact class of bug this feature exists to prevent.
 *
 * Dispatchers.IO for the same reason [captureScrollOrNull] is: `executeJavaScript` on
 * `coroutineScope` (Dispatchers.Main) has no suspension point of its own, so a wedged renderer
 * would park the app's UI thread rather than a background one. A failed install here degrades to
 * "hibernation treats this tab as never dirty", which is the existing, already-accepted
 * behaviour for a host with no page-event channel at all.
 *
 * Not truly fire-and-forget any more: the launched Job is stored on [hoistedState] so
 * [disposeBrowserHandleOffThread] can await it before disposing the handle this call is running
 * against - without tracking it, an install landing mid-flight during ANY dispose (tab close,
 * retry, hibernation) would be an untracked, unfenced race against `executeJavaScript` on a
 * handle about to be disposed.
 */
private fun installDirtyMarker(
    handle: BrowserHandle,
    coroutineScope: CoroutineScope,
    hoistedState: FluckBrowserTabState,
) {
    // A rapid second navigation can call this again before the first install's job has finished;
    // cancelling the outgoing one before overwriting the field is what keeps "tracked so dispose
    // can await/cancel it" true for every install, not just the most recent.
    hoistedState.dirtyMarkerInstallJob?.cancel()
    val job =
        coroutineScope.launch {
            // Rethrows CancellationException rather than swallowing it, matching captureScrollOrNull
            // and every runCatching inside ScrollRestore: runCatching is Throwable-wide, so a
            // cancelled install caught here would complete this job NORMALLY. Nothing runs after
            // the runCatching today, so today that only mislabels the job's completion - but it is
            // the same shape that would silently keep going the moment anything is added below it.
            runCatching { withContext(Dispatchers.IO) { handle.executeJavaScript(DirtyInputMarker.INSTALL_JS) } }
                .onFailure { if (it is CancellationException) throw it }
        }
    hoistedState.dirtyMarkerInstallJob = job
    // Identity-checked so a completion callback firing late (after a NEWER install already
    // replaced this field, e.g. the rapid-navigation case above) cannot null out a job that isn't
    // this one any more.
    job.invokeOnCompletion { if (hoistedState.dirtyMarkerInstallJob === job) hoistedState.dirtyMarkerInstallJob = null }
}

/**
 * Reads [ScrollRestore.CAPTURE_JS] AND the current URL off a still-live handle, right before it
 * is disposed - bundled together (via [ScrollRestore.SavedScroll]) because comparing the
 * RESTORING handle's URL against itself verifies nothing; the URL that actually needs verifying
 * is the one this OLD handle is on right now, at capture time.
 *
 * `getCurrentUrl()` is documented as a plain synchronous getter, and this reads it INSIDE the IO
 * offload anyway. The caller here is the hibernation job, which runs on `Dispatchers.Main`, and
 * this is the last thing to touch the handle before it is disposed; the point of moving five
 * `executeJavaScript` call sites to IO in this file was to stop taking the app's UI thread on
 * trust about what does and does not reach native code. One line inside the block it was already
 * next to costs nothing and removes the last unbounded, un-offloaded call on the capture path.
 *
 * 1 second is generous for a synchronous DOM read and short enough that a wedged renderer
 * cannot delay the dispose it is about to receive anyway. Any failure - timeout, thrown,
 * malformed result - degrades to "nothing to restore", never to a stale or wrong position.
 */
private suspend fun captureScrollOrNull(handle: BrowserHandle): ScrollRestore.SavedScroll? =
    try {
        // Dispatchers.IO, not the caller's Main - same reason busyState() gives for its own
        // identical call: withTimeoutOrNull can only abandon a call that SUSPENDS, and if
        // executeJavaScript blocks internally (a wedged renderer) this thread stays parked
        // until it answers. On Main that parks the whole app UI; on IO it parks one thread.
        withContext(Dispatchers.IO) {
            val url = handle.getCurrentUrl()
            withTimeoutOrNull(1_000L) { handle.executeJavaScript(ScrollRestore.CAPTURE_JS) }
                ?.let { ScrollRestore.parseCapture(it) }
                ?.let { ScrollRestore.SavedScroll(it, url) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

/** Bound to [captureScrollOrNull]'s own 1s capture timeout - these are meant to be sub-millisecond DOM ops on a healthy page. */
private const val RESTORE_CALL_TIMEOUT_MS = 1_000L

/** Wrapped the same way [ScrollRestore.CAPTURE_JS] is, so a thrown exception and a genuine `null` height stay distinguishable through the `runCatching` inside [ScrollRestore.awaitSettleAndApply]'s poll loop. */
private const val READ_HEIGHT_JS =
    "(function(){try{return document.documentElement.scrollHeight;}catch(e){return null;}})()"

/**
 * Applies a captured scroll position to a freshly (re)created handle, once the document has
 * settled - not at `DOMContentLoaded`, which loses a race with any page that re-renders after
 * load (an SPA's own hydration undoing the very scroll this just applied).
 *
 * A thin binding over [ScrollRestore.awaitSettleAndApply] - see that function's KDoc for why the
 * settle signal is document height rather than `readyState`, for the loop-correctness bug this
 * replaced, and for why it waits for [saved]'s URL to actually load before touching height at all
 * (a freshly created handle starts on `about:blank` while its own navigation is still in flight).
 *
 * The whole call runs on Dispatchers.IO, not the caller's Main - same reason as
 * [captureScrollOrNull]: up to several sequential `executeJavaScript` round trips happen inside
 * [ScrollRestore.awaitSettleAndApply], and a wedged renderer or a slow-to-hydrate SPA blocking
 * any one of them must park a background thread, not the app's UI thread.
 *
 * Each individual call is also wrapped in `bounded` ([RESTORE_CALL_TIMEOUT_MS]), but this does
 * NOT actually cap a wedged call's wall-clock time - see [captureScrollOrNull]'s KDoc (and
 * `busyState()`'s own identical admission) for why `withTimeoutOrNull` can only abandon OUR wait,
 * never a call that blocks without suspending. What it caps is the well-behaved case: on a healthy
 * renderer this keeps one slow-but-not-wedged call from running unbounded, rather than adding a
 * comment that promises a guarantee the code cannot give. [ScrollRestore.awaitSettleAndApply]'s
 * own caps are poll COUNTS, not wall-clock - the two do not compose into a hard deadline, and
 * `disposeBrowserHandleOffThread`'s 2s join is a best-effort wait on top of both, not a bound
 * either of them enforces. Tracked with the pre-existing renderer-timeout gap as BossConsole#300.
 */
private suspend fun restoreScrollOnSettle(
    handle: BrowserHandle,
    saved: ScrollRestore.SavedScroll,
) {
    if (!ScrollRestore.shouldAttemptRestore(saved.position, saved.url)) return

    suspend fun <T> bounded(block: suspend () -> T): T? =
        withTimeoutOrNull(RESTORE_CALL_TIMEOUT_MS) { block() }

    val settled =
        withContext(Dispatchers.IO) {
            ScrollRestore.awaitSettleAndApply(
                target = saved.position,
                expectedUrl = saved.url,
                readUrl = { bounded { handle.getCurrentUrl() } },
                readHeight = { bounded { handle.executeJavaScript(READ_HEIGHT_JS)?.toString() } },
                applyScroll = { bounded { handle.executeJavaScript(ScrollRestore.restoreJs(saved.position)) } },
                readPosition = { bounded { handle.executeJavaScript(ScrollRestore.CAPTURE_JS) }?.let { ScrollRestore.parseCapture(it) } },
                delay = { kotlinx.coroutines.delay(it) },
            )
        }
    if (!settled) {
        // Logged for the same reason a skipped hibernation is: "the scroll position didn't
        // restore" is otherwise indistinguishable in the field from "restore is broken". Names
        // all three endings rather than only the first - awaitSettleAndApply collapses them to
        // one boolean deliberately (the caller's response to each is identical), so the log line
        // is the only place the distinction can still be read.
        println(
            "[FluckBrowser] Scroll restore did not land: the page never reached the captured URL, " +
                "redirected away mid-restore, or every reapply clamped short of the saved position",
        )
    }
}

/**
 * A handle released from [FluckBrowserTabState], together with the jobs that were still pending
 * against it at the moment of release.
 *
 * The two travel together because they are only correct together. The jobs have to be read BEFORE
 * the cancellation inside `releaseBrowserHandle` completes - each nulls its own field via
 * `invokeOnCompletion`, usually within microseconds - so a caller reading them afterwards gets an
 * empty list and an await that looks like protection and is not. That was a rule enforced by a
 * comment at one call site out of six; bundling them makes the wrong order unrepresentable
 * instead, which is the same reason `browserHandle` has a `private set`.
 */
internal data class ReleasedHandle(
    val handle: BrowserHandle?,
    val pendingJobs: List<kotlinx.coroutines.Job>,
)

/**
 * Dispose a released handle off the UI thread, awaiting whatever was pending against it.
 *
 * The one-liner every release-then-dispose site uses, so all of them get the await rather than
 * only the site that remembered to ask for it.
 */
internal fun disposeReleasedHandle(released: ReleasedHandle) {
    released.handle?.let { disposeBrowserHandleOffThread(it, awaitJobs = released.pendingJobs) }
}

/** Dispose a handle off the UI thread — dispose() ends in a blocking Chromium IPC round-trip. */
internal fun disposeBrowserHandleOffThread(
    handle: BrowserHandle,
    awaitJobs: List<kotlinx.coroutines.Job> = emptyList(),
) {
    browserDisposeExecutor.execute {
        try {
            // installDirtyMarker and restoreScrollOnSettle run executeJavaScript against this
            // handle; joining them first is what stops one landing mid-dispose. Callers get the
            // list from [ReleasedHandle] rather than assembling it, so every dispose site is
            // covered rather than only the one that remembered to ask.
            //
            // What a join CANNOT tell us: a job wrapping `withTimeoutOrNull { executeJavaScript }`
            // completes the instant that timeout gives up, which is not when the underlying native
            // call returns if the renderer is wedged. That orphaned call can still be running
            // after this returns - a pre-existing limitation this plugin cannot close alone
            // (busyState admits the identical gap), tracked as BossConsole#300.
            //
            // runBlocking is safe here because this already runs on browserDisposeExecutor, a
            // background thread whose only job is this dispose. Bounded, not indefinite: a wedged
            // renderer must not delay a dispose that exists partly to recover FROM one. ONE
            // timeout around the whole list, not one per job - a job parked in a non-suspending
            // executeJavaScript is the one cancellation cannot shorten, so it is also the one
            // most likely to pay a per-job bound in full, twice.
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                    awaitJobs.forEach { job -> job.join() }
                }
            }
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

/**
 * Run [block] against a live browser handle, or do nothing.
 *
 * Every call on a [BrowserHandle] crosses into the host's JxBrowser objects, and a browser whose
 * engine has been replaced does not fail quietly there - it throws ObjectClosedException. The
 * handle's own `isValid` guard cannot close that race from the host side: it is a check on one
 * side of it, and `Browser.isClosed` answers `false` for a browser whose engine died with its IPC
 * channel, because the notification that would flip it has no channel left to arrive on.
 *
 * Uncaught, that exception lands on the *plugin*. From a `launch` it reaches the coroutine's last
 * resort handler; from a click it unwinds through composition. Either way the host's crash
 * interceptor tears down the whole plugin: on 17 Aug a single Enter in the URL bar closed all 13
 * open browser tabs, while the orphaned Chromium process kept playing the video from one of them.
 *
 * The tab does not need the exception to recover - the validity poll rebuilds the browser within
 * 500ms - so it stops here, at the plugin's own boundary. What must not happen is the plugin
 * dying on the way.
 *
 * Cancellation is re-thrown: swallowing it inside a coroutine breaks structured concurrency, and
 * the tab-switch path cancels these scopes routinely.
 */
internal inline fun <T> BrowserHandle?.onBrowser(
    op: String,
    block: (BrowserHandle) -> T,
): T? {
    val handle = this ?: return null
    return try {
        block(handle)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        println("[FluckBrowser] Browser call '$op' failed: ${e.message}")
        null
    }
}

/**
 * Fill a generated [password] into the new-password box at [targetIndex] and its confirm twin.
 *
 * Separate from [fillCredential] because the two want opposite things from the result. That one
 * must not compare what it wrote (a site masking or reformatting on input has still accepted the
 * user's credential, and calling that a failure would warn about a fill that worked). This one
 * must: the password is about to be written to Secret Manager, so what the field ended up holding
 * is the only value worth storing.
 */
internal suspend fun BrowserHandle?.fillNewPassword(
    password: String,
    targetIndex: Int? = null,
): CredentialFill.NewPasswordResult =
    CredentialFill.parseNewPasswordResult(
        onBrowser("fillNewPassword") {
            it.executeJavaScript(CredentialFill.newPasswordScript(password, targetIndex))
        }?.toString()
    )

/** Re-read the secret list, so a write is reflected in the suggestions immediately. */
internal suspend fun reloadSecrets(
    provider: SecretDataProvider?,
    onLoaded: (List<SecretEntryData>) -> Unit,
) {
    val loaded = runCatching { provider?.getUserSecrets(limit = 1000) }.getOrNull()
    onLoaded(loaded?.getOrNull()?.data ?: return)
}

/**
 * Store a generated password, then find the row it became so Edit can correct it.
 *
 * Returns null when the write failed. The id lookup is a re-read because `createSecret` answers
 * `Result<Unit>` and does not hand one back; a null id is survivable (Edit falls back to creating a
 * second entry) but worth trying for.
 */
internal suspend fun saveGeneratedPassword(
    provider: SecretDataProvider?,
    domain: String,
    username: String,
    password: String,
    knownSecrets: List<SecretEntryData>,
    onSecretsReloaded: (List<SecretEntryData>) -> Unit,
): SavedSecretNotice? {
    if (provider == null) return null
    // An existing row for this account is UPDATED, not duplicated. The change-password form is the
    // case that makes this necessary and it is one the card explicitly targets: the user already has
    // a secret for that site and account, so creating a second leaves two rows for one login - one
    // holding the dead password - and the fill list then offers both with nothing to tell them
    // apart.
    val matches = matchSecretsForDomain(domain, knownSecrets)
    val existing =
        if (username.isNotBlank()) {
            matches.firstOrNull { it.username.equals(username, ignoreCase = true) }
        } else {
            // A blank username still needs a dedupe, or every accepted suggestion on a signup form
            // where the email box is still empty creates another unnamed row - and
            // CredentialSavePolicy.decide's repair rule can then only ever name one of them.
            // Reachable by clearing the field and taking a second suggestion.
            matches.firstOrNull { it.username.isBlank() }
        }
    val written =
        runCatching {
            if (existing != null) {
                updateSecretPreservingFields(provider, existing, username, password)
            } else {
                provider.createSecret(
                    CreateSecretRequestData(website = domain, username = username, password = password),
                )
            }
        }.getOrNull() ?: return null
    if (written.isFailure) return null

    val reloaded = runCatching { provider.getUserSecrets(limit = 1000) }.getOrNull()?.getOrNull()?.data
    if (reloaded != null) onSecretsReloaded(reloaded)
    val id =
        reloaded
            ?.let { matchSecretsForDomain(domain, it) }
            // ignoreCase, matching the lookup above. They disagreed: if the backend normalises the
            // username, the id resolved to null and Edit silently fell back to creating a duplicate
            // of the row it was meant to correct.
            ?.firstOrNull { it.password == password && it.username.equals(username, ignoreCase = true) }
            ?.id
    return SavedSecretNotice(domain = domain, username = username, password = password, secretId = id)
}

/**
 * Apply a save-bar decision.
 *
 * **The update path passes every field of the existing secret back, and that is not defensive
 * tidiness - it is required.** `update_secret` is a full replace: it assigns `notes` and
 * `expiration_date` from its parameters unconditionally, deletes and re-inserts the tag rows, and
 * (the sharp one) `DELETE`s the whole `secret_metadata` row whenever `p_twofa_enabled` is false.
 * Omitting a field does not leave it alone, it destroys it - so an update that only meant to change
 * a password would silently drop the user's notes, tags and recovery codes.
 *
 * One case is unpreservable through that API and so is refused rather than mangled: a secret whose
 * metadata row holds a TOTP seed while 2FA is marked disabled. Passing `twofaEnabled = false` would
 * delete the row and the seed with it, and there is no parameter to send the seed back.
 */
internal suspend fun storeCredential(
    provider: SecretDataProvider?,
    decision: CredentialSavePolicy.Decision,
    domain: String,
    username: String,
    password: String,
): Boolean {
    if (provider == null) return false
    if (username.isBlank() || password.isBlank()) return false
    val result =
        runCatching {
            when (decision) {
                CredentialSavePolicy.Decision.Ignore -> return false
                is CredentialSavePolicy.Decision.Save ->
                    provider.createSecret(
                        CreateSecretRequestData(website = domain, username = username, password = password),
                    )
                is CredentialSavePolicy.Decision.Update -> {
                    val existing = decision.secret
                    // One predicate, shared with the caller that has to explain the refusal.
                    if (refusesTotpUpdate(existing)) return false
                    updateSecretPreservingFields(provider, existing, username, password)
                }
            }
        }.getOrNull() ?: return false
    return result.isSuccess
}

/**
 * Change a secret's password without destroying the rest of it.
 *
 * **Every field of [existing] is passed back, and that is required rather than tidy.**
 * `update_secret` is a full replace: it assigns `notes` and `expiration_date` from its parameters
 * unconditionally, deletes and re-inserts the tag rows, and `DELETE`s the whole `secret_metadata`
 * row whenever `p_twofa_enabled` is false. Omitting a field does not leave it alone, it destroys it -
 * so an update meaning only to change a password would silently drop the user's notes, tags and
 * recovery codes.
 *
 * Extracted because three call sites need exactly this and a partial copy in any of them is a silent
 * data loss rather than a compile error.
 */
internal suspend fun updateSecretPreservingFields(
    provider: SecretDataProvider,
    existing: SecretEntryData,
    username: String,
    password: String,
): Result<Unit> {
    val meta = existing.metadata
    return provider.updateSecret(
        UpdateSecretRequestData(
            secretId = existing.id,
            website = existing.website,
            username = username,
            password = password,
            notes = existing.notes,
            expirationDate = existing.expirationDate,
            tags = existing.tags,
            twofaEnabled = meta?.twofaEnabled ?: false,
            twofaType = meta?.twofaType,
            recoveryCodes = meta?.recoveryCodes ?: emptyList(),
        ),
    )
}

internal const val GENERATED_FILL_FAILED_NOTICE = "Could not fill the new password box on this page"
internal const val GENERATED_SAVE_FAILED_NOTICE = "Could not save to Secret Manager"

/**
 * Why an update was refused rather than attempted, for the one case that cannot be retried.
 *
 * `update_secret` deletes the whole metadata row when 2FA reads as disabled, and has no parameter
 * to send a TOTP seed back - so a secret holding a stranded seed cannot have its password changed
 * through this API without losing it. Telling the user "could not save" would send them to retry
 * something that can never work.
 */
internal const val TOTP_UPDATE_REFUSED_NOTICE = "Change this password in Secret Manager - it has 2FA details to keep"

/** The [storeCredential] refusal, exposed so the caller can explain it rather than guess. */
internal fun refusesTotpUpdate(secret: SecretEntryData): Boolean {
    val meta = secret.metadata ?: return false
    return !meta.twofaEnabled && !meta.twofaSecret.isNullOrBlank()
}

/**
 * Fill [secret] into the page.
 *
 * Scripted from here rather than delegated to `BrowserHandle.fillCredentials`, because that API
 * takes no "which field" argument and so can only guess - and its guess wrote the password into
 * Google's `display: none` decoy while the visible email box stayed empty. See [CredentialFill].
 *
 * [targetIndex] is the field's position in the eligible-login-field list when the caller knows it
 * (the suggestion list does; the right-click menu does not and relies on `document.activeElement`).
 *
 * There is deliberately **no fallback to `fillCredentials`**. An earlier version had one, for a
 * page that could not be scripted at all - but it could never help: both paths go through
 * `mainFrame().executeJavaScript`, so every condition that makes this return null (a torn-down
 * handle, no main frame, a throwing call) fails the host's injector for the same reason. A
 * fallback that cannot succeed where the primary failed is not a safety net, it is a second way to
 * write a password somewhere nobody asked for. `fillCredentials` is being removed from the api
 * outright, so this is the only credential-fill path there is.
 */
internal suspend fun BrowserHandle?.fillCredential(
    secret: SecretEntryData,
    targetIndex: Int? = null,
): CredentialFill.Result =
    CredentialFill.parseResult(
        onBrowser("fillCredential") {
            it.executeJavaScript(
                CredentialFill.script(secret.username, secret.password, targetIndex)
            ) as? String
        }
    )

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

    /**
     * Scroll position (bundled with the URL it was captured from - see [ScrollRestore.SavedScroll])
     * captured the instant before a hibernation dispose, consumed (set back to null) the moment
     * it is applied on wake. Plain var, not `mutableStateOf` - like [hibernationJob], nothing in
     * the UI reads this; it exists purely for the wake effect to hand off to itself across the
     * dispose/recreate boundary.
     *
     * **Known gap: a restore that fails to land is not distinguished from one that never ran.**
     * [restoreScrollOnSettle] does not write back here on failure - there is nothing to write,
     * the whole point of this field is being consumed once - so a wake that lands short (see
     * [ScrollRestore.awaitSettleAndApply]'s `@return` KDoc for why that happens on a
     * lazy-loading page) leaves the tab sitting wherever the failed apply left it. If the user
     * does not manually scroll back before the NEXT hibernation, that wrong position is what
     * gets captured and overwrites the original good one - a transient restore failure becomes
     * permanent. Narrow in practice (the idle window before hibernation is 10-30 minutes, so the
     * two events are normally far apart) and not fixed here: closing it means [captureScrollOrNull]
     * threading through "did the last restore actually land" from a whole prior wake, which is
     * more state than this field carries today.
     */
    var savedScroll: ScrollRestore.SavedScroll? = null

    /**
     * The two fire-and-forget jobs [installDirtyMarker] and the wake-time [restoreScrollOnSettle]
     * launch against the current handle. Tracked so [disposeBrowserHandleOffThread] can await
     * them before disposing - see its KDoc. Both nulled by whichever function set them once that
     * job actually completes, is not required for correctness (a completed Job's `join()` returns
     * immediately either way) but keeps this from holding a reference to a finished job forever.
     *
     * `@Volatile`, because not every writer is on `Dispatchers.Main` and an earlier version of
     * this doc claimed otherwise. `invokeOnCompletion` runs on whichever thread completes the job,
     * and cancelling a job suspended inside `withContext(Dispatchers.IO)` completes it on an IO
     * thread - so the `= null` write genuinely happens off Main. The identity check at each of
     * those handlers is what makes the write harmless; `@Volatile` is what makes it visible.
     *
     * Volatile gives visibility, not atomicity, and the read-then-cancel and check-then-null pairs
     * here are still not atomic. They do not need to be: cancelling an already-finished job is a
     * no-op, and the identity check means a late completion can only ever null out its own
     * reference. What it must not do is read a stale one, which is what this prevents.
     */
    @Volatile
    var dirtyMarkerInstallJob: kotlinx.coroutines.Job? = null

    @Volatile
    var scrollRestoreJob: kotlinx.coroutines.Job? = null
    var isLoading: Boolean by mutableStateOf(false)
    var urlBarText: TextFieldValue by mutableStateOf(TextFieldValue(""))

    /**
     * The URL the browser last committed to, as opposed to [urlBarText]'s editable draft. Written
     * unconditionally from the navigation listener - which that listener deliberately does NOT do
     * for [urlBarText] while the user is editing - and cleared by `applyHomeTabIdentity`, since
     * `about:blank` fires no navigation events and so cannot announce its own arrival. See
     * [visiblePageUrl].
     *
     * Hoisted, not `remember`ed in the composable. A tab switch disposes the composition (that is
     * what arms the hibernation timer) while `browserHandle` survives, so the handle-creation
     * effect - and with it the navigation listener's registration - does not re-run. A local
     * `remember` would leave the new composition's copy at `""` forever, with the old listener
     * closure writing into a dead `MutableState`.
     */
    var loadedUrl: String by mutableStateOf("")
    var pageTitle: String by mutableStateOf("New Tab")
    var zoomLevel: Double by mutableStateOf(1.0)

    /**
     * Set ONLY when this tab has actually failed, and null the rest of the time.
     *
     * It used to be seeded with "Initializing browser…" and cleared by exactly one thing: a
     * page-load callback. So every new tab reported a failure for the whole gap between adopting
     * its browser (which stopped the spinner, turning it into a warning triangle) and the first
     * `NavigationFinished`. Usually the page then arrived and the screen went away by
     * itself, which is what made it read as a flash rather than as a bug.
     *
     * Where it did not arrive, the tab was stuck: the host issues the initial `loadUrl` inside the
     * handle's constructor, before `createBrowser` returns, so the loading listener attached here
     * can be registered after that navigation has already finished - and `about:blank` may fire no
     * navigation events at all. Nothing else ever cleared the field, and Retry could not (the init
     * effect returns early while a handle is live), leaving Reset Tab as the only way out.
     *
     * "Still starting" now lives in [initMessage] instead, so a working browser can never be
     * described as a broken one.
     */
    var error: String? by mutableStateOf(null)

    /**
     * What the starting surface says while there is no handle yet. Hoisted like the rest, so a tab
     * switch mid-boot does not reset a slow-boot notice back to the first-second wording.
     */
    var initMessage: String by mutableStateOf(INITIALIZING_MESSAGE)
    var canGoBack: Boolean by mutableStateOf(false)
    var canGoForward: Boolean by mutableStateOf(false)
    var isBookmarked: Boolean by mutableStateOf(false)
    var navigationHistory: MutableList<Pair<String, String>> by mutableStateOf(mutableListOf())
    var historyIndex: Int by mutableStateOf(-1)

    // Sites the user answered "Never for this site" to. HOISTED, not remember-scoped: the host
    // drops an inactive tab's Composable, so a remember slot would forget the answer the next time
    // the user switched away and back - and the bar would ask again on a site they had explicitly
    // told it not to. Deliberately not persisted beyond this tab: an answer that outlived the
    // session would leave no way to be asked again short of editing a file.
    var neverSaveDomains: Set<String> by mutableStateOf(emptySet())

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

    /**
     * Whether fullscreen should currently hold hibernation off.
     *
     * Deliberately false in [FullscreenExitPhase.FAILED]. Fullscreen defers hibernation because
     * the user is presumed to be watching, and in FAILED that presumption has already failed:
     * the host was asked twice and never answered. Left ungated, the one state where the plugin
     * *knows* something is wrong would also be the only state a tab can never hibernate out of,
     * for the rest of its life, since nothing re-arms the timer while a tab stays backgrounded.
     *
     * Hibernating instead releases the handle, and BossConsole#36 detaches the host's view on
     * disposal - so the state that cannot be recovered from inside the tab is recovered by the
     * memory saver, at the cost of a reload. That is a better ending than a permanently exempt
     * tab, and it only happens after a full idle window on a tab nobody has returned to.
     */
    val fullscreenBlocksHibernation: Boolean
        get() = isInFullscreen && fullscreenExitPhase != FullscreenExitPhase.FAILED

    // Main-thread confined along with the two fullscreen fields above: the host callbacks are
    // marshalled through the Component's scope before they reach markFullscreen*, and
    // Dispatchers.Main is single-threaded and FIFO, so the epoch check inside the fallback
    // cannot interleave with a re-entry.
    private var fullscreenExitFallbackJob: Job? = null
    // Bumped on every fullscreen transition so a fallback armed for one session can recognise
    // that it woke into a different one and do nothing.
    private var fullscreenEpoch = 0

    // Stamped at host-callback time, before any dispatch, and checked once the callback lands.
    //
    // Needed because the callbacks marshal with Dispatchers.Main.immediate, which buys the tight
    // enter tracking documented at the call site but gives up FIFO *between* the two: an enter
    // arriving on a CEF thread is queued while an exit arriving on the EDT a moment later runs
    // inline, so the exit would apply first and leave isInFullscreen set with no window behind
    // it. Atomic because the stamp is taken on whichever thread the host used; the compare below
    // runs main-confined like the rest of this state.
    private val fullscreenCallbackSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private var lastAppliedFullscreenSeq = 0L

    /** Stamp a host callback at arrival, before it is marshalled. See [fullscreenCallbackSeq]. */
    fun nextFullscreenCallbackSeq(): Long = fullscreenCallbackSeq.incrementAndGet()

    /** False when this callback was overtaken by a later one that already applied. */
    private fun acceptFullscreenCallback(seq: Long): Boolean {
        if (seq <= lastAppliedFullscreenSeq) {
            println("[FluckBrowser] Dropping out-of-order fullscreen callback $seq")
            return false
        }
        lastAppliedFullscreenSeq = seq
        return true
    }

    /**
     * Takes ownership of a freshly created handle.
     *
     * Clears fullscreen state first: a replacement handle never reports `onExitFullscreen` for a
     * session it was not part of, so a stale `true` would strand the tab on the placeholder.
     */
    fun adoptBrowserHandle(handle: BrowserHandle) {
        // Re-adopting the installed handle would dispose it and then store the dead reference.
        // Unreachable today via the create effect's `browserHandle != null` early return, but
        // this method's whole purpose is to be safe without depending on that.
        if (browserHandle === handle) return
        browserHandle?.let { previous ->
            // Not just a dangling reference: an undisposed BrowserHandle keeps a whole
            // Chromium process tree alive. This is now the single chokepoint for handle
            // transitions, so it is the place to actually free it rather than log about it.
            println("[FluckBrowser] Adopting a handle over a live one; disposing the previous")
            // Symmetric with releaseBrowserHandle. Disposal alone would rely entirely on
            // BossConsole#36 detaching the view, which is a host version this plugin can load
            // without.
            if (isInFullscreen) requestHostExitFullscreen(previous)
            disposeBrowserHandleOffThread(previous)
        }
        clearFullscreenState()
        browserHandle = handle
    }

    /**
     * Releases the current handle and returns it for disposal.
     *
     * Asking the host to leave fullscreen first is what keeps a detached fullscreen window from
     * outliving the tab that owned it.
     *
     * This one host call is made synchronously, one line above a `disposeBrowserHandleOffThread`
     * whose comment exists because dispose ends in a blocking Chromium IPC round-trip. The
     * asymmetry is deliberate and rests on a specific host implementation rather than on the
     * general claim that exit is cheap: `BrowserHandleImpl.requestExitFullscreen` delegates to
     * `FullscreenBrowserWindow.requestExit`, whose entire body is a `SwingUtilities.invokeLater`.
     * It posts and returns. If that ever becomes an `invokeAndWait`, closing a fullscreen tab
     * starts hitching the UI and this call has to move off-thread with it - note that
     * `browserDisposeExecutor` is a *cached* pool, so simply routing it there would not preserve
     * exit-before-dispose ordering.
     *
     * BossConsole#36 (merged) additionally detaches a matching fullscreen view from `BrowserHandle`
     * disposal, which is what covers the invalid-handle case this cannot reach.
     */
    fun releaseBrowserHandle(): ReleasedHandle {
        val handle = browserHandle
        if (isInFullscreen) {
            requestHostExitFullscreen(handle)
        }
        browserHandle = null
        // Read BEFORE the cancel below, and returned to the caller rather than left for it to go
        // and fetch: cancellation completes almost immediately and each job nulls its own field
        // via invokeOnCompletion, so a list built from these fields afterwards is empty nearly
        // every time. See [ReleasedHandle].
        val pending = listOfNotNull(dirtyMarkerInstallJob, scrollRestoreJob)
        // Cancel AND hand the jobs back, which are two different protections. Cancellation cannot
        // retroactively stop a call already blocked in native code (see captureScrollOrNull's
        // KDoc for why not), but it DOES stop the job from starting its NEXT round trip - e.g.
        // restoreScrollOnSettle's reapply loop, between attempts. The returned list is what lets
        // every dispose site actually WAIT for a job already in flight. Each job nulls its own
        // reference in [installDirtyMarker] / the wake effect via `invokeOnCompletion`, so a
        // cancel here on an already-finished job is a harmless no-op, not a double-cancel.
        dirtyMarkerInstallJob?.cancel()
        scrollRestoreJob?.cancel()
        clearFullscreenState()
        // For the same reason fullscreen state is cleared here rather than at each call site:
        // isLoading describes THIS handle's navigation, and the listener that would flip it goes
        // with the handle. Left set, a tab hibernated or retried mid-load spends the whole next
        // boot showing a Stop button and a running progress bar over a browser that no longer
        // exists - the "the tab says it is doing something it isn't" family this file is being
        // cleaned of. Adoption re-seeds it from the replacement.
        isLoading = false
        return ReleasedHandle(handle, pending)
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
        // One more best-effort ask on the way out. Against a wedged host this is the same no-op
        // the retry already was; against a merely slow one it can still detach the view before
        // the tab recomposes with Content(). This is the path taken by a user who has decided
        // the window is gone, so it should not also be the only path that never asks.
        requestHostExitFullscreen(browserHandle)
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

    /**
     * A host enter always means "there is a live fullscreen session now", including when one was
     * already believed to be running.
     *
     * When `isInFullscreen` is already set, the callback is either a duplicate the host emitted
     * or a genuine re-entry inside a pending exit's window, and nothing here can tell them apart.
     * The costs are not symmetric, which is what decides it:
     *
     *  - Treating a genuine re-entry as a duplicate leaves the old exit's fallback armed against
     *    a session that is no longer the one it was armed for. It fires, posts an exit, and
     *    ejects the user from fullscreen they just started - then either restores the tab under
     *    them or spends another window landing on [FullscreenExitPhase.FAILED]. Reachable through
     *    exactly the failure this design calls the likelier one: the host exits, the callback is
     *    dropped, and the user re-enters inside the two-second window.
     *  - Treating a duplicate as a re-entry cancels a legitimate pending exit. The phase returns
     *    to IDLE, so the placeholder stops swallowing clicks, and the user clicks once more.
     *
     * So a repeat resets rather than early-returning. The epoch bump matters as much as the
     * cancel: it is what stops a fallback already past its delay from acting on the old session.
     *
     * The same reasoning covers [FullscreenExitPhase.FAILED], where leaving the phase set would
     * also keep [fullscreenBlocksHibernation] false and let a backgrounded tab hibernate
     * mid-video - the exact thing [TabHibernation.BusyState.FULLSCREEN] was added to prevent,
     * reached through the FAILED escape hatch. A fresh enter is positive evidence the host is
     * talking to us, unlike the *absence* of an exit callback that produced FAILED in the first
     * place.
     */
    fun markFullscreenEntered(seq: Long) {
        if (!acceptFullscreenCallback(seq)) return
        // The invariant has to hold in both directions, not just on release. These callbacks are
        // marshalled asynchronously onto the Component scope, so a late or duplicate host enter
        // can land after hibernation or crash recovery already dropped the handle. Fullscreen
        // with no handle is unexitable: the placeholder's click finds nothing to ask, clears
        // itself, and the content `when` falls through every branch to render nothing at all.
        if (browserHandle == null) return
        if (isInFullscreen && fullscreenExitPhase != FullscreenExitPhase.IDLE) {
            println("[FluckBrowser] Fullscreen re-entered during a pending exit; treating the session as live")
        }
        cancelFullscreenExitFallback()
        fullscreenEpoch++
        fullscreenExitPhase = FullscreenExitPhase.IDLE
        isInFullscreen = true
    }

    fun markFullscreenExited(seq: Long) {
        if (!acceptFullscreenCallback(seq)) return
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
        if (handle == null) return HostExitOutcome.DEAD
        // isValid is inside the guard, not before it. It is still checked first, so a handle that
        // is both invalid and throwing reads as DEAD - but it is a call across the plugin
        // classloader like every other, and this file already catches Throwable around
        // executeJavaScript for exactly that reason. Left outside, a throwing isValid propagates
        // out of releaseBrowserHandle and takes onDestroy with it, so neither the dispose on the
        // next line nor the scope cancellation below it runs: a leaked Chromium process tree, the
        // very thing adoptBrowserHandle was hardened against. From the fallback coroutine it
        // would instead kill the job and strand the phase at EXITING, where the debounce swallows
        // every click.
        return runCatching {
            if (!handle.isValid) {
                HostExitOutcome.DEAD
            } else {
                handle.requestExitFullscreen()
                HostExitOutcome.ACCEPTED
            }
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
    var isLoading by hoistedState::isLoading
    var urlBarText by hoistedState::urlBarText
    var loadedUrl by hoistedState::loadedUrl
    var pageTitle by hoistedState::pageTitle
    var zoomLevel by hoistedState::zoomLevel
    var error by hoistedState::error
    var initMessage by hoistedState::initMessage
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
    // Whether anything has been TYPED since the current claim was taken. Tracked rather than
    // inferred from "does the field still read as the loaded URL": that comparison is false
    // whenever the bar legitimately shows something else - a home tab holds "about:blank" while
    // loadedUrl is deliberately "", and a draft the user abandoned outlives the claim that was
    // released with it - and in every one of those states the staleness bound below would never
    // fire, which is the freeze it exists to prevent.
    var typedSinceClaim by remember { mutableStateOf(false) }
    // Bumped every time the field is CLAIMED. onFocusLost releases the claim from a delayed
    // coroutine, and nothing cancels that job if the field is re-claimed inside its 200ms - so
    // Cmd+L pressed just after a click into the page would have its fresh claim wiped by the
    // previous one's release, and the next navigation would collapse the selection it had just
    // made. Deliberately NOT snapshot state: nothing composes off it, and it must be readable
    // from a coroutine without invalidating anything.
    val claimGeneration = remember { AtomicLong(0) }
    var lastUserEditTime by remember { mutableStateOf(0L) }

    // Handed to BrowserToolbar far below; the registration that focuses it is further down,
    // after the state its callback writes.
    val addressBarFocusRequester = remember { FocusRequester() }
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

    // Cmd+L. Declared DOWN HERE deliberately, not next to the requester above: the focus
    // callback writes isUserEditingUrl, lastUserEditTime, typedSinceClaim AND the three
    // dropdown fields, so it cannot be declared above the last of them.
    AddressBarRegistration(
        tabId = tabId,
        focusRequester = addressBarFocusRequester,
    ) {
        // Claim the field BEFORE selecting it, or the navigation listener below wipes the
        // selection - see AddressBarUrlField.navigationMayRewrite, which is the predicate that
        // listener asks.
        isUserEditingUrl = true
        lastUserEditTime = System.currentTimeMillis()
        // Cmd+L is an explicit "I am replacing this" gesture - it selects the whole field - so
        // the claim it takes is a FRESH one even over a draft. That is what lets an abandoned
        // Cmd+L go stale; see UNTYPED_CLAIM_STALE_MS for the promise this does and does not make.
        typedSinceClaim = false
        claimGeneration.incrementAndGet()

        // Clear the dropdown state, like every other path that resets the claim (submit,
        // suggestion selected, history click). Cmd+L over a half-typed query used to leave it
        // standing, which broke three things at once: the first Escape dismissed the stale
        // dropdown instead of releasing the claim (so the two-stage design needed two presses to
        // do what it promises in one), Enter navigated to a suggestion computed from the text
        // BEFORE Cmd+L, and the arrow keys walked a dropdown that no longer matched the field.
        //
        // urlSuggestions included, though nothing reads it while the other three are cleared:
        // the Enter branch reaches for urlSuggestions.first(), so leaving a stale list behind is
        // a trap for whoever relaxes those guards, not a live bug.
        showUrlSuggestions = false
        autocompleteSuggestion = null
        selectedDropdownIndex = -1
        urlSuggestions = emptyList()

        urlBarText = AddressBarUrlField.selectAll(urlBarText)
    }
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

    // Inline credential suggestions: which login box the page has focus in, and which one the
    // user has already waved away. See CredentialSuggestions.kt for the probe behind this.
    var focusedLoginField by remember { mutableStateOf<FocusedLoginField?>(null) }
    var dismissedSuggestionId by remember { mutableStateOf<String?>(null) }

    // The probe's last full answer, and a counter that advances on every answer.
    //
    // focusedLoginField alone is not enough for the save prompt: it is null for both "there is no
    // login form here" (which is what a successful sign-in looks like) and "the user is not in one
    // right now" (which is what a FAILED sign-in looks like, with the form still on screen). The
    // counter is what makes a capture wait for a FRESH observation - without it, the initial
    // NoLoginField would read as "the form is gone" the instant a credential is captured, and the
    // prompt would appear on submit rather than on success.
    var lastProbe by remember { mutableStateOf<LoginFieldProbe>(LoginFieldProbe.NoLoginField) }
    var probeSeq by remember { mutableStateOf(0L) }

    // Settings, read on each composition rather than remembered, so flipping the toggle in the host
    // takes effect without a restart. Absent means ON: the host publishes both defaults up front
    // (BrowserSettings.init), and defaulting a password manager to off would leave a feature nobody
    // finds. Compare showShareButton below, which defaults OFF and so tests for "true".
    val suggestPasswordsEnabled = System.getProperty("boss.fluck.suggestPasswords") != "false"
    val offerToSavePasswordsEnabled = System.getProperty("boss.fluck.offerToSavePasswords") != "false"

    // A credential the user submitted, waiting to find out whether the login worked. Memory only,
    // never persisted. See CredentialSavePolicy.
    var pendingSave by remember { mutableStateOf<CredentialSavePolicy.Pending?>(null) }
    var pendingSaveProbeSeq by remember { mutableStateOf(0L) }
    var saveDecision by remember { mutableStateOf<CredentialSavePolicy.Decision?>(null) }
    // Editable, because a two-step sign-in's second screen has no identifier in the document.
    var saveUsernameDraft by remember { mutableStateOf("") }
    // "Never for this site". Hoisted (see FluckBrowserTabState) so a tab switch does not forget it.
    var neverSaveDomains by hoistedState::neverSaveDomains

    // Where a pushed capture is handed from the JxBrowser thread to composition. CONFLATED because
    // only the newest submission matters, and because the sink must never block the page's own
    // event dispatch - trySend on a conflated channel cannot.
    val captureChannel = remember { Channel<CapturedEvent>(Channel.CONFLATED) }

    // The generated password currently on offer, and a tick the Regenerate button advances. Keyed
    // remember rather than plain state so it is stable across recomposition: rerolling on every
    // frame would make the card unreadable and would save something other than what was shown.
    var regenerateTick by remember { mutableStateOf(0) }

    // Confirmation that a generated password reached Secret Manager, with what is needed to open it
    // for editing. Holds the password because Edit prefills the dialog with it.
    var savedSecretNotice by remember { mutableStateOf<SavedSecretNotice?>(null) }

    // Set by the saved-notice's Edit button. Opens the quick dialog on the secret just written.
    var editingSavedSecret by remember { mutableStateOf<SavedSecretNotice?>(null) }

    // Set by the right-click menu's "Suggest Strong Password". The automatic offer is suppressed on
    // a field the user has waved away, which otherwise makes that field a dead end - this is the
    // way back.
    var forceSuggestPassword by remember { mutableStateOf(false) }
    // ...and it is scoped to the box it was requested for. Left set, it followed the caret onto
    // every other password field on the page, which is an offer nobody asked for.
    LaunchedEffect(focusedLoginField?.dismissId) { forceSuggestPassword = false }

    // A fill that lands needs no announcement. One that does not is otherwise indistinguishable
    // from a click that never registered, which is exactly how a resolver picking the wrong field
    // went unnoticed for so long. The sequence number, not the message, keys the auto-dismiss:
    // two failures in a row are two notices, and keying on the text would leave the second to
    // inherit whatever was left of the first one's timer.
    var fillNotice by remember { mutableStateOf<String?>(null) }
    var fillNoticeSeq by remember { mutableStateOf(0) }
    val showFillNotice: (CredentialFill.Result) -> Unit = { result ->
        CredentialFill.notice(result)?.let {
            fillNotice = it
            fillNoticeSeq++
        }
    }

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
    // What "the page I am on" means for copy-link and bookmarking. Equal to currentUrl whenever
    // the box holds the loaded URL, which is almost always; see [visiblePageUrl]. Deliberately NOT
    // substituted for currentUrl wholesale - showDashboard and isSecure below answer "what is this
    // composable rendering", which is the box's question, not the loaded page's.
    val pageUrl = visiblePageUrl(urlBarText.text, loadedUrl)
    val showDashboard = isHomeUrl(currentUrl)

    // Security indicator derived from the LOADED page, not the box. showDashboard above asks what
    // this composable is rendering, which is the draft's question; the padlock is a claim about
    // the document the user is actually on, and typing "https://…" over an http page must not
    // put a lock on it.
    val isSecure = pageUrl.startsWith("https://")

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
            // Resolved ONCE, and used both to create the browser and to seed loadedUrl at
            // adoption below - the two were computed separately and could disagree on a wake
            // whose captured URL is the post-redirect one, leaving loadedUrl briefly wrong until
            // the navigation listener corrected it.
            val creationUrl =
                (hoistedState.savedScroll?.url ?: visiblePageUrl(urlBarText.text, hoistedState.loadedUrl))
                    .ifBlank { initialUrl }
            val creation = hoistedState.browserCreation
                ?: browserCreationScope.async {
                    // urlBarText is Compose state read here on an IO thread — safe
                    // (snapshot reads are thread-consistent), and deliberately
                    // snapshotted at launch time per the comment above.
                    //
                    // Through visiblePageUrl, so a tab backgrounded MID-EDIT wakes onto the page
                    // it was actually showing rather than onto a half-typed draft. Scroll restore
                    // makes that visible: savedScroll.url comes from getCurrentUrl() on the old
                    // handle, so a recreation seeded from the draft never matches, and the restore
                    // spends its whole navigation-wait cap polling for a URL that will not arrive.
                    // Same reason the loading listener reads the committed URL rather than the box.
                    browserService.createBrowser(
                        BrowserConfig(
                            // savedScroll's URL wins when there is one (see creationUrl above),
                            // so the recreated handle lands on exactly the URL
                            // awaitSettleAndApply is waiting for. Those are two different reads
                            // otherwise - savedScroll.url comes from getCurrentUrl() on the OLD
                            // handle, loadedUrl from the navigation listener - and any URL the
                            // listener does not report (an in-page fragment navigation, if the
                            // host's NavigationFinished does not fire for one) or that the server
                            // normalises on reload leaves the restore polling its full cap for a
                            // URL that never arrives. That is the fragment case
                            // shouldAttemptRestore goes out of its way to preserve, lost one
                            // layer down. Self-consistent by construction now.
                            url = creationUrl,
                        )
                    )
                }.also { hoistedState.browserCreation = it }
            var handle = withTimeoutOrNull(BROWSER_CREATION_TIMEOUT_MS) { creation.await() }
            if (handle == null && !creation.isCompleted) {
                // Past the first deadline and still running. Say so and keep waiting, rather than
                // reporting a failure that has not happened: the creation runs on the
                // never-cancelled browserCreationScope, and a cold first-install engine boot is
                // the ordinary way to get here.
                println("[FluckBrowser] Browser creation still running after ${BROWSER_CREATION_TIMEOUT_MS}ms")
                bootStateFor(BootOutcome.SLOW).let {
                    error = it.error
                    initMessage = it.initMessage
                }
                // Armed before the second wait, not after it: this effect is cancelled by a tab
                // switch, and the nudge is what brings a boot that landed while the tab was away
                // back into a composition that can adopt it. One registration per deferred -
                // lateAdoptNudged is hoisted, so it survives that switch.
                if (hoistedState.lateAdoptNudged !== creation) {
                    hoistedState.lateAdoptNudged = creation
                    creation.invokeOnCompletion { hoistedState.initNonce++ }
                }
                handle =
                    withTimeoutOrNull(BROWSER_CREATION_GIVE_UP_MS - BROWSER_CREATION_TIMEOUT_MS) {
                        creation.await()
                    }
            }
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
                bootStateFor(BootOutcome.ADOPTED).let {
                    error = it.error
                    initMessage = it.initMessage
                }

                // Consumed once: a plain retry or crash-recovery on the SAME creation must not
                // re-apply a scroll position from a previous life of this tab. Launched on
                // coroutineScope (not this LaunchedEffect's own scope) so a tab switch away
                // mid-restore does not cancel it - awaitSettleAndApply's own poll counts bound
                // the navigation-wait and settle phases regardless, and RESTORE_CALL_TIMEOUT_MS
                // bounds every individual call within them.
                //
                // Job stored on hoistedState so disposeBrowserHandleOffThread can await it before
                // disposing this same handle - restore can run several seconds, and nothing
                // previously stopped a fast-enough re-hibernation from disposing mid-restore.
                hoistedState.savedScroll?.let { saved ->
                    hoistedState.savedScroll = null
                    // Cancel-before-overwrite, symmetric with installDirtyMarker and for the same
                    // reason: a field that still has a job in it is a job nothing would join or
                    // cancel again once the reference is gone. Hard to reach here (savedScroll is
                    // consumed once, and releaseBrowserHandle cancels on the way out), so this is
                    // the invariant made structural rather than a live bug being fixed.
                    hoistedState.scrollRestoreJob?.cancel()
                    val job = coroutineScope.launch { restoreScrollOnSettle(handle, saved) }
                    hoistedState.scrollRestoreJob = job
                    // See installDirtyMarker's identical pattern for why this is identity-checked.
                    job.invokeOnCompletion { if (hoistedState.scrollRestoreJob === job) hoistedState.scrollRestoreJob = null }
                }

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
                    // Home is "nothing loaded" as far as visiblePageUrl is concerned. This is the
                    // only place that can say so: about:blank fires no navigation events, so
                    // arriving at home cannot announce itself the way every other URL does, and
                    // loadedUrl would otherwise keep pointing at the page before it.
                    loadedUrl = ""
                }

                // A tab that opens directly on home (e.g. a restored dashboard tab)
                // may never fire navigation events for about:blank — apply up front.
                if (isHomeUrl(urlBarText.text)) {
                    applyHomeTabIdentity()
                } else if (loadedUrl.isBlank()) {
                    // Seed for the same reason: the initial loadUrl is issued inside the handle's
                    // constructor, so the navigation listener below can be registered after that
                    // first navigation already finished and never hear about it. From the same
                    // creationUrl the browser was actually given, not a second reading of the box.
                    loadedUrl = creationUrl
                }

                // The dirty-typing marker rides navigation: installed now for the document already
                // committed, and re-installed on every navigation event below. Idempotent per
                // document (the property doubles as the install guard), and a new document arrives
                // with the flag naturally cleared - so submit-and-navigate resets it for free.
                // Keystroke-based, NOT a DOM diff; see DirtyInputMarker for the three withdrawn
                // attempts that rule the DOM approach out.
                installDirtyMarker(handle, coroutineScope, hoistedState)

                // Add listeners - matches bundled browser exactly
                handle.addNavigationListener { url ->
                    // This handle may already have been released - hibernation nulls
                    // browserHandle and then SUSPENDS in captureScrollOrNull, leaving Main free
                    // while the old handle is still alive with this listener attached. A late
                    // redirect or meta-refresh landing in that window would otherwise launch a
                    // fresh installDirtyMarker job that is not in the released bundle, so nothing
                    // joins it and its executeJavaScript can land during dispose() - the exact
                    // race the job tracking exists to close. It would also write loadedUrl for a
                    // handle the tab has given up, changing what the tab wakes onto.
                    if (hoistedState.browserHandle !== handle) return@addNavigationListener
                    installDirtyMarker(handle, coroutineScope, hoistedState)
                    loadedUrl = url
                    // Only update URL bar while the user does not own the field - which is
                    // also what keeps Cmd+L's selection alive on a still-loading page.
                    if (AddressBarUrlField.navigationMayRewrite(
                            isUserEditing = isUserEditingUrl,
                            msSinceUserEdit = System.currentTimeMillis() - lastUserEditTime,
                            typedSinceClaim = typedSinceClaim,
                        )
                    ) {
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

                    // Page finished loading. Deliberately does NOT clear `error` any more: this
                    // callback used to be the only thing that did, which made "is this tab
                    // broken" depend on a page-load event that can precede the listener's own
                    // registration (the initial loadUrl is issued in the handle's constructor) or
                    // never arrive at all (about:blank fires no navigation events). Adoption owns
                    // that now; a page load is not evidence about the browser's health.
                    if (!loading) {
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

                // Seeded from the handle rather than waited for, and deliberately AFTER the
                // listener above is registered. The host issues the initial loadUrl inside the
                // handle's constructor, so that navigation may already have finished by the time
                // this code runs, and then no callback is coming: a listener-only reading of "is
                // this tab loading" would stay wrong for the life of the tab, leaving the toolbar
                // showing Stop and the indicator spinning forever.
                //
                // The order is what makes both halves safe. Seeding first left the same race one
                // field over - a load finishing between the read and the registration latched
                // `true` with nothing left to clear it. With the listener already attached, a
                // completion in that window fires it (false) and this read then sees the finished
                // state (false); a completion after it fires the listener as usual.
                isLoading = handle.onBrowser("isLoading") { it.isLoading() } ?: false

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
                        // fullscreen state.
                        //
                        // `immediate` for the same reason as the context-menu callback above, and
                        // one more specific to fullscreen: a host that reparents the native view
                        // on the EDT and calls back from there would, under a plain launch, leave
                        // a dispatch-sized window where the view already lives in the fullscreen
                        // window but isInFullscreen is still false - so the tab composes
                        // Content() for a view that has moved. That is the two-parents condition
                        // the FAILED state exists to avoid, arrived at from the other end.
                        // The sequence stamp is taken here, on the host's thread, because
                        // `immediate` only orders callbacks that arrive the same way: one queued
                        // from a CEF thread would otherwise be overtaken by one running inline on
                        // the EDT. Stamping before the dispatch makes that detectable.
                        onEnterFullscreen = {
                            val seq = hoistedState.nextFullscreenCallbackSeq()
                            coroutineScope.launch(Dispatchers.Main.immediate) {
                                hoistedState.markFullscreenEntered(seq)
                            }
                        },
                        onExitFullscreen = {
                            val seq = hoistedState.nextFullscreenCallbackSeq()
                            coroutineScope.launch(Dispatchers.Main.immediate) {
                                hoistedState.markFullscreenExited(seq)
                            }
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
                bootStateFor(BootOutcome.FAILED).let {
                    error = it.error
                    initMessage = it.initMessage
                }
            } else {
                // Past the give-up deadline and STILL running. (withTimeoutOrNull returns null
                // rather than throwing, so this is the timeout path - there is no
                // TimeoutCancellationException to catch.) The in-flight deferred stays cached, so a
                // tab-switch re-entry keeps waiting on the same boot and the nudge armed above
                // still adopts it if it ever lands; an explicit Retry abandons it and starts fresh.
                //
                // Reported as broken rather than slow, which is the distinction the two deadlines
                // exist for: everything up to here might still be a cold first-install boot, and
                // calling that a failure is what made a first run look broken while it was working.
                // Two minutes in, an install with no engine at all needs to be told so, and a
                // spinner never says it.
                println("[FluckBrowser] Browser creation still running after ${BROWSER_CREATION_GIVE_UP_MS}ms - reporting it")
                bootStateFor(BootOutcome.WEDGED).let {
                    error = it.error
                    initMessage = it.initMessage
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

                        // The page the tab was ON, not the URL-bar draft. Deliberate, and worth
                        // stating because visiblePageUrl changes what this line used to mean: a
                        // renderer that crashes WHILE loading B now recovers to A rather than to
                        // B, since loadedUrl only advances on NavigationFinished. Recovering onto
                        // the page that was actually rendering is the safer of the two - the load
                        // that crashed is the one least worth immediately repeating - and
                        // recovery re-seeds loadedUrl below so the recreation agrees with it.
                        val currentUrl = pageUrl

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
                        disposeReleasedHandle(hoistedState.releaseBrowserHandle())
                        // A recovery in progress belongs on the starting surface, not the error
                        // one: the tab is about to rebuild its browser by itself, and the retry
                        // below is what makes that true. `error` stays null so the rebuild can
                        // render the page the moment it arrives.
                        initMessage = RECOVERING_MESSAGE

                        // Restore URL after small delay (home needs no restore)
                        delay(100)
                        if (!isHomeUrl(currentUrl)) {
                            urlBarText = TextFieldValue(currentUrl, TextRange(currentUrl.length))
                            // Both, so the recreation below resolves to this same URL rather than
                            // to whatever loadedUrl held before the crash.
                            loadedUrl = currentUrl
                        }

                        // Increment retry count to trigger LaunchedEffect
                        retryCount++
                    } else {
                        // Max recovery attempts reached
                        error = "Browser recovery failed after $maxRecoveryAttempts attempts. Please close and reopen this tab."
                        disposeReleasedHandle(hoistedState.releaseBrowserHandle())
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

    // Watch the page for a focused login box: to offer a saved credential beside it, to offer a
    // generated one on a signup form, and to tell a successful sign-in from a failed one.
    //
    // THE GATE IS WIDER THAN IT WAS, and deliberately. It used to require this page to already
    // have a saved credential, on the argument that "the user owns a secret" is true for almost
    // everyone and would leave every tab probing every page forever. Two of the three jobs above
    // cannot live with that: a signup form is by definition a page with nothing saved for it, and a
    // credential that was just typed is one the domain has no secret for yet. So the probe now also
    // runs when the suggestor is enabled, or while a capture is pending.
    //
    // What that actually costs, since the original argument deserves an answer rather than a
    // shrug: a page with no login field is probed every 4s (loginProbeDelayMs), and an inactive tab
    // is not composed at all - the host drops a background tab's Composable - so this is one
    // round-trip per 4s per VISIBLE tab, not per open tab. The tab hibernation busy-check already
    // makes a comparable call every 30s. The rate only becomes quick while the user is literally
    // inside a login box.
    //
    // Worth knowing for later: a push channel now exists (setPageEventScript, see
    // CredentialCapture), so this poll could be replaced by focus and geometry events. That is a
    // rewrite of a shipped feature rather than part of this one, and it is not attempted here.
    //
    // The call itself runs off the UI thread.
    //
    // That last one is not optional. executeJavaScript blocks until the renderer answers, and a
    // renderer in a long task - heavy JS, a modal beforeunload, a devtools breakpoint - would park
    // the EDT on every one of these. HibernationPolicy.busyState makes the same call and says why
    // it uses Dispatchers.IO: a wedged renderer must not take the UI with it. That call runs every
    // 30s and this one can run every 300ms, so the argument is strictly stronger here.
    //
    // A push channel would be better than any of this, but nothing on BrowserHandle carries a page
    // event to a plugin today; adding one is an api change, and this needs none.
    val probeDomain = remember(currentUrl) { extractMainDomain(currentUrl) }
    val hasCredentialsForPage =
        remember(probeDomain, allSecrets) {
            probeDomain != null && matchSecretsForDomain(probeDomain, allSecrets).isNotEmpty()
        }
    val probeWanted = hasCredentialsForPage || suggestPasswordsEnabled || pendingSave != null
    LaunchedEffect(browserHandle, probeWanted) {
        if (!probeWanted) {
            focusedLoginField = null
            return@LaunchedEffect
        }
        while (true) {
            val probe =
                parseLoginFieldProbe(
                    withContext(Dispatchers.IO) {
                        // The bound is advisory, exactly as in busyState: withTimeoutOrNull can
                        // only abandon a call that suspends, so a call blocked inside JxBrowser
                        // keeps this IO thread until the renderer answers. What it buys is that
                        // the thread it keeps is not the one drawing the UI.
                        browserHandle.onBrowser("loginFieldProbe") {
                            withTimeoutOrNull(LOGIN_PROBE_TIMEOUT_MS) {
                                it.executeJavaScript(LOGIN_FIELD_PROBE_JS)
                            }
                        }
                    }
                )
            focusedLoginField = (probe as? LoginFieldProbe.Focused)?.field
            // Published together, and the counter last: an observer keyed on probeSeq must never
            // read a stale lastProbe.
            lastProbe = probe
            probeSeq++
            delay(loginProbeDelayMs(probe))
        }
    }

    // Install the credential-capture script, and take it back down when the setting is off.
    //
    // This is the only path by which a page value the user typed reaches the plugin, and it only
    // ever fires on a submit. The periodic probe above stays value-free (hasValue: Boolean) for
    // exactly that reason - see CredentialCapture's KDoc for the rule.
    LaunchedEffect(browserHandle, offerToSavePasswordsEnabled) {
        val handle = browserHandle ?: return@LaunchedEffect
        if (!offerToSavePasswordsEnabled) {
            // Also drops anything already captured: leaving a pending credential in memory after
            // the feature is switched off would surface a prompt the user has just disabled.
            pendingSave = null
            saveDecision = null
            withContext(Dispatchers.IO) { PageEventChannel.clear(handle) }
            return@LaunchedEffect
        }
        // Reached REFLECTIVELY, so this plugin loads on hosts that predate the channel. Naming the
        // member directly is what made 1.2.22 unloadable below its floor: BrowserHandle is
        // host-compiled and served parent-first, so a reference the host's copy lacks fails the
        // binary-compatibility check and the whole plugin is disabled - which, for the plugin that
        // provides the browser tab, reads as the browser disappearing. See PageEventChannel.
        //
        // Absent channel means no save prompt and nothing else: the suggestor half needs none of
        // this. One log line, because "installed and the user has not submitted anything" and "this
        // host cannot deliver it" are otherwise the same silence.
        if (!PageEventChannel.isSupported(handle)) {
            println("[FluckBrowser] Host has no page event channel; no credential save prompt")
            return@LaunchedEffect
        }
        // Off the UI thread, like the probe and for the same reason: installing reaches into the
        // engine (a window property write plus an evaluation into the live document), and a renderer
        // in a long task would otherwise park the thread drawing the UI.
        withContext(Dispatchers.IO) {
            val installed =
                PageEventChannel.install(handle, CredentialCapture.INSTALL_JS) { url, json ->
                    // JxBrowser thread, inside the page's own event dispatch. trySend and nothing
                    // else: a blocking hand-off would stall the submit the user just performed.
                    //
                    // `url` is the host's reading of the document that posted, taken at the moment
                    // of the call. Carried through rather than resolved later - see the collector.
                    captureChannel.trySend(CapturedEvent(url, json))
                }
            if (!installed) {
                println("[FluckBrowser] Page event script would not install; no credential save prompt")
            }
        }
    }

    // Uninstall on the way out. Recomposition replaces the script and the channel anyway, so this
    // is a small leak rather than a bug - but what stays installed is a submit listener holding a
    // channel nobody reads, and given what that listener reads off the page it is worth retracting
    // deliberately rather than by side effect.
    DisposableEffect(browserHandle) {
        // Captured in a local. `browserHandle` is `by hoistedState::browserHandle`, a delegated
        // property, so reading it inside onDispose reads whatever it holds AT DISPOSE TIME - on a
        // handle swap that clears the new handle and leaves the old one's script installed, which
        // is the opposite of what this effect reads like it does.
        //
        // No IO dispatch, deliberately, unlike the install: clearPageEventScript only nulls two
        // fields host-side and never reaches the engine, so there is nothing here that can park the
        // UI thread.
        val handleAtInstall = browserHandle
        onDispose {
            handleAtInstall?.let { PageEventChannel.clear(it) }
        }
    }

    // Turn a pushed capture into a pending save.
    LaunchedEffect(captureChannel, offerToSavePasswordsEnabled) {
        if (!offerToSavePasswordsEnabled) return@LaunchedEffect
        for (event in captureChannel) {
            val captured = CredentialCapture.parse(event.json) ?: continue
            // The domain comes from the URL the HOST read off the posting document, never from the
            // page's payload: the bridge is reachable by any script on the page, so a page-supplied
            // origin would let a site offer a credential for a domain it does not own.
            //
            // It is also why this no longer calls getCurrentUrl(). That read happened after a
            // channel hop, by which time the navigation the submit itself started could have
            // committed - attributing the credential to the page the login LANDED on rather than the
            // one it was typed into, which for a cross-domain sign-in means storing it against the
            // wrong site entirely. The host reads its URL inside the page's own event dispatch,
            // before any of that.
            val domain = extractMainDomain(event.url) ?: continue
            if (domain in neverSaveDomains) continue
            pendingSave =
                CredentialSavePolicy.Pending(
                    domain = domain,
                    username = captured.username,
                    password = captured.password,
                    wasFilledByBoss = captured.wasFilledByBoss,
                    capturedAtMs = System.currentTimeMillis(),
                )
            // The observation the outcome has to be NEWER than. Without this the prompt would fire
            // on submit rather than on success, because the probe's last answer still describes the
            // page as it was before the credential went in.
            pendingSaveProbeSeq = probeSeq
            saveDecision = null
        }
    }

    // Decide whether the login worked, and what to offer if it did.
    LaunchedEffect(pendingSave, probeSeq) {
        val pending = pendingSave ?: return@LaunchedEffect
        // Already answered and showing; nothing to re-decide.
        if (saveDecision != null) return@LaunchedEffect
        // Wait for an observation taken after the capture.
        if (probeSeq <= pendingSaveProbeSeq) return@LaunchedEffect
        val onDomain = extractMainDomain(currentUrl)
        when (CredentialSavePolicy.outcome(pending, lastProbe, System.currentTimeMillis(), onDomain)) {
            CredentialSavePolicy.Outcome.WAITING -> Unit
            CredentialSavePolicy.Outcome.EXPIRED -> {
                pendingSave = null
                saveDecision = null
            }
            CredentialSavePolicy.Outcome.SUCCEEDED -> {
                when (val decision = CredentialSavePolicy.decide(pending, allSecrets)) {
                    CredentialSavePolicy.Decision.Ignore -> {
                        // Already stored, or filled by us and unchanged. Say nothing at all.
                        pendingSave = null
                    }
                    is CredentialSavePolicy.Decision.Save -> {
                        saveUsernameDraft = decision.username
                        saveDecision = decision
                    }
                    is CredentialSavePolicy.Decision.Update -> {
                        saveUsernameDraft = decision.secret.username
                        saveDecision = decision
                    }
                }
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
                    // A tab that is audible, fullscreen, or showing a call in a pop-out is waited
                    // out rather than cut off mid-play.
                    val busy =
                        TabHibernation.awaitQuiet(
                            probe = {
                                TabHibernation.busyStateFor(
                                    hoistedState.fullscreenBlocksHibernation,
                                    browserHandle,
                                )
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
                    // The jobs come back WITH the handle - see [ReleasedHandle] for why they
                    // cannot correctly be fetched separately afterwards. This site cannot use
                    // disposeReleasedHandle because it has to capture scroll off the live handle
                    // first; it disposes by hand in the `finally` below, with the same list.
                    val released = hoistedState.releaseBrowserHandle()
                    val handle = released.handle
                    if (handle != null) {
                        try {
                            // Sequenced ahead of dispose, not run concurrently with it in OUR
                            // bookkeeping - executeJavaScript against a handle that is mid-dispose
                            // is undefined JxBrowser behaviour. This ordering guarantees no
                            // overlap ONLY when captureScrollOrNull's executeJavaScript call
                            // returns within its own 1s timeout. withTimeoutOrNull abandons OUR
                            // wait, not the underlying call - if the renderer is genuinely wedged
                            // past 1s, the native call may still be running when
                            // disposeBrowserHandleOffThread posts dispose() on a different thread.
                            // busyState()'s own comment already admits the identical limitation
                            // for its own executeJavaScript-with-timeout call, so this is a
                            // pre-existing gap this diff participates in, not one it introduces or
                            // worsens - fully closing it needs a host-side capability (an
                            // abortable executeJavaScript, or a way to ask "no native call pending
                            // on this handle") that this plugin cannot build alone.
                            // captureScrollOrNull rethrows CancellationException (matching
                            // busyState's own convention) rather than swallowing it - the `finally`
                            // below is what guarantees dispose still runs when that happens.
                            //
                            // Written only if the tab has NOT already woken while this was
                            // capturing (up to 1s): releaseBrowserHandle() already nulled
                            // hoistedState.browserHandle, so a re-entry can adopt a brand new one
                            // before this finishes. If it did, this capture belongs to a session
                            // already over, and writing it would sit in savedScroll unconsumed
                            // until the tab's NEXT, unrelated wake - where it would be silently
                            // applied as if it belonged there.
                            val captured = captureScrollOrNull(handle)
                            if (hoistedState.browserHandle == null) {
                                hoistedState.savedScroll = captured
                            }
                        } finally {
                            // Off the UI thread so hibernating a background tab can't hitch
                            // the foreground UI. In a finally so cancellation (tab re-entry,
                            // right above in this same effect) can never skip disposal and leak
                            // the handle - see the comment above. Awaits the jobs snapshotted
                            // before release, not whatever hoistedState holds now - see
                            // disposeBrowserHandleOffThread's KDoc for why that distinction matters.
                            disposeBrowserHandleOffThread(handle, awaitJobs = released.pendingJobs)
                        }
                    }
                }
            }
        }
    }
    // Keep the URL-bar star in sync with external collection edits — e.g. when the
    // user removes a bookmark from the bookmarks panel, isBookmarked must reflect that.
    val bookmarkCollections = bookmarkDataProvider?.collections?.collectAsState(initial = emptyList())?.value
    LaunchedEffect(bookmarkCollections, pageUrl, pageTitle, bookmarkDataProvider) {
        bookmarkDataProvider?.let { provider ->
            val tabConfig = ai.rever.boss.plugin.workspace.TabConfig(
                type = "browser",
                title = pageTitle,
                url = pageUrl
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
                                browserHandle.onBrowser("reload") { it.reload() }
                                true
                            }
                            Key.Zero -> {
                                // Reset zoom - Cmd+0 / Ctrl+0
                                browserHandle.onBrowser("resetZoom") { it.resetZoom() }
                                true
                            }
                            Key.Equals, Key.NumPadAdd -> {
                                // Zoom in - Cmd++ or Cmd+= / Ctrl++ or Ctrl+=
                                browserHandle.onBrowser("zoomIn") { it.zoomIn() }
                                true
                            }
                            Key.Minus, Key.NumPadSubtract -> {
                                // Zoom out - Cmd+- / Ctrl+-
                                browserHandle.onBrowser("zoomOut") { it.zoomOut() }
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
            pageUrl = pageUrl,
            onShare = if (shareButtonEnabled) {
                {
                    BrowserShareManager.share(tabId, shareMaskInputs)
                    shareDialogOpen = true
                    thisTabInitiatedShare = true
                }
            } else null,
            isSharing = liveShareInfo != null,
            addressBarFocusRequester = addressBarFocusRequester,
            urlBarText = urlBarText,
            onUrlBarTextChange = { newValue ->
                isUserEditingUrl = true
                lastUserEditTime = System.currentTimeMillis()
                typedSinceClaim = true
                claimGeneration.incrementAndGet()
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
                typedSinceClaim = false
                showUrlSuggestions = false
                autocompleteSuggestion = null
                selectedDropdownIndex = -1
                coroutineScope.launch {
                    browserHandle.onBrowser("loadUrl") { it.loadUrl(url) }
                }
            },
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onBack = { browserHandle.onBrowser("goBack") { it.goBack() } },
            onForward = { browserHandle.onBrowser("goForward") { it.goForward() } },
            onReload = { browserHandle.onBrowser("reload") { it.reload() } },
            onStop = { browserHandle.onBrowser("stop") { it.stop() } },
            isLoading = isLoading,
            isSecure = isSecure,
            zoomLevel = zoomLevel,
            onZoomChange = { level ->
                zoomLevel = level
                browserHandle.onBrowser("setZoomLevel") { it.setZoomLevel(level) }
            },
            isBookmarked = isBookmarked,
            onBookmarkClick = {
                // Add or remove bookmark using the host API
                bookmarkDataProvider?.let { provider ->
                    val tabConfig = TabConfig(
                        type = "browser",
                        title = pageTitle,
                        url = pageUrl
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
                typedSinceClaim = false
                showUrlSuggestions = false
                autocompleteSuggestion = null
                selectedDropdownIndex = -1
                coroutineScope.launch {
                    browserHandle.onBrowser("loadUrl") { it.loadUrl(suggestion.url) }
                }
            },
            onDismissSuggestions = {
                showUrlSuggestions = false
                autocompleteSuggestion = null
                selectedDropdownIndex = -1
            },
            onCancelUrlEditing = {
                // Reset explicitly rather than leaning on the 200ms onFocusLost path: that only
                // runs if a focus-changed event actually arrives, and on Windows/Linux
                // HARDWARE_ACCELERATED a click into the native page surface may not produce one.
                // The claim is released either way - that is what fixes the freeze. Whether the
                // TEXT reverts is three separate judgements, so they live in a predicate with
                // their reasons and their own tests rather than inline here.
                if (AddressBarUrlField.shouldRestore(
                        isUserEditing = isUserEditingUrl,
                        loadedUrl = loadedUrl,
                        currentText = urlBarText.text,
                    )
                ) {
                    urlBarText = AddressBarUrlField.restoreTo(loadedUrl)
                }
                isUserEditingUrl = false
                lastUserEditTime = 0L
                typedSinceClaim = false
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
                val releasing = claimGeneration.get()
                coroutineScope.launch {
                    delay(200)
                    // Only release the claim this job was scheduled for, and that covers the
                    // DROPDOWN too - the check used to sit one line below, so a user who clicked
                    // away and came straight back to type had the freshly computed dropdown
                    // blanked by the outgoing job before it noticed the claim was no longer its
                    // own. Cmd+L (or a keystroke) inside the 200ms takes a NEW claim, and
                    // clearing that one would also undo the claim-before-select ordering: the
                    // next navigation would rewrite the field with a collapsed selection while it
                    // still held focus.
                    if (claimGeneration.get() != releasing) return@launch
                    showUrlSuggestions = false
                    isUserEditingUrl = false
                    typedSinceClaim = false
                    // lastUserEditTime is deliberately LEFT ALONE, unlike the other release
                    // paths. It is what keeps USER_EDIT_GRACE_MS reachable: this is the one
                    // release where the user may come straight back mid-Tab-completion, and
                    // zeroing it here made a navigation landing in that window overwrite what
                    // they were assembling. Resetting all three "for consistency" is what
                    // quietly retired the 300ms buffer.
                }
            }
        )

        // Browser content or Dashboard.
        // weight(1f) (instead of fillMaxSize) reserves exactly the height REMAINING below the
        // BrowserToolbar. In HARDWARE_ACCELERATED mode the browser is a heavyweight native
        // surface whose on-screen bounds track this composable; a fillMaxSize child can be
        // measured against the Column's full height and let that surface extend up over the
        // lightweight URL bar (the Windows overlap). Weighting bounds it to the area under the bar.
        // Both status surfaces - a failure and a boot still running - offer the same two escapes,
        // so the handlers are declared once here rather than duplicated into each branch.
        val onRetryBoot: () -> Unit = {
            // An explicit Retry abandons any cached creation - for a wedged
            // boot, re-awaiting it would just time out forever. Its eventual
            // result gets adopted and disposed, then a fresh boot starts.
            hoistedState.browserCreation?.let { pending ->
                hoistedState.browserCreation = null
                // The nudge goes with the deferred it was registered on. Left pointing at an
                // abandoned boot, its invokeOnCompletion still fires and bumps initNonce, which
                // restarts the FRESH boot's effect and silently resets its deadline once.
                hoistedState.lateAdoptNudged = null
                abandonBrowserCreation(pending)
            }
            // Retry used to be a no-op whenever a handle was live, because the init effect opens
            // with `if (browserHandle != null) return`. That was reachable - the old code could
            // show an error over a working browser - and it left Reset Tab as the only way out.
            // Dropping the handle here makes the button mean what it says in every state.
            disposeReleasedHandle(hoistedState.releaseBrowserHandle())
            error = null
            initMessage = INITIALIZING_MESSAGE
            retryCount = 0
            initNonce++
        }
        val onResetTab: () -> Unit = {
            // Open current URL in a new tab, then close this one
            val currentUrl = urlBarText.text.ifBlank { initialUrl }
            onOpenInNewTab(currentUrl)
            onCloseTab()
        }

        // Read once, so the ERROR branch below renders the value the surface was chosen from.
        // `error` is a delegated var and will not smart-cast, and an elvis there would have
        // rendered the *starting* message under a warning triangle - the exact confusion this
        // whole change removes - on the one path where the two could disagree.
        val currentError = error

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (
                browserSurfaceFor(
                    error = currentError,
                    isInFullscreen = hoistedState.isInFullscreen,
                    hasHandle = browserHandle != null,
                    showDashboard = showDashboard,
                    hasDashboardProvider = dashboardContentProvider != null,
                )
            ) {
                BrowserSurface.ERROR, BrowserSurface.STARTING -> {
                    // One composable, two meanings: a warning for a failure, a spinner for a boot
                    // still running. Both keep Retry and Reset Tab, for the boot that never lands.
                    //
                    // Resolved from the same `error` the branch was chosen from, so there is no
                    // path on which the surface and the message can disagree - and no fallback
                    // that could put the *starting* message under a warning icon.
                    val status = browserStatusFor(currentError, initMessage)
                    BrowserStatusContent(
                        message = status.message,
                        isLoading = status.isLoading,
                        onRetry = onRetryBoot,
                        onResetTab = onResetTab,
                    )
                }
                BrowserSurface.FULLSCREEN -> {
                    // Fullscreen placeholder - browser is displayed in a separate fullscreen window
                    FullscreenPlaceholder(
                        phase = hoistedState.fullscreenExitPhase,
                        onExitClick = { hoistedState.requestExitFullscreen(coroutineScope) },
                        onRestoreAnyway = { hoistedState.restoreTabFromFailedExit() },
                    )
                }
                BrowserSurface.DASHBOARD -> {
                    // Home is the one surface with no page behind it, so the host's in-page swipe
                    // detector cannot reach it - and it is the surface a back swipe lands you on,
                    // which made "swipe back home, then cannot swipe forward again" the gesture's
                    // most obvious hole. With no native surface in the way Compose sees the scroll
                    // here, so home detects it itself. See HomeSwipeSurface.
                    HomeSwipeSurface(
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        onNavigate = { direction ->
                            when (direction) {
                                HomeSwipeDirection.BACK ->
                                    browserHandle.onBrowser("goBack") { it.goBack() }
                                HomeSwipeDirection.FORWARD ->
                                    browserHandle.onBrowser("goForward") { it.goForward() }
                            }
                        },
                    ) {
                        // Show host's dashboard for about:blank pages. The safe call is how the
                        // provider's presence - which browserSurfaceFor already required to pick
                        // this branch - is expressed without a `!!` that would outlive that
                        // guarantee.
                        dashboardContentProvider?.DashboardContent(
                            onNavigate = { url ->
                                coroutineScope.launch {
                                    browserHandle.onBrowser("loadUrl") { it.loadUrl(url) }
                                }
                            }
                        )
                    }
                }
                BrowserSurface.BROWSER -> {
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
                                        if (browserHandle.onBrowser("canGoBack") { it.canGoBack() } == true) {
                                            browserHandle.onBrowser("goBack") { it.goBack() }
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    BrowserMouseNavigation.FORWARD -> {
                                        if (browserHandle.onBrowser("canGoForward") { it.canGoForward() } == true) {
                                            browserHandle.onBrowser("goForward") { it.goForward() }
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
                                    browserHandle.onBrowser("loadUrl") { it.loadUrl(url) }
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
                            onFillCredential = { secret ->
                                coroutineScope.launch {
                                    // No target index: the host reported this menu against the
                                    // clicked field, and the page's own activeElement is what
                                    // identifies it.
                                    showFillNotice(browserHandle.fillCredential(secret))
                                }
                            },
                            canSuggestPassword = suggestPasswordsEnabled,
                            onSuggestPassword = {
                                // Clears the dismissal as well as forcing the offer: the request
                                // came after the dismissal, so honouring the earlier "not this box"
                                // would make the menu item do nothing at all.
                                dismissedSuggestionId = null
                                forceSuggestPassword = true
                            },
                            isBookmarked = isBookmarked,
                            onAddBookmark = {
                                // Add or remove bookmark using the host API
                                bookmarkDataProvider?.let { provider ->
                                    val tabConfig = TabConfig(
                                        type = "browser",
                                        title = pageTitle,
                                        url = pageUrl
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

            // Inline credential suggestions, anchored under the page's focused login box.
            //
            // The anchor is a zero-size Box offset to where the field is: BossPopup measures its
            // anchor and, under HARDWARE_ACCELERATED, draws the list in an always-on-top window at
            // that spot. Drawn in place it would be invisible the moment it extended over the page,
            // because Chromium composites its own native window over the Compose scene - the same
            // reason the URL autocomplete list goes through BossPopup.
            //
            // focusable = false is load-bearing rather than cosmetic here: a focusable overlay
            // takes focus off the browser view, which blurs the very field the fill targets.
            val suggestionField = focusedLoginField
            val suggestionMatches =
                remember(suggestionField?.pageUrl, allSecrets) {
                    suggestionField
                        ?.pageUrl
                        ?.let { extractMainDomain(it) }
                        ?.let { matchSecretsForDomain(it, allSecrets) }
                        .orEmpty()
                }
            // The generator takes precedence on a new-password box, and the two would otherwise
            // both fire: a change-password form on a site with a saved secret satisfies
            // shouldOfferSuggestions AND shouldOfferGeneratedPassword for the same focused box, so
            // two BossPopups would draw at identical coordinates. Precedence rather than an
            // arbitrary z-order, because offering to fill an EXISTING password into a box that is
            // choosing a new one is the wrong offer regardless of which one is on top.
            val generatorHasThisBox =
                suggestionField != null &&
                    shouldOfferGeneratedPassword(
                        suggestionField,
                        dismissedSuggestionId,
                        suggestPasswordsEnabled,
                        forced = forceSuggestPassword,
                    )
            if (!generatorHasThisBox &&
                shouldOfferSuggestions(suggestionField, dismissedSuggestionId, suggestionMatches.size) &&
                suggestionField != null
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // CSS pixels scaled by the tab's zoom: the page reports its geometry in
                        // its own coordinate space, which only equals the view's at 100%.
                        .offset(
                            x = (suggestionField.left * zoomLevel).dp,
                            y = ((suggestionField.top + suggestionField.height) * zoomLevel).dp,
                        )
                        .width(
                            (suggestionField.width * zoomLevel).dp
                                .coerceIn(SUGGESTION_MIN_WIDTH, SUGGESTION_MAX_WIDTH)
                        ),
                ) {
                    BossPopup(
                        onDismissRequest = { dismissedSuggestionId = suggestionField.dismissId },
                        focusable = false,
                        anchoring = BossPopupAnchoring.AnchorBounds,
                    ) {
                        CredentialSuggestionList(
                            secrets = suggestionMatches,
                            onPick = { secret ->
                                // Dismissed before the fill, not after: the fill suspends, and
                                // leaving the list up over a box that is already being filled
                                // reads as the click having done nothing.
                                dismissedSuggestionId = suggestionField.dismissId
                                coroutineScope.launch {
                                    // The list is anchored to one specific box, so say which one
                                    // rather than making the script re-derive it.
                                    showFillNotice(
                                        browserHandle.fillCredential(
                                            secret,
                                            targetIndex = suggestionField.index,
                                        )
                                    )
                                }
                            },
                            onShowAll = {
                                dismissedSuggestionId = suggestionField.dismissId
                                showAllSecretsDialog = true
                            },
                            onDismiss = { dismissedSuggestionId = suggestionField.dismissId },
                        )
                    }
                }
            }

            // A generated password, offered beside a new-password box.
            //
            // Anchored the same way the saved-logins list is, and drawn through BossPopup for the
            // same reason: Chromium composites its own window over the Compose scene, so anything
            // drawn in place over page content is invisible.
            val generatorField = focusedLoginField
            // Generated OFF the composition thread, and this is not a micro-optimisation.
            //
            // `pattern` is attacker-controlled: it comes straight off the page, and matching it is
            // work java.util.regex cannot be asked to time out. Measured, the worst pattern found
            // costs ~56ms per match against a 20-character candidate, so a few hundred milliseconds
            // across the generator's retries - inside `remember { }` that was a repeatable stall on
            // the thread drawing the UI, on a feature that is on by default and fires when the user
            // clicks a password box. Bounded, but not somewhere it belongs.
            //
            // PasswordGenerator bounds the pattern length and the retry count as well; see its KDoc
            // for the numbers and for why the bound depends on the candidate length.
            val wantsSuggestion =
                generatorField != null &&
                    (generatorField.isNewPassword || (forceSuggestPassword && generatorField.isPassword))
            val offered by produceState<PasswordGenerator.Outcome.Generated?>(
                initialValue = null,
                generatorField?.dismissId,
                generatorField?.maxLength,
                generatorField?.pattern,
                wantsSuggestion,
                regenerateTick,
            ) {
                value =
                    if (!wantsSuggestion || generatorField == null) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            PasswordGenerator.generate(generatorField.maxLength, generatorField.pattern)
                                as? PasswordGenerator.Outcome.Generated
                        }
                    }
            }
            val offeredNow = offered
            if (generatorField != null &&
                offeredNow != null &&
                shouldOfferGeneratedPassword(
                    generatorField,
                    dismissedSuggestionId,
                    suggestPasswordsEnabled,
                    forced = forceSuggestPassword,
                )
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = (generatorField.left * zoomLevel).dp,
                            y = ((generatorField.top + generatorField.height) * zoomLevel).dp,
                        ),
                ) {
                    BossPopup(
                        onDismissRequest = {
                            // Clearing the forced flag as well as recording the dismissal. Forced
                            // deliberately bypasses the dismissal check, so recording it alone left
                            // an outside click doing nothing at all and the card re-rendering - only
                            // the X could close a card raised from the context menu.
                            forceSuggestPassword = false
                            dismissedSuggestionId = generatorField.dismissId
                        },
                        focusable = false,
                        anchoring = BossPopupAnchoring.AnchorBounds,
                    ) {
                        PasswordSuggestionCard(
                            password = offeredNow.password,
                            alphanumericOnly = offeredNow.alphanumericOnly,
                            onUse = {
                                forceSuggestPassword = false
                                // Dismissed first, for the same reason the credential list is: the
                                // fill suspends, and a card left over a box being filled reads as
                                // the click having done nothing.
                                dismissedSuggestionId = generatorField.dismissId
                                coroutineScope.launch {
                                    val result =
                                        browserHandle.fillNewPassword(
                                            offeredNow.password,
                                            targetIndex = generatorField.index,
                                        )
                                    // Save what LANDED, never what was generated. A field with
                                    // maxlength=12 truncates silently, and storing the untruncated
                                    // original would put a password in Secret Manager that has
                                    // never worked on the account.
                                    val landed = result.landed
                                    if (!result.filled || landed.isNullOrEmpty()) {
                                        fillNotice = GENERATED_FILL_FAILED_NOTICE
                                        fillNoticeSeq++
                                        return@launch
                                    }
                                    val domain =
                                        extractMainDomain(generatorField.pageUrl)
                                            ?: extractMainDomain(currentUrl)
                                            ?: return@launch
                                    savedSecretNotice =
                                        saveGeneratedPassword(
                                            provider = secretDataProvider,
                                            domain = domain,
                                            username = result.username.orEmpty(),
                                            password = landed,
                                            knownSecrets = allSecrets,
                                            onSecretsReloaded = { allSecrets = it },
                                        ) ?: run {
                                            fillNotice = GENERATED_SAVE_FAILED_NOTICE
                                            fillNoticeSeq++
                                            null
                                        }
                                }
                            },
                            onRegenerate = { regenerateTick++ },
                            onCopy = { copyToClipboard(offeredNow.password) },
                            onDismiss = {
                                // Clearing the forced flag matters: left set, the card would follow
                                // the caret onto every other password box on the page.
                                forceSuggestPassword = false
                                dismissedSuggestionId = generatorField.dismissId
                            },
                        )
                    }
                }
            }

            // Offer to store a credential the user just signed in with.
            //
            // Anchored under the toolbar rather than to a field: by the time this shows, the login
            // form is gone from the page, so there is nothing left to point at.
            saveDecision?.let { decision ->
                val isUpdate = decision is CredentialSavePolicy.Decision.Update
                val domain =
                    when (decision) {
                        is CredentialSavePolicy.Decision.Update -> getDisplayName(decision.secret.website)
                        is CredentialSavePolicy.Decision.Save -> decision.domain
                        CredentialSavePolicy.Decision.Ignore -> ""
                    }
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)) {
                    BossPopup(
                        onDismissRequest = {
                            saveDecision = null
                            pendingSave = null
                        },
                        focusable = true,
                        anchoring = BossPopupAnchoring.AnchorBounds,
                    ) {
                        SaveCredentialBar(
                            domain = domain,
                            username = saveUsernameDraft,
                            isUpdate = isUpdate,
                            // Decided from the DECISION, once, not from the draft the field edits.
                            // Deriving it from the draft made the editor disappear on the first
                            // keystroke. The bar asks for a username exactly when the policy could
                            // not supply one - a Save with nothing to fill in.
                            usernameEditable =
                                decision is CredentialSavePolicy.Decision.Save &&
                                    decision.username.isBlank(),
                            onUsernameChange = { saveUsernameDraft = it },
                            onConfirm = {
                                val pending = pendingSave
                                saveDecision = null
                                pendingSave = null
                                if (pending != null) {
                                    coroutineScope.launch {
                                        val ok =
                                            storeCredential(
                                                provider = secretDataProvider,
                                                decision = decision,
                                                domain = pending.domain,
                                                username = saveUsernameDraft,
                                                password = pending.password,
                                            )
                                        if (ok) {
                                            reloadSecrets(secretDataProvider) { allSecrets = it }
                                        } else {
                                            // A refusal and a failure are different things to be
                                            // told. The 2FA case can never succeed by retrying, so
                                            // "could not save" would send the user to try forever.
                                            fillNotice =
                                                if (decision is CredentialSavePolicy.Decision.Update &&
                                                    refusesTotpUpdate(decision.secret)
                                                ) {
                                                    TOTP_UPDATE_REFUSED_NOTICE
                                                } else {
                                                    GENERATED_SAVE_FAILED_NOTICE
                                                }
                                            fillNoticeSeq++
                                        }
                                    }
                                }
                            },
                            onNever = {
                                val pending = pendingSave
                                if (pending != null) neverSaveDomains = neverSaveDomains + pending.domain
                                saveDecision = null
                                pendingSave = null
                            },
                            onDismiss = {
                                saveDecision = null
                                pendingSave = null
                            },
                        )
                    }
                }
            }

            // The 90-second bound has to keep applying AFTER the bar is up. The decision effect
            // returns early once saveDecision is set, so the EXPIRED branch could no longer fire -
            // and a user who signs in and ignores the bar kept a plaintext password resident for as
            // long as the tab stayed on that page. Both KDoc and README claimed 90 seconds; this is
            // what makes that true rather than "90 seconds, unless we asked you something".
            saveDecision?.let { _ ->
                LaunchedEffect(saveDecision) {
                    val pending = pendingSave ?: return@LaunchedEffect
                    val remaining =
                        CredentialSavePolicy.PENDING_WINDOW_MS -
                            (System.currentTimeMillis() - pending.capturedAtMs)
                    if (remaining > 0) delay(remaining)
                    saveDecision = null
                    pendingSave = null
                }
            }

            // Confirmation that a generated password was stored, with a way to correct it. The
            // password is saved before this appears, deliberately: a signup that succeeds while the
            // user ignores a prompt must not leave the only copy of a generated password on a page
            // that is about to navigate away.
            savedSecretNotice?.let { notice ->
                LaunchedEffect(notice) {
                    delay(SAVED_NOTICE_DURATION_MS)
                    savedSecretNotice = null
                }
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)) {
                    BossPopup(
                        onDismissRequest = { savedSecretNotice = null },
                        focusable = false,
                        anchoring = BossPopupAnchoring.AnchorBounds,
                    ) {
                        Surface(
                            color = BossThemeColors.SurfaceColor,
                            shape = RoundedCornerShape(6.dp),
                            elevation = 6.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = BossThemeColors.SuccessColor,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Saved for ${notice.domain}",
                                    color = BossThemeColors.TextPrimary,
                                    fontSize = 12.sp,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                TextButton(
                                    onClick = {
                                        editingSavedSecret = notice
                                        savedSecretNotice = null
                                    },
                                ) {
                                    Text("Edit", color = MaterialTheme.colors.primary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Why a fill did nothing. Only failures are announced - a fill that worked is visible
            // in the page itself, and a toast for it would be noise on every login.
            fillNotice?.let { notice ->
                LaunchedEffect(fillNoticeSeq) {
                    delay(FILL_NOTICE_DURATION_MS)
                    fillNotice = null
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    color = BossThemeColors.SurfaceColor,
                    shape = RoundedCornerShape(6.dp),
                    elevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            // error, not primary: an accent-tinted warning reads as informational,
                            // and this only ever appears when a fill did not land.
                            tint = MaterialTheme.colors.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            notice,
                            color = BossThemeColors.TextPrimary,
                            fontSize = 12.sp,
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
                    },
                    onFillResult = showFillNotice
                )
            }

            // Edit the secret the suggestor just wrote.
            editingSavedSecret?.let { editing ->
                QuickCreateSecretDialog(
                    websitePrefill = editing.domain,
                    usernamePrefill = editing.username,
                    passwordPrefill = editing.password,
                    // Resolved from the reload that followed the create. Null means the row could
                    // not be found, and the dialog falls back to creating - a duplicate is a worse
                    // outcome than an update, but a better one than an Edit button that lies.
                    existing = editing.secretId?.let { id -> allSecrets.firstOrNull { it.id == id } },
                    secretDataProvider = secretDataProvider,
                    coroutineScope = coroutineScope,
                    onDismiss = { editingSavedSecret = null },
                    onSecretCreated = {
                        editingSavedSecret = null
                        coroutineScope.launch { reloadSecrets(secretDataProvider) { allSecrets = it } }
                    },
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
                                    typedSinceClaim = false
                                    coroutineScope.launch {
                                        browserHandle.onBrowser("loadUrl") { it.loadUrl(entry.url) }
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
internal fun extractMainDomain(url: String): String? {
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
            // Not a public-suffix list, but the suffixes that matter most here. Without them
            // every tenant of a shared host reduces to the same registrable domain, so a
            // credential saved on one person's *.vercel.app would be offered, unprompted, beside
            // a login box on anyone else's. That was harmless while this only ranked a menu; it is
            // not now that it gates an unprompted suggestion.
            val multiPartTlds =
                setOf(
                    "co.uk", "org.uk", "ac.uk", "com.au", "co.jp", "co.nz", "com.br", "co.in",
                    "co.za", "com.cn", "co.kr",
                    "github.io", "vercel.app", "herokuapp.com", "netlify.app", "pages.dev",
                    "web.app", "firebaseapp.com", "appspot.com", "myshopify.com", "blogspot.com",
                    "azurewebsites.net", "cloudfront.net", "workers.dev",
                )
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
 * The registrable domain a secret's `website` refers to, or null if it does not name a site.
 *
 * [extractMainDomain] answers null for a bare authority, because `java.net.URI("google.com")`
 * parses it as a *path* and reports no host. Most stored websites are bare - "github.com",
 * "accounts.google.com" - so the bare form is retried with a scheme rather than treated as
 * unparseable.
 *
 * A value with no dot in it is not a domain and gets null. That is what keeps a secret labelled
 * "GOOGLE" (an API key) out of the login list for google.com, and one labelled "android" out of
 * every list. The single exception is `localhost`, which [extractMainDomain] already treats as a
 * host in its own right, so a secret saved for it has to resolve the same way or it would never
 * match the page it was saved on.
 */
internal fun secretWebsiteDomain(website: String): String? {
    val trimmed = website.trim().lowercase()
    if (trimmed.isEmpty()) return null
    extractMainDomain(trimmed)?.let { return it }
    val authority =
        trimmed
            .substringBefore('/')
            .substringBefore('?')
            .substringAfter('@')
            .substringBefore(':')
    if (authority == LOCALHOST || authority.startsWith("127.")) return authority
    if (!authority.contains('.')) return null
    return extractMainDomain("https://$authority")
}

private const val LOCALHOST = "localhost"

/**
 * Match secrets against the page's registrable domain.
 *
 * Domain relationships, not substrings. The old rule accepted a match in either direction with
 * `contains`, which meant a secret whose website was the bare word "GOOGLE" - the Gemini API key -
 * was offered as a **login credential** for google.com, because "google.com".contains("google").
 * Every short label was a wildcard over every domain containing it.
 *
 * Equality plus either-way suffix keeps the cases that matter: a secret saved for
 * "accounts.google.com" is offered on google.com and vice versa, since both reduce to the same
 * registrable domain, while "google.com" and "notgoogle.com" stay unrelated.
 */
internal fun matchSecretsForDomain(
    domain: String,
    secrets: List<SecretEntryData>,
    maxResults: Int = 5
): List<SecretEntryData> {
    val pageDomain = domain.trim().lowercase()
    if (pageDomain.isEmpty()) return emptyList()

    return secrets.filter { secretMatchesDomain(pageDomain, it) }.take(maxResults)
}

/**
 * Whether one secret belongs to [pageDomain].
 *
 * Extracted so there is exactly one rule. The "Select Secret to Fill" dialog carried a
 * hand-inlined copy of the old substring test, and its `?: ""` fallback made it worse than the
 * original: `contains("")` is always true, so every secret whose website does not parse as a
 * domain - "GOOGLE", "android", a GitHub Actions secret label - was badged as matching the current
 * site. That dialog is where the suggestion list's "Other logins..." row sends the user, so the
 * fix to [matchSecretsForDomain] stopped one click short of where it was needed.
 *
 * [pageDomain] is expected already reduced by [extractMainDomain].
 */
internal fun secretMatchesDomain(
    pageDomain: String,
    secret: SecretEntryData,
): Boolean {
    val page = pageDomain.trim().lowercase()
    if (page.isEmpty()) return false
    val secretDomain = secretWebsiteDomain(secret.website) ?: return false
    // Equality carries the common case, since both sides are already registrable domains. The
    // suffix arms only fire for the dotless hosts secretWebsiteDomain returns verbatim (localhost,
    // 127.*), which extractMainDomain does not reduce.
    return secretDomain == page ||
        secretDomain.endsWith(".$page") ||
        page.endsWith(".$secretDomain")
}

/**
 * Get display name for a website.
 * Extracts a clean, readable name from the website URL.
 */
internal fun getDisplayName(website: String): String {
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
    onAddBookmark: () -> Unit = {},
    // The fill itself belongs to the caller: it owns the page-scripting path and the notice a
    // fill that lands nowhere has to produce. Building a menu and performing a fill are separate
    // jobs, and the second one needs a suspend context this builder does not have.
    onFillCredential: (SecretEntryData) -> Unit = {},
    // Offered only on a password box, and only when the suggestor is switched on.
    canSuggestPassword: Boolean = false,
    onSuggestPassword: () -> Unit = {}
): List<ContextMenuItem> = buildList {
    // Check if form field is focused (editable element)
    if (info?.isEditable == true) {
        // Form field context menu (matches original focusedFieldInfo != null case)

        // Edit operations for text fields (first, like main branch)
        add(ContextMenuItem(
            text = "Cut",
            onClick = { browserHandle.onBrowser("cut") { it.cut() } }
        ))

        add(ContextMenuItem(
            text = "Copy",
            onClick = { browserHandle.onBrowser("copySelection") { it.copySelection() } }
        ))

        add(ContextMenuItem(
            text = "Paste",
            onClick = { browserHandle.onBrowser("paste") { it.paste() } }
        ))

        add(ContextMenuItem(
            text = "Select All",
            onClick = { browserHandle.onBrowser("selectAll") { it.selectAll() } }
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
                            onClick = { onFillCredential(secret) }
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

            // Only on a password box: the item generates a password, and putting one into a
            // username field is not a thing to offer even by accident.
            if (canSuggestPassword && formFieldInfo.isPasswordField()) {
                add(ContextMenuItem(
                    text = "Suggest Strong Password",
                    onClick = onSuggestPassword
                ))
            }

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
            onClick = { browserHandle.onBrowser("reload") { it.reload() } }
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
            onClick = { browserHandle.onBrowser("showDevTools") { it.showDevTools() } }
        ))
    } else {
        // Default context menu

        // Navigation items (only show if available)
        if (canGoBack) {
            add(ContextMenuItem(
                text = "Back",
                onClick = { browserHandle.onBrowser("goBack") { it.goBack() } }
            ))
        }

        if (canGoForward) {
            add(ContextMenuItem(
                text = "Forward",
                onClick = { browserHandle.onBrowser("goForward") { it.goForward() } }
            ))
        }

        // Always show reload
        add(ContextMenuItem(
            text = "Reload",
            onClick = { browserHandle.onBrowser("reload") { it.reload() } }
        ))

        add(ContextMenuItem(isDivider = true))

        // Picture-in-Picture option if clicking on a video
        if (info?.hasVideo == true) {
            add(ContextMenuItem(
                text = "Picture in Picture",
                onClick = { browserHandle.onBrowser("requestPictureInPicture") { it.requestPictureInPicture() } }
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
            onClick = { browserHandle.onBrowser("showDevTools") { it.showDevTools() } }
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
        // Take down whatever is on screen FIRST. If a previous call fell through to Swing and
        // that popup is still up, going native without dismissing it would leave two menus
        // visible and drop the reference to the one hide() could still have closed.
        currentPopup?.let { it.isVisible = false }

        // A real NSMenu where the platform allows it. It is an OS-owned window, so unlike a Swing
        // popup it can never be occluded by the browser's hardware-accelerated surface - the very
        // problem isLightWeightPopupEnabled below exists to work around.
        val nodes =
            items.map { item ->
                if (item.isDivider) {
                    NativeMenuNode.Separator
                } else {
                    NativeMenuNode.Item(label = item.text, action = item.onClick)
                }
            }
        if (NativeContextMenu.show(screenX, screenY, nodes, onDismiss)) {
            currentPopup = null
            return
        }
        // The native attempt declined AFTER possibly leaving a previous NSMenu on screen: show()
        // only greys and detaches the outgoing menu once it has committed, and per measured fact 2
        // detaching does not close it. Without this the user would see the Swing menu drawn over a
        // still-open native one - the very thing the dismissal above exists to prevent. No-op when
        // nothing is attached, so the non-macOS path is unaffected.
        NativeContextMenu.hide()

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
        NativeContextMenu.hide()
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
    // The loaded page's URL, which equals urlBarText except while the user is mid-edit. Only the
    // copy-link button reads it - everything else here is about the text box itself. See
    // visiblePageUrl.
    pageUrl: String,
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
    /**
     * Escape: the user is abandoning the edit, not just closing the dropdown. Puts the loaded URL
     * back and releases the field, which is what stops a Cmd+L the user changed their mind about
     * leaving the bar claimed forever - see [AddressBarUrlField.restoreTo].
     */
    onCancelUrlEditing: () -> Unit = {},
    onAcceptAutocomplete: () -> Unit = {},
    onSelectedDropdownIndexChange: (Int) -> Unit = {},
    onSuggestionDeleted: (UrlHistoryEntry) -> Unit = {},
    onFocusLost: () -> Unit = {},
    onShare: (() -> Unit)? = null,
    isSharing: Boolean = false,
    /**
     * Attached to the URL field so Cmd+L can focus it from outside the composition
     * (see [AddressBarFocusRegistry]). Defaulted rather than nullable so the modifier chain
     * below stays a plain `.focusRequester(...)`; a caller that omits it - a preview - gets a
     * live requester nothing ever asks to focus.
     */
    addressBarFocusRequester: FocusRequester = remember { FocusRequester() }
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

        // Copy-link button, left of the URL bar. Mirrors ShareLinkRow's copy-feedback pattern
        // (swap to a checkmark, revert after ~1.6s) so copying a link feels the same everywhere
        // in this file. Copies pageUrl, not urlBarText.text: a copy-LINK affordance means "the
        // page I am on", and mid-edit the text box holds a half-typed string instead. The
        // bookmark paths at the call site read the same value, so the two agree.
        run {
            val clipboardManager = LocalClipboardManager.current
            var urlCopied by remember { mutableStateOf(false) }
            // Home has no link to copy - the dashboard is a surface, not a page, and its URL is
            // blank or about:blank. Disabled rather than copying an empty string and showing the
            // same green check-mark the successful case does, which would report a copy that did
            // not happen. Greyed the way Back/Forward are, since an explicit tint overrides the
            // content alpha IconButton would otherwise dim it with.
            val copyable = !isHomeUrl(pageUrl)
            LaunchedEffect(urlCopied) {
                if (urlCopied) {
                    delay(1600)
                    urlCopied = false
                }
            }
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(pageUrl))
                    urlCopied = true
                },
                enabled = copyable,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (urlCopied) Icons.Filled.Check else Icons.Filled.Link,
                    contentDescription = if (urlCopied) "Copied" else "Copy link",
                    tint =
                        when {
                            urlCopied -> BossThemeColors.SuccessColor
                            copyable -> BossThemeColors.TextPrimary
                            else -> BossThemeColors.TextMuted
                        },
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // URL text field with bookmark star, zoom indicator, and autocomplete dropdown
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = urlBarText,
                onValueChange = onUrlBarTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .focusRequester(addressBarFocusRequester)
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
                                // TWO-STAGE, Firefox's shape rather than Chrome's: the first press
                                // closes the dropdown, the second abandons the edit. Chrome reverts
                                // on the first press, but Chrome has no separate dropdown-dismiss
                                // gesture to spend one on - this field does, and collapsing both
                                // into one press meant "type a few characters, Escape the dropdown
                                // away, keep typing" silently lost the draft.
                                //
                                // The stuck claim is still closed: Cmd+L opens no dropdown, so the
                                // first Escape after it goes straight to the release.
                                //
                                // Focus deliberately STAYS in the field either way, which is what
                                // both browsers do - Escape reverts the text and leaves you able to
                                // retype. Clearing it would also send focus nowhere in particular
                                // (the page is a native surface this plugin cannot focus), and the
                                // tab's own onPreviewKeyEvent hangs off a non-focusable Column, so
                                // dropping focus to the root risks taking Cmd+R and the zoom
                                // chords out of the dispatch path.
                                if (showUrlSuggestions) onDismissSuggestions() else onCancelUrlEditing()
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
 * The tab's status surface: a [message] with Retry and Reset Tab, over a spinner while
 * [isLoading] and a warning icon otherwise.
 *
 * Named for the state rather than for the failure ([BrowserSurface.STARTING] uses it too). It was
 * `BrowserErrorContent`, and the name was load-bearing in the wrong direction: because the only
 * surface that existed was an error one, "still starting" was expressed by putting a message in
 * the error field - which is exactly how a booting browser came to render a warning triangle.
 */
@Composable
internal fun BrowserStatusContent(
    message: String,
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
                text = message,
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
    onAddNewSecret: (websitePrefill: String) -> Unit,
    onFillResult: (CredentialFill.Result) -> Unit = {}
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
                                // The same predicate the list and the menu use. This was a
                                // hand-inlined copy of the old substring rule.
                                isMatched = currentDomain != null &&
                                    secretMatchesDomain(currentDomain, secret),
                                onClick = {
                                    coroutineScope.launch {
                                        val result = browserHandle.fillCredential(secret)
                                        onDismiss()
                                        onFillResult(result)
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
 * Quick create secret dialog for browser integration: create a secret, or correct the one just
 * written by the password suggestor.
 *
 * [existing] is what makes it the second thing. Without it, Edit on the "Saved for github.com"
 * confirmation would create a SECOND entry for the same account, which is worse than not offering
 * Edit at all - the user would be left to work out which of two rows is real.
 */
@Composable
private fun QuickCreateSecretDialog(
    websitePrefill: String,
    secretDataProvider: SecretDataProvider?,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
    onSecretCreated: () -> Unit,
    usernamePrefill: String = "",
    passwordPrefill: String = "",
    existing: SecretEntryData? = null,
) {
    var website by remember { mutableStateOf(websitePrefill) }
    var username by remember { mutableStateOf(usernamePrefill) }
    var password by remember { mutableStateOf(passwordPrefill) }
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
                    if (existing != null) "Edit Saved Login" else "Save Credentials",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    if (existing != null) {
                        "Correct the login that was just saved"
                    } else {
                        "Save login credentials for this website"
                    },
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
                                        // Every field of `existing` is passed back, because
                                        // update_secret is a full replace: it assigns notes and
                                        // expiration_date from its parameters unconditionally,
                                        // re-inserts the tag rows, and DELETEs the whole
                                        // secret_metadata row when twofaEnabled is false. Omitting
                                        // a field destroys it rather than leaving it alone.
                                        val result =
                                            if (existing != null) {
                                                val meta = existing.metadata
                                                secretDataProvider.updateSecret(
                                                    UpdateSecretRequestData(
                                                        secretId = existing.id,
                                                        website = website,
                                                        username = username,
                                                        password = password,
                                                        notes = existing.notes,
                                                        expirationDate = existing.expirationDate,
                                                        tags = existing.tags,
                                                        twofaEnabled = meta?.twofaEnabled ?: false,
                                                        twofaType = meta?.twofaType,
                                                        recoveryCodes = meta?.recoveryCodes ?: emptyList(),
                                                    )
                                                )
                                            } else {
                                                secretDataProvider.createSecret(
                                                    CreateSecretRequestData(
                                                        website = website,
                                                        username = username,
                                                        password = password
                                                    )
                                                )
                                            }
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
