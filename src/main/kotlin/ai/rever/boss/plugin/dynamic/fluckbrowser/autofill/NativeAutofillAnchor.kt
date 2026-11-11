package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import ai.rever.boss.plugin.browser.BrowserHandle
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.ProvidableCompositionLocal
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.swing.RootPaneContainer

/**
 * Publishes where [handle]'s browser view sits on screen, for the dropdown and save card. Placed
 * over the browser view; draws nothing. Gone from composition (tab hidden or closed) means no anchor.
 */
@Composable
internal fun NativeAutofillAnchor(
    handle: BrowserHandle,
    modifier: Modifier,
) {
    val window = localWindow?.current
    val density = LocalDensity.current.density
    val bounds = remember { AtomicReference<Rect?>(null) }
    Box(modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) })
    DisposableEffect(handle, window, density) {
        NativeAutofillEngine.setAnchor(handle) {
            anchorOf(window ?: hostWindowOf(handle) ?: activeWindow(), bounds.get(), density)
        }
        onDispose { NativeAutofillEngine.setAnchor(handle, null) }
    }
}

/** Compose's own window local; `internal` in Kotlin but public in bytecode, so reached reflectively. */
@Suppress("UNCHECKED_CAST")
private val localWindow: ProvidableCompositionLocal<Window?>? =
    runCatching {
        Class
            .forName("androidx.compose.ui.window.LocalWindowKt")
            .getMethod("getLocalWindow")
            .invoke(null) as ProvidableCompositionLocal<Window?>
    }.getOrNull()

/** The host handle's window (`frameStallHostWindow`), when Compose did not provide one. */
private fun hostWindowOf(handle: BrowserHandle): Window? =
    runCatching {
        generateSequence<Class<*>>(handle.javaClass) { it.superclass }
            .firstNotNullOf { c -> runCatching { c.getDeclaredField("frameStallHostWindow") }.getOrNull() }
            .apply { isAccessible = true }
            .get(handle) as? Window
    }.getOrNull()

private fun activeWindow(): Window? = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

internal fun anchorOf(
    window: Window?,
    bounds: Rect?,
    density: Float,
): AutofillAnchor? {
    if (window == null || bounds == null || bounds.isEmpty || density <= 0f || !window.isShowing) return null
    val pane = (window as? RootPaneContainer)?.contentPane ?: return null
    val origin = runCatching { pane.locationOnScreen }.getOrNull() ?: return null
    return AutofillAnchor(
        window,
        Rectangle(
            origin.x + (bounds.left / density).toInt(),
            origin.y + (bounds.top / density).toInt(),
            (bounds.width / density).toInt(),
            (bounds.height / density).toInt(),
        ),
    )
}
