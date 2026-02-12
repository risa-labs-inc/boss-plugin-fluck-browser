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
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserContextMenuInfo
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle.Callbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
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

    init {
        lifecycle.subscribe(
            callbacks = object : Callbacks {
                override fun onDestroy() {
                    coroutineScope.cancel()
                }
            }
        )
    }

    @Composable
    override fun Content() {
        if (browserService != null && browserService.isAvailable()) {
            // Extract initial URL from config - handle both FluckBrowserTabData and built-in FluckTabInfo
            val initialUrl = getInitialUrl(config)

            FluckBrowserTabContent(
                initialUrl = initialUrl,
                browserService = browserService,
                coroutineScope = coroutineScope,
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
 * Main browser tab content with URL bar, toolbar, and browser view.
 * Shows Dashboard for about:blank pages and browser content otherwise.
 */
@Composable
internal fun FluckBrowserTabContent(
    initialUrl: String,
    browserService: BrowserService,
    coroutineScope: CoroutineScope,
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
    // Browser state - matches bundled browser exactly
    var browserHandle by remember { mutableStateOf<BrowserHandle?>(null) }
    var isInitializing by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var urlBarText by remember { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var isUserEditingUrl by remember { mutableStateOf(false) }
    var lastUserEditTime by remember { mutableStateOf(0L) }
    var pageTitle by remember { mutableStateOf("New Tab") }
    var zoomLevel by remember { mutableStateOf(1.0) }
    var error by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    // Bookmark state - check against actual bookmark provider
    var isBookmarked by remember { mutableStateOf(false) }

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

    // Retry state for browser creation
    var retryCount by remember { mutableStateOf(0) }
    val maxRetries = 3

    // Recovery state - prevents infinite recovery loops
    var recoveryAttempts by remember { mutableStateOf(0) }
    val maxRecoveryAttempts = 5

    // Navigation history state - tracks back/forward history for persistence
    var navigationHistory by remember { mutableStateOf<MutableList<Pair<String, String>>>(mutableListOf()) }
    var historyIndex by remember { mutableStateOf(-1) }

    // Show dashboard for about:blank pages - matches bundled browser exactly
    val currentUrl = urlBarText.text
    val showDashboard = currentUrl.isEmpty() || currentUrl == "about:blank"

    // Security indicator derived from current URL
    val isSecure = currentUrl.startsWith("https://")

    // Lazily created provider - by the time LaunchedEffect runs, the tab should be registered
    var tabUpdateProvider by remember { mutableStateOf<TabUpdateProvider?>(null) }

    // Initialize browser with retry mechanism
    LaunchedEffect(retryCount) {
        if (browserHandle != null) return@LaunchedEffect

        try {
            // Create the TabUpdateProvider now (tab should be registered by this point)
            if (tabUpdateProvider == null) {
                tabUpdateProvider = tabUpdateProviderFactory?.createProvider(tabId, tabTypeId)
            }

            // Apply exponential backoff delay for retries
            if (retryCount > 0) {
                val delayMs = 100L * (1 shl (retryCount - 1)) // 100ms, 200ms, 400ms
                delay(delayMs)
            }

            val handle = browserService.createBrowser(
                BrowserConfig(url = initialUrl)
            )
            if (handle != null) {
                browserHandle = handle
                isInitializing = false

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

                    // Load saved zoom level for this domain (zoom persistence feature)
                    zoomSettingsProvider?.let { provider ->
                        val domain = provider.extractDomain(url)
                        if (domain != null) {
                            val savedZoom = provider.getZoomForDomain(domain)
                            if (savedZoom != null && abs(savedZoom - zoomLevel) > 0.001) {
                                zoomLevel = savedZoom
                                handle.setZoomLevel(savedZoom)
                            }
                        }
                    }

                    // Check if URL is bookmarked
                    bookmarkDataProvider?.let { provider ->
                        val tabConfig = ai.rever.boss.plugin.workspace.TabConfig(
                            type = "fluck",
                            title = pageTitle,
                            url = url
                        )
                        isBookmarked = provider.isTabBookmarked(tabConfig)
                    }
                }
                handle.addTitleListener { title ->
                    pageTitle = title

                    // Update the tab's title in the tab bar via the host
                    tabUpdateProvider?.updateTitle(title)

                    // Add URL to history with title (URL history feature)
                    urlHistoryProvider?.addUrl(urlBarText.text, title)
                }
                handle.addLoadingListener { loading ->
                    isLoading = loading

                    // Save history when page finishes loading
                    val currentUrlText = urlBarText.text
                    if (!loading && currentUrlText.isNotBlank() && currentUrlText != "about:blank") {
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

                // Set up callback for cmd+click and target="_blank" to open in new tab
                handle.setOpenInNewTabCallback { url ->
                    onOpenInNewTab(url)
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
            } else {
                // Retry if we haven't exceeded max retries
                if (retryCount < maxRetries) {
                    retryCount++
                } else {
                    error = "Failed to create browser instance after $maxRetries attempts"
                    isInitializing = false
                }
            }
        } catch (e: Exception) {
            if (retryCount < maxRetries) {
                retryCount++
            } else {
                error = e.message ?: "Unknown error"
                isInitializing = false
            }
        }
    }

    // Browser validity check and recovery mechanism
    // Periodically check if browser is still valid and trigger recovery if needed
    LaunchedEffect(browserHandle) {
        if (browserHandle != null) {
            while (true) {
                delay(2000) // Check every 2 seconds

                val handle = browserHandle
                if (handle != null && !handle.isValid) {
                    // Browser became invalid - trigger recovery
                    if (recoveryAttempts < maxRecoveryAttempts) {
                        recoveryAttempts++
                        println("[FluckBrowser] Browser invalid, triggering recovery (attempt $recoveryAttempts/$maxRecoveryAttempts)")

                        // Save current URL for recovery
                        val currentUrl = urlBarText.text

                        // Reset state to trigger reinitialization
                        browserHandle = null
                        isInitializing = true
                        error = null

                        // Restore URL after small delay
                        delay(100)
                        if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                            urlBarText = TextFieldValue(currentUrl, TextRange(currentUrl.length))
                        }

                        // Increment retry count to trigger LaunchedEffect
                        retryCount++
                    } else {
                        // Max recovery attempts reached
                        error = "Browser recovery failed after $maxRecoveryAttempts attempts. Please close and reopen this tab."
                        browserHandle = null
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

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            browserHandle?.dispose()
        }
    }

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
        // URL bar with navigation controls
        BrowserToolbar(
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
                        type = "fluck",
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
                        // Add bookmark to default collection
                        val bookmark = Bookmark(
                            tabConfig = tabConfig,
                            workspaceName = "Default"
                        )
                        provider.addBookmark("Favorites", bookmark)
                        isBookmarked = true
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
            onFocusLost = {
                // Hide dropdown when focus is lost (with delay to allow click events)
                coroutineScope.launch {
                    delay(200)
                    showUrlSuggestions = false
                    isUserEditingUrl = false
                }
            }
        )

        // Loading indicator
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colors.primary
            )
        }

        // Browser content or Dashboard
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isInitializing -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colors.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (retryCount > 0) "Retrying... ($retryCount/$maxRetries)" else "Initializing browser...",
                                color = MaterialTheme.colors.onBackground,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                error != null -> {
                    BrowserErrorContent(
                        error = error!!,
                        onRetry = {
                            error = null
                            retryCount = 0
                            isInitializing = true
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
                    // Wrap browser content with mouse button handler (back/forward/middle-click)
                    @OptIn(ExperimentalComposeUiApi::class)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onPointerEvent(PointerEventType.Press) { event ->
                                // Access native AWT MouseEvent for extended button detection
                                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent

                                // Handle middle-click to close tab (button 2 is middle mouse button in AWT)
                                if (awtEvent?.button == 2) {
                                    onCloseTab()
                                    event.changes.forEach { it.consume() }
                                    return@onPointerEvent
                                }

                                // Handle mouse back button
                                // Windows/macOS: awtButton=4, Linux: awtButton=6 or 8 (varies by mouse)
                                if (awtEvent?.button in listOf(4, 6, 8)) {
                                    if (browserHandle?.canGoBack() == true) {
                                        browserHandle?.goBack()
                                    }
                                    event.changes.forEach { it.consume() }
                                    return@onPointerEvent
                                }

                                // Handle mouse forward button
                                // Windows/macOS: awtButton=5, Linux: awtButton=7 or 9 (varies by mouse)
                                if (awtEvent?.button in listOf(5, 7, 9)) {
                                    if (browserHandle?.canGoForward() == true) {
                                        browserHandle?.goForward()
                                    }
                                    event.changes.forEach { it.consume() }
                                    return@onPointerEvent
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
                                        type = "fluck",
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
                                        // Add bookmark to default collection
                                        val bookmark = Bookmark(
                                            tabConfig = tabConfig,
                                            workspaceName = "Default"
                                        )
                                        provider.addBookmark("Favorites", bookmark)
                                        isBookmarked = true
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index == selectedDropdownIndex)
                                        MaterialTheme.colors.primary.copy(alpha = 0.1f)
                                    else
                                        MaterialTheme.colors.surface
                                )
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
                        }
                    }
                }
            }
        }
    } // End Box
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
            onClick = { }
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
            onClick = { }
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

/**
 * Browser toolbar with URL bar, navigation controls, bookmark star, zoom indicator, and URL autocomplete.
 * Design matches the main implementation with inline autocomplete and keyboard navigation.
 */
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
    onFocusLost: () -> Unit = {}
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
        // Back button
        IconButton(
            onClick = onBack,
            enabled = canGoBack,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) Color(0xFFCCCCCC) else Color(0xFF666666),
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
                tint = if (canGoForward) Color(0xFFCCCCCC) else Color(0xFF666666),
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
                tint = Color(0xFFCCCCCC),
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
                                // Matches bundled browser exactly
                                val urlToLoad = when {
                                    selectedDropdownIndex >= 0 && selectedDropdownIndex < urlSuggestions.size -> {
                                        // Use selected dropdown item
                                        urlSuggestions[selectedDropdownIndex].url
                                    }
                                    else -> {
                                        // Use what the user actually typed
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
                                tint = Color(0xFF4CAF50),
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
 * Error content when browser fails to load.
 * Includes a retry button.
 */
@Composable
internal fun BrowserErrorContent(
    error: String,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp,
            backgroundColor = MaterialTheme.colors.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colors.error,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Browser Error",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = error,
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                if (onRetry != null) {
                    Spacer(modifier = Modifier.height(16.dp))

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
                        Text("Retry")
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
                            text = "The browser service is not available.",
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

private val BossDarkBackground = Color(0xFF1E1F22)
private val BossDarkBorder = Color(0xFF3C3F41)
private val BossDarkTextSecondary = Color(0xFF9E9E9E)

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
            color = Color(0xFF2D2D2D),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Save Credentials",
                    color = Color.White,
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
                        color = Color(0xFFFF5252),
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
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save", color = Color.White)
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
                textStyle = MaterialTheme.typography.body2.copy(color = Color.White),
                cursorBrush = SolidColor(Color(0xFF4CAF50)),
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
            .background(Color(0xFF1E1E1E))
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
                tint = Color(0xFF888888)
            )
            Text(
                text = "Tab is in fullscreen mode",
                style = MaterialTheme.typography.h6,
                color = Color.White
            )
            Text(
                text = "Click here or press ESC to exit fullscreen",
                style = MaterialTheme.typography.body2,
                color = Color(0xFF888888)
            )
        }
    }
}
