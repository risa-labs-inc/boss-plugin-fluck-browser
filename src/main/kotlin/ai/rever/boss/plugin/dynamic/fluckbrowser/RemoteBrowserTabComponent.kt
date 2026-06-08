package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.HostBrowserProvider
import ai.rever.boss.plugin.api.HostBrowserSession
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage

/**
 * Remote browser UI for the fluck-browser plugin.
 *
 * This was previously hosted inside the platform (composeApp) and reached via
 * reflection. It now lives in the plugin and talks to the host only through the
 * typed [HostBrowserProvider] / [HostBrowserSession] seam — no gRPC types, no
 * `HostRegistry` reflection. The platform implementation streams JPEG frames
 * over the server's BrowserService; this component just draws them and forwards
 * input.
 */
@OptIn(FlowPreview::class)
@Composable
fun RemoteBrowserTabComponent(
    provider: HostBrowserProvider,
    tabId: String,
    initialUrl: String,
    modifier: Modifier = Modifier,
) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var urlText by remember { mutableStateOf(initialUrl) }
    var session by remember { mutableStateOf<HostBrowserSession?>(null) }

    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Mouse-move throttling. Compose emits pointer-move events at the display's
    // refresh rate; each becomes an input dispatch on the server. Push moves
    // into a SharedFlow and sample at ~30 Hz with trailing-edge delivery so the
    // final position still lands when the cursor stops. Discrete events
    // (press/release/scroll) bypass throttling and emit a synchronous final
    // move so the click lands at the latest position.
    val moveFlow = remember {
        MutableSharedFlow<Pair<Int, Int>>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    // Translate Canvas/container pixel space to the server's (capped) viewport
    // space so clicks land correctly on windows larger than the render cap.
    fun toRenderSpace(x: Int, y: Int): Pair<Int, Int> {
        val cs = containerSize
        if (cs.width <= 0 || cs.height <= 0) return x to y
        val rs = capRenderSize(cs)
        if (rs.width == cs.width && rs.height == cs.height) return x to y
        val rx = (x.toFloat() * rs.width / cs.width).toInt()
        val ry = (y.toFloat() * rs.height / cs.height).toInt()
        return rx to ry
    }

    fun sendInput(type: String, x: Int, y: Int, dx: Int = 0, dy: Int = 0) {
        val s = session ?: return
        val (rx, ry) = toRenderSpace(x, y)
        scope.launch { s.sendInput(type, rx, ry, dx, dy) }
    }

    fun sendMouseEvent(type: String, x: Int, y: Int) {
        if (type == "mousemove") {
            moveFlow.tryEmit(x to y)
        } else {
            sendInput("mousemove", x, y)
            sendInput(type, x, y)
        }
    }

    fun sendKeyEvent(type: String, keyText: String) {
        val s = session ?: return
        scope.launch { s.sendInput(type = type, x = 0, y = 0, keyText = keyText) }
    }

    fun navigateTo(rawUrl: String) {
        val normalized = normalizeUrl(rawUrl)
        urlText = normalized
        val s = session ?: return
        scope.launch { runCatching { s.navigate(normalized) } }
    }

    LaunchedEffect(provider, tabId, initialUrl) {
        // Wait for a non-zero size from layout, then open the session at the
        // capped render size.
        val rawSize = snapshotFlow { containerSize }.first { it.width > 0 && it.height > 0 }
        val size = capRenderSize(rawSize)

        val s = provider.openSession(tabId, initialUrl, size.width, size.height) ?: return@LaunchedEffect
        session = s

        // Frame pipeline: receive frames on one coroutine, decode on another,
        // joined by a CONFLATED channel that always keeps only the freshest
        // frame. JPEG decode goes through Skia (bundled with Compose Desktop).
        val frameChannel = Channel<ByteArray>(Channel.CONFLATED)

        val tunnelJob = launch {
            try {
                s.frames().collect { frameChannel.trySend(it) }
            } finally {
                frameChannel.close()
            }
        }

        val decodeJob = launch {
            for (jpegBytes in frameChannel) {
                runCatching {
                    imageBitmap = SkiaImage.makeFromEncoded(jpegBytes).toComposeImageBitmap()
                }
            }
        }

        // On (re)attach, sync the URL bar to whatever page the server is
        // actually showing (reattach lands on the last-navigated page, not the
        // stale initialUrl). Small delay so the server commits the reattach.
        launch {
            runCatching {
                delay(200)
                val serverUrl = s.currentUrl()
                if (serverUrl.isNotBlank() && serverUrl != "about:blank") {
                    urlText = serverUrl
                }
            }
        }

        // Drain mousemove at ~30 Hz; trailing-edge keeps hover working.
        val moveJob = launch {
            moveFlow.sample(MOUSE_MOVE_INTERVAL_MS).collect { (x, y) ->
                sendInput("mousemove", x, y)
            }
        }

        // Send resize messages dynamically without restarting the stream.
        // Debounced so a multi-step layout pass doesn't thrash the server, and
        // distinct-only so identical sizes are ignored.
        val resizeJob = launch {
            snapshotFlow { containerSize }
                .map { capRenderSize(it) }
                .filter { it.width > 0 && it.height > 0 }
                .distinctUntilChanged()
                .debounce(150)
                .collect { newSize -> s.resize(newSize.width, newSize.height) }
        }

        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        RemoteUrlBar(
            url = urlText,
            onUrlChange = { urlText = it },
            onSubmit = { navigateTo(urlText) },
            onBack = { session?.let { s -> scope.launch { runCatching { s.goBack() } } } },
            onForward = { session?.let { s -> scope.launch { runCatching { s.goForward() } } } },
            onReload = { session?.let { s -> scope.launch { runCatching { s.reload() } } } },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    val type = when (keyEvent.type) {
                        KeyEventType.KeyDown -> "keydown"
                        KeyEventType.KeyUp -> "keyup"
                        else -> return@onKeyEvent false
                    }
                    sendKeyEvent(type, keyEvent.key.toString())
                    true
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val position = change.position
                            when (event.type) {
                                PointerEventType.Press ->
                                    sendMouseEvent("mousedown", position.x.toInt(), position.y.toInt())
                                PointerEventType.Release ->
                                    sendMouseEvent("mouseup", position.x.toInt(), position.y.toInt())
                                PointerEventType.Move ->
                                    sendMouseEvent("mousemove", position.x.toInt(), position.y.toInt())
                                PointerEventType.Scroll -> {
                                    val scrollAmount = change.scrollDelta
                                    sendInput(
                                        "scroll",
                                        position.x.toInt(),
                                        position.y.toInt(),
                                        (scrollAmount.x * 20).toInt(),
                                        (scrollAmount.y * 20).toInt(),
                                    )
                                }
                            }
                        }
                    }
                }
        ) {
            imageBitmap?.let { bitmap ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Scale the (capped-resolution) frame to fill the entire
                    // canvas. capRenderSize preserves the container aspect ratio,
                    // so this upscales uniformly rather than letterboxing — the
                    // viewport fills the tab even on high-DPI / 4K displays.
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
            } ?: Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun RemoteUrlBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colors.onSurface)
        }
        IconButton(onClick = onForward, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", tint = MaterialTheme.colors.onSurface)
        }
        IconButton(onClick = onReload, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = MaterialTheme.colors.onSurface)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.weight(1f).heightIn(min = 36.dp),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = MaterialTheme.colors.onSurface,
                backgroundColor = MaterialTheme.colors.background,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }, onDone = { onSubmit() }),
        )
    }
}

