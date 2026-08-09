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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
 * Whether a native menu can be produced at all.
 *
 * Lifted out of [NativeContextMenu.show] because `isSupported()` short-circuits everything on a
 * non-macOS machine, so a test that goes through `show()` on CI only ever re-tests the platform
 * gate and can never reach the EDT or empty-plan guards it claims to cover.
 */
internal fun canShowNatively(
    isSupported: Boolean,
    isEventDispatchThread: Boolean,
    plannedSize: Int,
): Boolean = isSupported && isEventDispatchThread && plannedSize > 0

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
    // EDT-only. show() declines off the EDT and hide() posts, so both are only ever mutated
    // there; @Volatile keeps the hide() read that guards the bump honest either way.
    @Volatile
    private var attached: Pair<Window, java.awt.PopupMenu>? = null
    private val watcher = DismissWatcher()

    /**
     * Bumped on every show and on [hide]. Item actions are fenced on the generation they were
     * built for, so a menu that outlives the tab that opened it cannot act on state that has
     * gone away.
     */
    // Atomic, not @Volatile: hide() bumps outside onEdt so teardown from any thread closes the
    // fence immediately, which makes `generation += 1` followed by a separate read two operations
    // a concurrent show() can interleave with - landing on generation == shown for a menu that was
    // already invalidated. That is precisely the fail-open this fence must never have.
    private val generation = AtomicLong(0)

    fun isSupported(): Boolean =
        shouldUseNativeMenus(settingEnabled = nativeMenusEnabled, isMacOs = OsFamily.isMac)

    /**
     * Escape hatch, since a plugin cannot be patched as quickly as it ships: set
     * `-Dboss.nativeContextMenus=false` (or `BOSS_NATIVE_CONTEXT_MENUS=false`) to fall back to
     * the drawn menu everywhere.
     */
    private val nativeMenusEnabled: Boolean by lazy {
        val raw =
            System.getProperty("boss.nativeContextMenus")
                ?: System.getenv("BOSS_NATIVE_CONTEXT_MENUS")
        raw?.lowercase() != "false"
    }

    /**
     * Returns false when nothing was shown, in which case the caller must draw its own menu.
     *
     * [screenX]/[screenY] are screen coordinates supplied by the caller. Today that caller reads
     * the pointer at menu-build time, so this is not more precise than reading the cursor here -
     * the callback runs after the JavaScript probes, and every hop between the click and the read
     * is drift. Taking them as a parameter is what would let a caller pass Chromium's own
     * reported click position instead, which `BrowserContextMenuInfo` would have to carry.
     */
    fun show(
        screenX: Int,
        screenY: Int,
        nodes: List<NativeMenuNode>,
        onDismiss: () -> Unit = {},
    ): Boolean {
        // Everything below touches AWT peers and must decide synchronously, because the caller
        // uses the return value to choose between this and its own menu. Posting the work would
        // mean returning true before knowing whether a menu appears, and a later failure would
        // then leave the right-click doing nothing at all - the outcome the fallback exists to
        // prevent. Callers are on the EDT (Compose's main dispatcher); off it, decline.
        val planned = planNativeMenu(nodes, OsFamily.isWindows)
        if (!canShowNatively(
                isSupported = isSupported(),
                isEventDispatchThread = SwingUtilities.isEventDispatchThread(),
                plannedSize = planned.size,
            )
        ) {
            return false
        }

        val at = Point(screenX, screenY)
        val invoker = resolveInvoker(at) ?: return false

        val shown = generation.incrementAndGet()
        val isCurrent = { generation.get() == shown }

        // The WHOLE block, not just popup.show(). PopupMenu/MenuItem/Menu construction and
        // invoker.add all create AWT peers and can throw - HeadlessException most obviously. An
        // exception escaping here would escape SwingContextMenu.show() too, so the user would get
        // no native menu AND no Swing menu, and the request would be burned. That is exactly the
        // outcome the boolean return exists to prevent, so the contract has to be total.
        val opened =
            runCatching {

                // Grey the outgoing menu before losing the handle: per measured fact 2 it may still
                // be tracking on screen, and once detached hide() cannot disable it. Its items are
                // already inert via the fence, but a menu that looks live and does nothing reads as
                // a hang. Reachable via the keyboard menu key, which does not consume the grab.
                attached?.let { (_, menu) -> runCatching { disableAll(menu) } }
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

                val local = at.toInvokerCoordinates(invoker) ?: return@runCatching false
                popup.show(invoker, local.x, local.y)
                myWatcher = watcher.install(dismissed)
                // No watcher means no dismissal signal will ever arrive; report it now rather than
                // leaving the caller believing the menu is still up.
                if (myWatcher == null) dismissed()
        
                true
            }.getOrElse {
                detach()
                false
            }
        if (!opened) return false
        return true
    }

    /**
     * Advisory: an open menu cannot be dismissed programmatically. This greys the orphan out and
     * lets go of it, so it cannot act on state the tab has already torn down.
     */
    fun hide() {
        // Synchronously, before anything is posted: this is what dispose() actually needs. The
        // host's Toolkit holds this listener and it closes over plugin classes, so if a host ever
        // calls dispose() off the EDT and closes the classloader before a posted runnable drained,
        // the listener would outlive the unload - the exact leak dispose() exists to prevent.
        // Toolkit.add/removeAWTEventListener are internally synchronized, so this is safe here.
        watcher.clear()
        // Unconditional, deliberately. An earlier version skipped the bump when nothing was
        // attached, to protect an item's own ActionEvent from being fenced off when hide() runs
        // from teardown triggered BY the menu dismissing itself. But `attached` is also nulled by
        // the *heuristic* watcher - there is no real dismissal event (measured fact 3) - so if the
        // watcher fired while the NSMenu was still tracking, teardown would skip the bump and the
        // still-open menu's items would stay live, firing into a disposed tab.
        //
        // The two hazards are not equal: a dropped click is an annoyance, an orphan menu acting on
        // a disposed tab is the failure this whole design exists to exclude. So the fence always
        // closes, and losing a queued ActionEvent in that narrow race is the accepted cost.
        generation.incrementAndGet()
        onEdt {
            watcher.clear()
            attached?.let { (_, menu) -> runCatching { disableAll(menu) } }
            detach()
        }
    }

    /** Null when the origin is unknowable - better to decline than to guess the top-left. */
    private fun Point?.toInvokerCoordinates(invoker: Window): Point? {
        val origin = runCatching { invoker.locationOnScreen }.getOrNull() ?: return null
        val screen = this ?: return null
        return Point(screen.x - origin.x, screen.y - origin.y)
    }

    private fun resolveInvoker(at: Point?): Window? {
        // The focused window is only a shortcut if it satisfies what pickInvoker would demand of
        // it. With two BOSS windows open, or focus sitting on a detached browser window, the
        // focused one need not contain the click - and using it anyway subtracts the wrong
        // origin, putting the menu far from the pointer. It must also be a real frame or dialog,
        // since getWindows() hands back the heavyweight windows Swing creates for popups.
        KeyboardFocusManager
            .getCurrentKeyboardFocusManager()
            .focusedWindow
            ?.takeIf { it is Frame || it is Dialog }
            ?.takeIf { it.isShowing && (at == null || it.bounds.contains(at)) }
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
    // AtomicReference because clear() is now called synchronously from hide(), which runs on
    // whatever thread teardown happens on, while install() runs on the EDT.
    private val current = AtomicReference<AWTEventListener?>(null)

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
                    current.compareAndSet(this, null)
                    onDismiss()
                }
            }
        current.set(listener)
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
            current.set(null)
            null
        }
    }

    fun clear() {
        current.getAndSet(null)?.let {
            runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        }
    }

    fun clearIf(listener: AWTEventListener?) {
        if (listener != null && current.get() === listener) clear()
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
