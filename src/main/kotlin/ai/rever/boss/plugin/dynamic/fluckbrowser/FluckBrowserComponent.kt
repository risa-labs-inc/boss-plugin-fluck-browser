package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle.Callbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fluck Browser panel component (Dynamic Plugin)
 *
 * Uses BrowserService from host for full browser functionality.
 * Features:
 * - URL bar with navigation
 * - Back/forward/reload controls
 * - Title and favicon updates
 * - Download integration (via host)
 */
class FluckBrowserComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val browserService: BrowserService?
) : PanelComponentWithUI, ComponentContext by ctx {

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
            FluckBrowserContent(
                browserService = browserService,
                coroutineScope = coroutineScope
            )
        } else {
            // Fallback stub content when browser service not available
            FluckBrowserStubContent()
        }
    }
}

/**
 * Main browser content with URL bar and browser view.
 */
@Composable
internal fun FluckBrowserContent(
    browserService: BrowserService,
    coroutineScope: CoroutineScope
) {
    // Browser state
    var browserHandle by remember { mutableStateOf<BrowserHandle?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var urlBarText by remember { mutableStateOf("https://www.google.com") }
    var pageTitle by remember { mutableStateOf("New Tab") }
    var error by remember { mutableStateOf<String?>(null) }

    // Initialize browser
    LaunchedEffect(Unit) {
        try {
            val handle = browserService.createBrowser(
                BrowserConfig(url = currentUrl)
            )
            if (handle != null) {
                browserHandle = handle
                isLoading = false

                // Add listeners
                handle.addNavigationListener { url ->
                    currentUrl = url
                    urlBarText = url
                }
                handle.addTitleListener { title ->
                    pageTitle = title
                }
            } else {
                error = "Failed to create browser instance"
                isLoading = false
            }
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
            isLoading = false
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            browserHandle?.dispose()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        // URL bar
        BrowserUrlBar(
            urlBarText = urlBarText,
            onUrlBarTextChange = { urlBarText = it },
            onNavigate = { url ->
                coroutineScope.launch {
                    browserHandle?.loadUrl(url)
                }
            },
            canGoBack = browserHandle?.canGoBack() ?: false,
            canGoForward = browserHandle?.canGoForward() ?: false,
            onBack = { browserHandle?.goBack() },
            onForward = { browserHandle?.goForward() },
            onReload = { browserHandle?.reload() }
        )

        // Browser content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF4A90E2))
                    }
                }
                error != null -> {
                    BrowserErrorContent(error = error!!)
                }
                browserHandle != null -> {
                    browserHandle?.Content()
                }
            }
        }
    }
}

/**
 * URL bar with navigation controls.
 */
@Composable
internal fun BrowserUrlBar(
    urlBarText: String,
    onUrlBarTextChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2D2D2D))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(
            onClick = onBack,
            enabled = canGoBack,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) Color(0xFFCCCCCC) else Color(0xFF666666),
                modifier = Modifier.size(16.dp)
            )
        }

        // Forward button
        IconButton(
            onClick = onForward,
            enabled = canGoForward,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Forward",
                tint = if (canGoForward) Color(0xFFCCCCCC) else Color(0xFF666666),
                modifier = Modifier.size(16.dp)
            )
        }

        // Reload button
        IconButton(
            onClick = onReload,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reload",
                tint = Color(0xFFCCCCCC),
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // URL text field
        BasicTextField(
            value = urlBarText,
            onValueChange = onUrlBarTextChange,
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyUp) {
                        val url = if (urlBarText.startsWith("http://") || urlBarText.startsWith("https://")) {
                            urlBarText
                        } else {
                            "https://$urlBarText"
                        }
                        onNavigate(url)
                        true
                    } else {
                        false
                    }
                },
            textStyle = TextStyle(
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(Color(0xFF4A90E2)),
            singleLine = true
        )
    }
}

/**
 * Error content when browser fails to load.
 */
@Composable
internal fun BrowserErrorContent(error: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp,
            backgroundColor = Color(0xFF2B2D30)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Browser Error",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = error,
                    fontSize = 14.sp,
                    color = Color(0xFFCCCCCC),
                    textAlign = TextAlign.Center
                )
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
