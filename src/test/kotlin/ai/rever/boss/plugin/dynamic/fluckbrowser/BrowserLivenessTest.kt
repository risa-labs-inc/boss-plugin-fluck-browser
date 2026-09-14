package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle
import androidx.compose.ui.text.input.TextFieldValue
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wedge this pins is BossConsole#392: a right-split tab stuck on a loading spinner with dead
 * reload/stop/address-bar controls, only scrolling still working. Its cause is an engine that died
 * with its IPC channel severed - `BrowserHandle.isValid` stays true for it, so the 500ms validity
 * poll never rebuilt the browser, while every call through [onBrowser] threw `ObjectClosedException`
 * and was swallowed. [BrowserLiveness] is the second signal: onBrowser reports the closed throwable
 * and the poll recovers on it as well as on `isValid`.
 */
class BrowserLivenessTest {
    /** Local stand-in: the plugin does not depend on JxBrowser, so the real type is unavailable. */
    private class ObjectClosedException(message: String) : RuntimeException(message)

    @Test
    fun `the engine-closed exception is recognised by type name`() {
        assertTrue(BrowserLiveness.isEngineClosed(ObjectClosedException("boom")))
    }

    @Test
    fun `the engine-closed exception is recognised by message when the type is opaque`() {
        assertTrue(
            BrowserLiveness.isEngineClosed(
                IllegalStateException("Attempted to use a closed object."),
            ),
            "the host wraps the failure in its own type; the message is the only tell left",
        )
    }

    @Test
    fun `a wrapped engine-closed cause is found`() {
        val wrapped = RuntimeException("browser call failed", ObjectClosedException("closed"))
        assertTrue(BrowserLiveness.isEngineClosed(wrapped))
    }

    @Test
    fun `an ordinary failure is not treated as engine death`() {
        assertFalse(BrowserLiveness.isEngineClosed(RuntimeException("network unreachable")))
    }

    @Test
    fun `a cause cycle does not hang the walk`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        // Must terminate and, since neither names the engine, must answer false.
        assertFalse(BrowserLiveness.isEngineClosed(a))
    }

    @Test
    fun `an invalid handle recovers, exactly as before`() {
        assertTrue(BrowserLiveness.shouldRecover(isValid = false, engineDeathReported = false))
    }

    @Test
    fun `a handle that reports valid but died over IPC still recovers`() {
        // The whole point of #392: isValid is true and recovery must still happen.
        assertTrue(BrowserLiveness.shouldRecover(isValid = true, engineDeathReported = true))
    }

    @Test
    fun `a healthy handle is left alone`() {
        assertFalse(BrowserLiveness.shouldRecover(isValid = true, engineDeathReported = false))
    }

    private fun handleThatThrows(error: Throwable): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "LivenessTestHandle"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "isValid" -> true
                "dispose" -> null
                else -> throw error
            }
        } as BrowserHandle

    @Test
    fun `closed call reports the actual valid handle and schedules complete recovery`() {
        val state = FluckBrowserTabState()
        val handle = handleThatThrows(ObjectClosedException("closed"))
        state.adoptBrowserHandle(handle)
        state.loadedUrl = "https://example.com/committed"
        state.urlBarText = TextFieldValue("https://example.com/draft")
        state.isLoading = true
        val initBefore = state.initNonce

        assertNull(handle.onBrowser("reload", state::reportEngineDeath) { it.reload() })
        assertTrue(handle.isValid)
        assertTrue(BrowserLiveness.shouldRecover(handle.isValid, state.engineDeathReported))

        val released = state.prepareBrowserRecovery()

        // All state required by the replacement effect must be ready before this method returns.
        // The caller's effect can now be cancelled by the handle transition without losing retry.
        assertSame(handle, released.handle)
        assertNull(state.browserHandle)
        assertFalse(state.isLoading)
        assertFalse(state.engineDeathReported)
        assertNull(state.error)
        assertEquals(RECOVERING_MESSAGE, state.initMessage)
        assertEquals("https://example.com/committed", state.loadedUrl)
        assertEquals(state.loadedUrl, state.urlBarText.text)
        assertEquals(initBefore + 1, state.initNonce)
    }

    @Test
    fun `a late failure from a released handle cannot kill its healthy replacement`() {
        val state = FluckBrowserTabState()
        val old = handleThatThrows(ObjectClosedException("old"))
        val replacement = handleThatThrows(ObjectClosedException("new"))
        state.adoptBrowserHandle(old)

        old.onBrowser("reload", state::reportEngineDeath) {
            state.releaseBrowserHandle()
            state.adoptBrowserHandle(replacement)
            it.reload() // The old in-flight operation finishes after adoption.
        }
        assertFalse(state.engineDeathReported)

        replacement.onBrowser("stop", state::reportEngineDeath) { it.stop() }
        assertTrue(state.engineDeathReported)
        state.reportEngineDeath(old)
        assertTrue(state.engineDeathReported, "a stale report must not overwrite a current report")
        state.adoptBrowserHandle(replacement)
        assertTrue(state.engineDeathReported, "re-adopting the same dead handle must not revive it")
        state.releaseBrowserHandle()
        state.reportEngineDeath(replacement)
        assertFalse(state.engineDeathReported)
    }

    @Test
    fun `ordinary failures and cancellation never request engine recovery`() {
        val state = FluckBrowserTabState()
        val ordinary = handleThatThrows(IllegalStateException("network error"))
        state.adoptBrowserHandle(ordinary)
        assertNull(ordinary.onBrowser("reload", state::reportEngineDeath) { it.reload() })
        assertFalse(state.engineDeathReported)
        state.releaseBrowserHandle()

        val cancelled = handleThatThrows(CancellationException("closed object"))
        state.adoptBrowserHandle(cancelled)
        assertFailsWith<CancellationException> {
            cancelled.onBrowser("reload", state::reportEngineDeath) { it.reload() }
        }
        assertFalse(state.engineDeathReported)
    }

    @Test
    fun `context menu navigation reports death and stale menus cannot kill a replacement`() {
        for (label in listOf("Reload", "Back", "Forward")) {
            val state = FluckBrowserTabState()
            val handle = handleThatThrows(ObjectClosedException("closed"))
            state.adoptBrowserHandle(handle)
            val action = buildContextMenuItems(
                info = null,
                browserHandle = handle,
                canGoBack = true,
                canGoForward = true,
                onNavigate = {},
                onOpenInNewTab = {},
                onEngineDead = state::reportEngineDeath,
            ).single { it.text == label }
            action.onClick?.invoke()
            assertTrue(state.engineDeathReported, label)
            state.releaseBrowserHandle()
            state.adoptBrowserHandle(handleThatThrows(ObjectClosedException("replacement")))
            action.onClick?.invoke()
            assertFalse(state.engineDeathReported, "stale $label menu")
        }
    }
}
