package ai.rever.boss.plugin.dynamic.fluckbrowser

import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.MouseInfo
import androidx.compose.foundation.ContextMenuItem as ComposeContextMenuItem

/**
 * Renders Compose's built-in text context menus (the Cut/Copy/Paste menu on a `BasicTextField`)
 * through [SwingContextMenu] instead of a Compose popup.
 *
 * Right-clicking the URL bar produced a menu that was **cropped at the browser's rendering area**.
 * Compose's default representation is a lightweight popup drawn into the Compose scene, and under
 * JxBrowser `HARDWARE_ACCELERATED` Chromium composites its own native window over that scene - so the
 * part of the menu extending across the page was painted behind it. The page's own context menu never
 * had this problem because it already goes through [SwingContextMenu], a `JPopupMenu` with
 * `isLightWeightPopupEnabled = false`. This makes the URL bar's menu use the same path, which is also
 * why the two now look alike.
 *
 * Positioned from [MouseInfo] rather than from the state's anchor rect: a context menu belongs at the
 * pointer, and reading the cursor avoids converting Compose coordinates into screen space through the
 * content pane. `BossPopup` exists for the anchored case and is used for the suggestion list instead.
 *
 * Provide it with `CompositionLocalProvider(LocalContextMenuRepresentation provides ...)`. Deliberately
 * NOT `BossPopup`: Compose owns these menus' items and lifecycle, and the representation interface is
 * the only seam, so the Swing route the plugin already ships is the smaller change.
 */
internal object SwingContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ComposeContextMenuItem>,
    ) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        // Keyed on the status instance so a second right-click re-shows the menu at the new cursor
        // position rather than being treated as the same open menu.
        DisposableEffect(status) {
            val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            SwingContextMenu.show(
                screenX = cursor?.x ?: 0,
                screenY = cursor?.y ?: 0,
                items =
                    items().map { item ->
                        ContextMenuItem(text = item.label, onClick = item.onClick)
                    },
                onDismiss = { state.status = ContextMenuState.Status.Closed },
            )
            onDispose { SwingContextMenu.hide() }
        }
    }
}
