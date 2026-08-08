package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks fullscreen UI state to the lifetime of its long-lived browser handle: replacing or
 * releasing a handle must never leak its fullscreen flag into the next composition or leave a
 * host fullscreen window detached from the tab.
 *
 * The distinction these tests exist to pin is between a **dead** handle and an **unresponsive**
 * one. A dead handle cannot still own a fullscreen view, so restoring the tab locally is safe.
 * A live handle that accepted two exit requests and never called back probably still has the
 * view parented in the host's fullscreen window, and restoring there would compose a
 * second parent for it - so the placeholder is held instead, in [FullscreenExitPhase.FAILED].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FluckBrowserFullscreenStateTest {
    private fun fakeHandle(
        isValid: Boolean = true,
        exitFailure: RuntimeException? = null,
        onExitFullscreen: () -> Unit = {},
    ): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java)
        ) { proxy, method, arguments ->
            when (method.name) {
                "requestExitFullscreen" -> {
                    exitFailure?.let { throw it }
                    onExitFullscreen()
                    null
                }
                "isValid" -> isValid
                "toString" -> "FakeBrowserHandle"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                // Loud rather than a confusing NullPointerException the first time someone
                // exercises a primitive-returning method through the proxy.
                else -> error("Unstubbed BrowserHandle method: ${method.name}")
            }
        } as BrowserHandle

    // ---- handle lifetime ----

    @Test
    fun `releasing a fullscreen handle resets state and requests host exit`() {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        val handle = fakeHandle(onExitFullscreen = { exitRequests++ })
        state.adoptBrowserHandle(handle)
        state.markFullscreenEntered()

        assertSame(handle, state.releaseBrowserHandle())
        assertNull(state.browserHandle)
        assertFalse(state.isInFullscreen)
        assertEquals(1, exitRequests)
    }

    @Test
    fun `releasing a non-fullscreen handle does not request host exit`() {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))

        state.releaseBrowserHandle()

        assertEquals(0, exitRequests)
    }

    @Test
    fun `releasing an invalid fullscreen handle does not request host exit`() {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(
            fakeHandle(
                isValid = false,
                onExitFullscreen = { exitRequests++ },
            ),
        )
        state.markFullscreenEntered()

        state.releaseBrowserHandle()

        assertEquals(0, exitRequests)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `releasing fullscreen state without a handle clears it safely`() {
        val state = FluckBrowserTabState()
        state.markFullscreenEntered()

        assertNull(state.releaseBrowserHandle())

        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `adopting a replacement handle clears stale fullscreen state`() {
        val state = FluckBrowserTabState()
        state.markFullscreenEntered()
        val replacement = fakeHandle()

        state.adoptBrowserHandle(replacement)

        assertSame(replacement, state.browserHandle)
        assertFalse(state.isInFullscreen)
    }

    // ---- exit requests ----

    @Test
    fun `exit request delegates to live handle and waits for callback`() {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()

        val requested = state.requestExitFullscreen()

        assertTrue(requested)
        assertEquals(1, exitRequests)
        assertTrue(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.EXITING, state.fullscreenExitPhase)
    }

    @Test
    fun `exit request clears stale placeholder when no handle exists`() {
        val state = FluckBrowserTabState()
        state.markFullscreenEntered()

        val requested = state.requestExitFullscreen()

        assertFalse(requested)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `exit request clears placeholder when handle is invalid`() {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle(isValid = false))
        state.markFullscreenEntered()

        val requested = state.requestExitFullscreen()

        assertFalse(requested)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `exit request clears placeholder when host request throws`() {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle(exitFailure = IllegalStateException("stale handle")))
        state.markFullscreenEntered()

        val requested = state.requestExitFullscreen()

        assertFalse(requested)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `repeated clicks do not re-post the host request while one is pending`() {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()

        assertTrue(state.requestExitFullscreen())
        assertFalse(state.requestExitFullscreen())
        assertFalse(state.requestExitFullscreen())

        assertEquals(1, exitRequests)
    }

    // ---- lost-callback fallback ----

    @Test
    fun `fallback retries host then holds the placeholder when the handle stays live`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()

        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS + 1)
        runCurrent()

        assertEquals(2, exitRequests)
        assertTrue(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.EXITING, state.fullscreenExitPhase)

        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS)
        runCurrent()

        // A live handle that never called back is presumed to still own the fullscreen view,
        // so the tab is NOT restored under it. The placeholder stays, offering a retry.
        assertTrue(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)
    }

    @Test
    fun `a failed exit can be retried and re-arms the fallback`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)

        // The click the FAILED copy invites.
        assertTrue(state.requestExitFullscreen())
        assertEquals(FullscreenExitPhase.EXITING, state.fullscreenExitPhase)
        assertEquals(3, exitRequests)

        state.scheduleFullscreenExitFallback(this)
        state.markFullscreenExited()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()

        assertFalse(state.isInFullscreen)
        assertEquals(3, exitRequests)
    }

    @Test
    fun `fallback restores the tab after one window when the handle is dead`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle(isValid = false))
        state.markFullscreenEntered()

        state.scheduleFullscreenExitFallback(this)
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS + 1)
        runCurrent()

        // No live view can be holding this one, so restoring locally is safe.
        assertFalse(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
    }

    @Test
    fun `host exit callback cancels pending fallback`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)

        state.markFullscreenExited()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()

        assertEquals(1, exitRequests)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `host callback during retry window cancels local restore`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)

        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS + 1)
        runCurrent()
        state.markFullscreenExited()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS + 1)
        runCurrent()

        assertEquals(2, exitRequests)
        assertFalse(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
    }

    @Test
    fun `releasing the handle cancels an armed fallback`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)

        // Tab closed while the exit was still in flight.
        state.releaseBrowserHandle()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()

        // The release itself posts one exit; the cancelled fallback must not post a second.
        assertEquals(2, exitRequests)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `repeated scheduling does not postpone fallback`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)

        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS / 2)
        state.scheduleFullscreenExitFallback(this)
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS / 2 + 1)
        runCurrent()

        // The discriminator: an implementation that restarted its timer on the second
        // schedule would not have reached the retry yet, leaving exitRequests at 1.
        assertEquals(2, exitRequests)
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS)
        runCurrent()

        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)
    }

    @Test
    fun `re-entered fullscreen survives stale fallback deadline`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)

        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS / 2)
        state.markFullscreenExited()
        state.markFullscreenEntered()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()

        assertEquals(1, exitRequests)
        assertTrue(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
    }

    @Test
    fun `a duplicate host enter does not strand a pending exit`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen())
        state.scheduleFullscreenExitFallback(this)

        // Spurious repeat of a callback the host already delivered.
        state.markFullscreenEntered()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS + 1)
        runCurrent()

        // The fallback armed for the live session is still the one running.
        assertEquals(2, exitRequests)
    }
}
