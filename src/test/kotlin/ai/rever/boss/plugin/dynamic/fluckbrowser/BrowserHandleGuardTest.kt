package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle
import kotlinx.coroutines.CancellationException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the plugin's boundary against the host's browser objects.
 *
 * A browser whose engine was replaced throws ObjectClosedException from every call, including the
 * ones this file makes from a bare `launch`. Uncaught there it reaches the coroutine's last
 * resort handler, the host's crash interceptor sees a plugin fault, and the whole plugin is torn
 * down with every browser tab it owns. That is what one Enter in the URL bar did on 17 Aug.
 *
 * The exception type is deliberately not named here: JxBrowser is not on this plugin's classpath,
 * which is exactly why the guard catches `Throwable` rather than something specific.
 */
class BrowserHandleGuardTest {
    private fun handleThatThrows(error: Throwable): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "ThrowingBrowserHandle"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> throw error
            }
        } as BrowserHandle

    @Test
    fun `a call on a closed browser is contained, not propagated`() {
        val handle =
            handleThatThrows(
                IllegalStateException("Attempted to use a closed object."),
            )

        // reload() is one of the click paths; loadUrl() is the one that actually crashed. Both
        // reach the same guard, so the assertion is about the guard, not the method.
        val result = handle.onBrowser("reload") { it.reload() }

        assertNull(result, "a failed call reports nothing rather than a value")
    }

    @Test
    fun `cancellation is re-thrown, not swallowed`() {
        // Swallowing this would leave a cancelled tab's coroutine running on: the tab-switch and
        // dispose paths cancel these scopes, and they rely on the exception continuing upward.
        val handle = handleThatThrows(CancellationException("tab closed"))

        assertFailsWith<CancellationException> {
            handle.onBrowser("reload") { it.reload() }
        }
    }

    @Test
    fun `a null handle does nothing and runs no block`() {
        var ran = false
        val handle: BrowserHandle? = null

        val result = handle.onBrowser("reload") { ran = true }

        assertNull(result)
        assertFalse(ran, "the block must not run without a handle")
    }

    @Test
    fun `a successful call passes its value through`() {
        val handle =
            Proxy.newProxyInstance(
                BrowserHandle::class.java.classLoader,
                arrayOf(BrowserHandle::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "canGoBack" -> true
                    else -> error("Unstubbed BrowserHandle method: ${method.name}")
                }
            } as BrowserHandle

        assertEquals(true, handle.onBrowser("canGoBack") { it.canGoBack() })
        assertTrue(handle.onBrowser("canGoBack") { it.canGoBack() } == true)
    }
}
