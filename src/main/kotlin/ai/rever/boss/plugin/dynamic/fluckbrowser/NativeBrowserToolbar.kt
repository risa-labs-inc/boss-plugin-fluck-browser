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
    return isNativeBrowserToolbarHosted(handleId)
}

/** The native bar follows the focused pane; inactive split panes share its window claim.
 * Snapshot-backed bridge reads recompose both the toolbar and its popup on claim changes.
 */
@Composable
internal fun isNativeBrowserToolbarHosted(handleId: String?): Boolean {
    val windowId = LocalWindowIdProvider.current?.getWindowId()
    return handleId != null && (BrowserTitleBarBridge.isHosted(handleId) ||
        windowId?.let(BrowserTitleBarBridge::isWindowHosted) == true)
}
