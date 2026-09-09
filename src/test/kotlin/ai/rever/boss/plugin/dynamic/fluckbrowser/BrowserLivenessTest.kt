package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
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
}
