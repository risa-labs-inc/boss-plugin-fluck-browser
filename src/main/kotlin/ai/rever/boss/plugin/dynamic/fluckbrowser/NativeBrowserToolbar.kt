package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.LocalWindowIdProvider
import ai.rever.boss.plugin.browser.BrowserAddressBarState
import ai.rever.boss.plugin.browser.BrowserTitleBarBridge
import ai.rever.boss.plugin.browser.BrowserTitleBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/** Publish even before hosting, so the native toolbar can mount before this toolbar disappears. */
@Composable
internal fun NativeBrowserToolbar(
    handleId: String?,
    url: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    bookmarked: Boolean,
    navigate: (String) -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    reloadOrStop: () -> Unit,
    bookmark: () -> Unit,
    share: (() -> Unit)?,
    address: BrowserAddressBarState,
): Boolean {
    val windowId = LocalWindowIdProvider.current?.getWindowId()
    val hostedWindow = windowId?.let(BrowserTitleBarBridge::isWindowHosted) == true
    if (handleId == null) return false
    val owner = remember(handleId) { Any() }
    SideEffect {
        BrowserTitleBarBridge.publish(handleId, owner, BrowserTitleBarState(
            url, canGoBack, canGoForward, loading, bookmarked,
            navigate, back, forward, reloadOrStop, bookmark, share, address,
        ))
    }
    DisposableEffect(handleId) {
        onDispose { BrowserTitleBarBridge.remove(handleId, owner) }
    }
    return BrowserTitleBarBridge.isHosted(handleId) || hostedWindow
}
