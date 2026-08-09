package ai.rever.boss.plugin.dynamic.fluckbrowser.menu

import java.awt.AWTEvent
import java.awt.Dialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * A menu described in terms an operating-system menu can render: a label, an enabled flag, an
 * optional shortcut, separators and submenus. Nothing else - no icon, no colour, no custom row.
 */
internal sealed interface NativeMenuNode {
    data class Item(
        val label: String,
        val enabled: Boolean = true,
        /** A virtual key code (`KeyEvent.VK_*`), shown as the platform's menu-key glyph. */
        val shortcutKeyCode: Int? = null,
        val shortcutShift: Boolean = false,
        val action: () -> Unit = {},
    ) : NativeMenuNode

    data object Separator : NativeMenuNode

    data class Submenu(
        val label: String,
        val children: List<NativeMenuNode>,
    ) : NativeMenuNode
}

/**
 * Native menus are enabled on macOS only.
 *
 * macOS is the platform whose behaviour was measured. Windows is excluded because
 * `WPopupMenuPeer` appears to reach `::TrackPopupMenu` through `AwtToolkit::SyncCall`, which
 * would block the EDT for as long as the menu is open, and because `TrackPopupMenu` renders the
 * classic Win32 menu that does not follow system dark mode. Linux is excluded because the AWT
 * peer there is the Motif-era XAWT menu, which ignores GTK. Both keep the drawn Swing menu.
 */
internal fun shouldUseNativeMenus(
    settingEnabled: Boolean,
    isMacOs: Boolean,
): Boolean = settingEnabled && isMacOs

/**
 * Normalise a menu for native rendering: drop leading, trailing and consecutive separators, drop
 * empty submenus, and double `&` on Windows where `AppendMenu` reads it as a mnemonic prefix.
 *
 * Menus assembled with `if` guards routinely produce dangling separators, and a native menu draws
 * those as stray lines rather than ignoring them.
 */
internal fun planNativeMenu(
    nodes: List<NativeMenuNode>,
    isWindows: Boolean = false,
): List<NativeMenuNode> {
    val planned = ArrayList<NativeMenuNode>(nodes.size)
    for (node in nodes) {
        when (node) {
            is NativeMenuNode.Separator ->
                if (planned.isNotEmpty() && planned.last() !is NativeMenuNode.Separator) {
                    planned += NativeMenuNode.Separator
                }

            is NativeMenuNode.Item ->
                planned += node.copy(label = escapeNativeLabel(node.label, isWindows))

            is NativeMenuNode.Submenu -> {
                val children = planNativeMenu(node.children, isWindows)
                if (children.isNotEmpty()) {
                    planned +=
                        NativeMenuNode.Submenu(
                            label = escapeNativeLabel(node.label, isWindows),
                            children = children,
                        )
                }
            }
        }
    }
    while (planned.lastOrNull() is NativeMenuNode.Separator) planned.removeAt(planned.lastIndex)
    return planned
}

/** See the mnemonic note on [planNativeMenu]. */
internal fun escapeNativeLabel(
    label: String,
    isWindows: Boolean,
): String = if (isWindows) label.replace("&", "&&") else label

/** Cached once: `os.name` cannot change while the process runs. */
internal object OsFamily {
    private val name: String = System.getProperty("os.name")?.lowercase().orEmpty()
    val isMac: Boolean = name.contains("mac")
    val isWindows: Boolean = name.contains("windows")
}

/**
 * Shows a real operating-system menu, using `java.awt.PopupMenu` - which on macOS is peered by
 * `sun.lwawt.macosx.CPopupMenu` onto an actual `NSMenu`.
 *
 * Four behaviours were **measured** rather than read out of the JDK, and each shapes this code:
 *
 * 1. `show()` does **not** block. It returns immediately and the EDT stays live, so its return is
 *    not a dismissal signal.
 * 2. **Nothing cancels an open menu** - hiding, removing and disposing the invoker all leave it
 *    tracking. So [hide] cannot dismiss; correctness rests on the generation fence, which makes a
 *    lingering menu's items inert.
 * 3. **There is no dismissal event on any AWT mask.** But an open menu holds the input grab and
 *    lets nothing through, while dismissal produces an immediate burst, so inferring dismissal
 *    from the next input event cannot fire early and does fire promptly.
 * 4. `MenuShortcut` is **display-only** - no live key equivalent, so a glyph cannot double-fire
 *    with the page's own key handling. Items can be disabled while the menu is open.
 *
 * Fact 3 is specifically an NSMenu-tracking property, and is the first thing to re-measure if
 * [shouldUseNativeMenus] ever widens past macOS.
 */
