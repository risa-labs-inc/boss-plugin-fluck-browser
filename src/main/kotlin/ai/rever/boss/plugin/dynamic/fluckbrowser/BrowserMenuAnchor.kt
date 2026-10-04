package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserContextMenuInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

internal data class BrowserMenuAnchor(val point: Point, val owner: Window? = null)

/** New hosts optionally expose the remote click through standard AWT interop on the opaque token. */
internal fun browserMenuAnchor(info: BrowserContextMenuInfo, cursor: () -> Point?): BrowserMenuAnchor? {
    val remote = info.menuContext as? MouseEvent
    if (remote != null) {
        val owner = (remote.component as? Window ?: SwingUtilities.getWindowAncestor(remote.component))
            ?.takeIf { it.isShowing } ?: return null
        return BrowserMenuAnchor(remote.locationOnScreen, owner)
    }
    return cursor()?.let { BrowserMenuAnchor(it) }
}