/**
 * Maximum dimensions we ask the server to render at. Capping is a latency /
 * bandwidth optimization: above ~1.6 megapixels visual quality gains are tiny
 * once scaled to fit the canvas, but each pixel costs JPEG encode + network +
 * decode. Aspect ratio is preserved so the bitmap stretches uniformly.
 */
private const val MAX_RENDER_WIDTH = 1600
private const val MAX_RENDER_HEIGHT = 1000

/** Minimum interval between outgoing mousemove events (~30 Hz). */
private const val MOUSE_MOVE_INTERVAL_MS = 33L

private fun capRenderSize(raw: IntSize): IntSize {
    if (raw.width <= 0 || raw.height <= 0) return raw
    if (raw.width <= MAX_RENDER_WIDTH && raw.height <= MAX_RENDER_HEIGHT) return raw
    val scale = minOf(
        MAX_RENDER_WIDTH.toFloat() / raw.width,
        MAX_RENDER_HEIGHT.toFloat() / raw.height,
    )
    return IntSize((raw.width * scale).toInt(), (raw.height * scale).toInt())
}

/**
 * Loose URL normalization for the URL bar. Domain-looking input gets https://,
 * anything with a scheme is left alone, otherwise route through Google search.
 */
private fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") ||
        lower.startsWith("file://") || lower.startsWith("about:") ||
        lower.startsWith("chrome://")
    ) {
        return trimmed
    }
    val looksLikeDomain = !trimmed.contains(' ') && trimmed.contains('.')
    return if (looksLikeDomain) "https://$trimmed"
    else "https://www.google.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
}