internal object NativeContextMenu {
    private var attached: Pair<Window, java.awt.PopupMenu>? = null
    private val watcher = DismissWatcher()

    /**
     * Bumped on every show and on [hide]. Item actions are fenced on the generation they were
     * built for, so a menu that outlives the tab that opened it cannot act on state that has
     * gone away.
     */
    private var generation: Long = 0L

    fun isSupported(): Boolean = OsFamily.isMac

    /**
     * Returns false when nothing was shown, in which case the caller must draw its own menu.
     *
     * [screenX]/[screenY] are the exact coordinates Chromium reported for the right-click, which
     * is more precise than reading the cursor: the callback runs after JavaScript probes, by
     * which time the pointer may have moved.
     */
    fun show(
        screenX: Int,
        screenY: Int,
        nodes: List<NativeMenuNode>,
        onDismiss: () -> Unit = {},
    ): Boolean {
        if (!isSupported()) return false
        val planned = planNativeMenu(nodes, OsFamily.isWindows)
        if (planned.isEmpty()) return false

        generation += 1
        val shown = generation
        val isCurrent = { generation == shown }

        onEdt {
            val at = Point(screenX, screenY)
            val invoker = resolveInvoker(at)
            if (invoker == null) {
                onDismiss()
                return@onEdt
            }

            detach()
            val popup = java.awt.PopupMenu()
            var myWatcher: AWTEventListener? = null
            val dismissed = {
                detachIf(popup)
                watcher.clearIf(myWatcher)
                onDismiss()
            }
            materialize(popup, planned, isCurrent, dismissed)
            invoker.add(popup)
            attached = invoker to popup

            val local = at.toInvokerCoordinates(invoker)
            popup.show(invoker, local.x, local.y)
            myWatcher = watcher.install(dismissed)
            // No watcher means no dismissal signal will ever arrive; report it now rather than
            // leaving the caller believing the menu is still up.
            if (myWatcher == null) dismissed()
        }
        return true
    }

    /**
     * Advisory: an open menu cannot be dismissed programmatically. This greys the orphan out and
     * lets go of it, so it cannot act on state the tab has already torn down.
     */
    fun hide() {
        generation += 1
        onEdt {
            watcher.clear()
            attached?.let { (_, menu) -> runCatching { disableAll(menu) } }
            detach()
        }
    }

    private fun Point?.toInvokerCoordinates(invoker: Window): Point {
        val origin = runCatching { invoker.locationOnScreen }.getOrNull()
        val screen = this
        return if (origin == null || screen == null) {
            Point(0, 0)
        } else {
            Point(screen.x - origin.x, screen.y - origin.y)
        }
    }

    private fun resolveInvoker(at: Point?): Window? {
        KeyboardFocusManager
            .getCurrentKeyboardFocusManager()
            .focusedWindow
            ?.takeIf { it.isShowing }
            ?.let { return it }

        val candidates =
            runCatching { Window.getWindows() }.getOrNull()?.map {
                InvokerCandidate(it, it is Frame || it is Dialog, it.isShowing, it.isActive, it.bounds)
            }.orEmpty()
        return pickInvoker(candidates, at)?.window
    }

    private fun materialize(
        menu: java.awt.Menu,
        nodes: List<NativeMenuNode>,
        isCurrent: () -> Boolean,
        onDismiss: () -> Unit,
    ) {
        nodes.forEach { node ->
            when (node) {
                is NativeMenuNode.Separator -> menu.addSeparator()
                is NativeMenuNode.Item -> menu.add(node.toAwtItem(isCurrent, onDismiss))
                is NativeMenuNode.Submenu ->
                    menu.add(
                        java.awt.Menu(node.label).also {
                            materialize(it, node.children, isCurrent, onDismiss)
                        },
                    )
            }
        }
    }

    /** getItem returns the `java.awt.Menu` for a submenu, so recurse to reach its children. */
    private fun disableAll(menu: java.awt.Menu) {
        for (i in 0 until menu.itemCount) {
            val item = menu.getItem(i)
            item.isEnabled = false
            if (item is java.awt.Menu) disableAll(item)
        }
    }

    private fun detach() {
        attached?.let { (owner, menu) -> runCatching { owner.remove(menu) } }
        attached = null
    }

    private fun detachIf(popup: java.awt.PopupMenu) {
        if (attached?.second === popup) detach()
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    internal val dismissGraceMs: Long get() = DismissWatcher.DISMISS_GRACE_MS

    internal fun isDismissalEvent(
        eventId: Int,
        elapsedMs: Long,
    ): Boolean = DismissWatcher.isDismissalEvent(eventId, elapsedMs)
}

private fun NativeMenuNode.Item.toAwtItem(
    isCurrent: () -> Boolean,
    onDismiss: () -> Unit,
): java.awt.MenuItem {
    val node = this
    return java.awt.MenuItem(node.label).apply {
        isEnabled = node.enabled
        node.shortcutKeyCode?.let { setShortcut(java.awt.MenuShortcut(it, node.shortcutShift)) }
        addActionListener {
            if (isCurrent()) node.action()
            onDismiss()
        }
    }
}

/**
 * Knows when a native menu has closed.
 *
 * Its own class because inferring dismissal is a separate concern from putting a popup on screen,
 * and it owns process-wide state (an AWT-wide listener) that must be cleaned up exactly.
 */
private class DismissWatcher {
    private var current: AWTEventListener? = null

    /**
     * `WINDOW_EVENT_MASK` is included because dismissing by switching applications produces no
     * input event at all. Only [AWTEvent.getID] is inspected; no event contents are read, which
     * matters because this is a process-wide listener installed by a library.
     */
    fun install(onDismiss: () -> Unit): AWTEventListener? {
        clear()
        val armedAt = System.currentTimeMillis()
        val toolkit = runCatching { Toolkit.getDefaultToolkit() }.getOrNull() ?: return null
        val listener =
            object : AWTEventListener {
                override fun eventDispatched(event: AWTEvent) {
                    if (!isDismissalEvent(event.id, System.currentTimeMillis() - armedAt)) return
                    runCatching { toolkit.removeAWTEventListener(this) }
                    if (current === this) current = null
                    onDismiss()
                }
            }
        current = listener
        // Requires AWTPermission("listenToAllAWTEvents"). If a host's policy refuses, lose the
        // dismissal signal rather than the menu.
        return runCatching {
            toolkit.addAWTEventListener(
                listener,
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or
                    AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK,
            )
            listener
        }.getOrElse {
            current = null
            null
        }
    }

    fun clear() {
        current?.let { runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(it) } }
        current = null
    }

    fun clearIf(listener: AWTEventListener?) {
        if (listener != null && current === listener) clear()
    }

    companion object {
        const val DISMISS_GRACE_MS = 120L

        /**
         * Nothing inside the grace window counts, since `show()` is posted to the EDT and the OS
         * grab is not established the instant it returns. `MOUSE_RELEASED` never counts, because
         * wall-clock alone is not enough: under a busy EDT the opening right-click's own release
         * can be dispatched well after the window expires. Nothing is lost by filtering it - the
         * measured dismissal burst is `MOUSE_EXITED` / key-release / `MOUSE_ENTERED`, and a
         * click-away has its press swallowed by the menu, so a release is never the only signal.
         */
        fun isDismissalEvent(
            eventId: Int,
            elapsedMs: Long,
        ): Boolean = elapsedMs >= DISMISS_GRACE_MS && eventId != MouseEvent.MOUSE_RELEASED
    }
}

/** The properties [pickInvoker] ranks on, lifted off AWT so the rule can be tested headlessly. */
internal data class InvokerCandidate<T>(
    val window: T,
    val isFrameOrDialog: Boolean,
    val isShowing: Boolean,
    val isActive: Boolean,
    val bounds: Rectangle,
)

/**
 * `Window.getWindows()` is not in z-order and also returns the heavyweight windows Swing creates
 * for popups, so it is filtered to real frames and dialogs. Smallest-area-first is a proxy for
 * topmost: a popup is owned by its invoker, so choosing the window underneath would place the
 * menu behind the one on top.
 */
internal fun <T> pickInvoker(
    candidates: List<InvokerCandidate<T>>,
    at: Point?,
): InvokerCandidate<T>? =
    candidates
        .filter { it.isFrameOrDialog && it.isShowing }
        // Rectangle.contains is half-open on the right/bottom edges, so a click on the very edge
        // can match nothing. Falling back keeps the mild degradation (menu on the wrong window)
        // rather than a worse one (right-click silently does nothing).
        .let { eligible -> eligible.filter { at == null || it.bounds.contains(at) }.ifEmpty { eligible } }
        .minWithOrNull(
            compareByDescending<InvokerCandidate<T>> { it.isActive }
                .thenBy { it.bounds.width.toLong() * it.bounds.height.toLong() },
        )
