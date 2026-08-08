package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        isValid: () -> Boolean = { true },
        exitFailure: RuntimeException? = null,
        onExitFullscreen: () -> Unit = {},
        disposals: (() -> Unit)? = null,
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
                "isValid" -> isValid()
                "dispose" -> { disposals?.invoke(); null }
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
                isValid = { false },
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
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertNotNull(state.releaseBrowserHandle())

        // Second release: fullscreen already cleared, handle already gone.
        assertNull(state.releaseBrowserHandle())
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `adopting a replacement handle clears stale fullscreen state`() {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        val replacement = fakeHandle()

        state.adoptBrowserHandle(replacement)

        assertSame(replacement, state.browserHandle)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `adopting over a live handle disposes the one it replaces`() {
        val state = FluckBrowserTabState()
        val disposed = CountDownLatch(1)
        state.adoptBrowserHandle(fakeHandle(disposals = { disposed.countDown() }))

        state.adoptBrowserHandle(fakeHandle())

        // An undisposed handle is a leaked Chromium process tree, not a dangling reference.
        // Disposal is off-thread, hence the latch rather than a bare assertion.
        assertTrue(disposed.await(5, TimeUnit.SECONDS), "previous handle was never disposed")
    }

    // ---- exit requests ----

    @Test
    fun `exit request delegates to live handle and waits for callback`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()

        val requested = state.requestExitFullscreen(this)

        assertTrue(requested)
        assertEquals(1, exitRequests)
        assertTrue(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.EXITING, state.fullscreenExitPhase)
    }

    @Test
    fun `a late host enter cannot resurrect fullscreen after release`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.isInFullscreen)

        // Hibernation or crash recovery takes the handle, and the enter callback the host
        // queued before that lands afterwards on the Component scope.
        state.releaseBrowserHandle()
        state.markFullscreenEntered()

        assertFalse(state.isInFullscreen)
        assertNull(state.browserHandle)
    }

    @Test
    fun `exit request clears placeholder when handle is invalid`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle(isValid = { false }))
        state.markFullscreenEntered()

        val requested = state.requestExitFullscreen(this)

        assertFalse(requested)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `a throwing but live handle goes to FAILED, not a local restore`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle(exitFailure = IllegalStateException("wedged")))
        state.markFullscreenEntered()

        val requested = state.requestExitFullscreen(this)

        // isValid is still true, so this is evidence of a wedged host rather than a gone one.
        // Restoring the tab here would compose a second parent for a view the host may hold.
        assertFalse(requested)
        assertTrue(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)
    }

    @Test
    fun `a throwing AND invalid handle is still treated as dead`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(
            fakeHandle(isValid = { false }, exitFailure = IllegalStateException("gone")),
        )
        state.markFullscreenEntered()

        assertFalse(state.requestExitFullscreen(this))

        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `fullscreen cannot be entered without a handle`() = runTest {
        val state = FluckBrowserTabState()

        // A late host callback marshalled onto the Component scope, landing after hibernation
        // or crash recovery already released the handle. Fullscreen here would be unexitable,
        // and the content `when` has no else branch to fall back to.
        state.markFullscreenEntered()

        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `a host enter after a failed exit is a live session again`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)

        // The host exited cleanly and lost the callback - the likelier of the two FAILED
        // causes - so isInFullscreen is still set when the user re-enters from the page.
        state.markFullscreenEntered()

        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
        // The part that actually bites: without this the tab hibernates mid-video.
        assertTrue(state.fullscreenBlocksHibernation)
    }

    @Test
    fun `a duplicate enter mid-exit still does not clear the pending phase`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))

        state.markFullscreenEntered()

        // EXITING has a fallback armed for a request in flight; only FAILED is reset.
        assertEquals(FullscreenExitPhase.EXITING, state.fullscreenExitPhase)
    }

    @Test
    fun `a failed exit stops blocking hibernation`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.fullscreenBlocksHibernation, "live fullscreen must defer hibernation")

        assertTrue(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)

        // Still isInFullscreen, so the placeholder stays - but the "user is watching"
        // presumption is exactly what just failed twice, and leaving it set would exempt this
        // tab from hibernation for the rest of its life.
        assertTrue(state.isInFullscreen)
        assertFalse(state.fullscreenBlocksHibernation)
    }

    @Test
    fun `re-adopting the installed handle is a no-op, not a disposal`() = runTest {
        val state = FluckBrowserTabState()
        val disposed = CountDownLatch(1)
        val handle = fakeHandle(disposals = { disposed.countDown() })
        state.adoptBrowserHandle(handle)

        state.adoptBrowserHandle(handle)

        assertSame(handle, state.browserHandle)
        // A bounded negative wait, not an immediate read: disposal is posted to
        // browserDisposeExecutor, so reading a counter here would race the executor and pass
        // whether or not the guard exists.
        assertFalse(
            disposed.await(1, TimeUnit.SECONDS),
            "re-adopting disposed the handle it then stored",
        )
    }

    @Test
    fun `restoring anyway makes one last exit request`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(2, exitRequests)

        state.restoreTabFromFailedExit()

        // A merely slow host can still detach on this one before the tab recomposes.
        assertEquals(3, exitRequests)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `a handle swap clears a failed exit phase`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)

        // The phase gates both the debounce and which affordances render, so a stale FAILED
        // surviving a swap would be user-visible on a healthy new handle.
        state.adoptBrowserHandle(fakeHandle())

        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
    }

    @Test
    fun `releasing the handle clears a failed exit phase`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)

        state.releaseBrowserHandle()

        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
    }

    @Test
    fun `repeated clicks do not re-post the host request while one is pending`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()

        assertTrue(state.requestExitFullscreen(this))
        assertFalse(state.requestExitFullscreen(this))
        assertFalse(state.requestExitFullscreen(this))

        assertEquals(1, exitRequests)
    }

    // ---- lost-callback fallback ----

    @Test
    fun `fallback retries host then holds the placeholder when the handle stays live`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()

        assertTrue(state.requestExitFullscreen(this))
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
        assertTrue(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)

        // The click the FAILED copy invites.
        assertTrue(state.requestExitFullscreen(this))
        assertEquals(FullscreenExitPhase.EXITING, state.fullscreenExitPhase)
        assertEquals(3, exitRequests)

        state.markFullscreenExited()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()

        assertFalse(state.isInFullscreen)
        assertEquals(3, exitRequests)
    }

    @Test
    fun `fallback restores the tab when the handle dies mid-exit`() = runTest {
        val state = FluckBrowserTabState()
        var valid = true
        state.adoptBrowserHandle(fakeHandle(isValid = { valid }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))

        // The renderer crashes while the exit is in flight - the one way the retry can find a
        // handle that was live when it armed and is not when it fires.
        valid = false
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS + 1)
        runCurrent()

        // No live view can be holding this one, so restoring locally is safe.
        assertFalse(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
    }

    @Test
    fun `the user can restore a tab whose exit never completed`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()
        assertEquals(FullscreenExitPhase.FAILED, state.fullscreenExitPhase)

        // isValid cannot tell "host wedged" from "host exited, callback lost", so FAILED must
        // not be a dead end: the user can see which it is and says so.
        state.restoreTabFromFailedExit()

        assertFalse(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.IDLE, state.fullscreenExitPhase)
    }

    @Test
    fun `restoring anyway does nothing before the exit has actually failed`() = runTest {
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))

        // Still EXITING: the host may yet call back, and dropping the placeholder here would
        // race the very callback the fallback is waiting for.
        state.restoreTabFromFailedExit()

        assertTrue(state.isInFullscreen)
        assertEquals(FullscreenExitPhase.EXITING, state.fullscreenExitPhase)
    }

    @Test
    fun `host exit callback cancels pending fallback`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))

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
        assertTrue(state.requestExitFullscreen(this))

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
        assertTrue(state.requestExitFullscreen(this))

        // Tab closed while the exit was still in flight.
        state.releaseBrowserHandle()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS * 2 + 1)
        runCurrent()

        // The release itself posts one exit; the cancelled fallback must not post a second.
        assertEquals(2, exitRequests)
        assertFalse(state.isInFullscreen)
    }

    @Test
    fun `a repeated click does not postpone the fallback`() = runTest {
        val state = FluckBrowserTabState()
        var exitRequests = 0
        state.adoptBrowserHandle(fakeHandle(onExitFullscreen = { exitRequests++ }))
        state.markFullscreenEntered()
        assertTrue(state.requestExitFullscreen(this))

        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS / 2)
        // The impatient second click, mid-window. Debounced, and must not restart the clock.
        assertFalse(state.requestExitFullscreen(this))
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS / 2 + 1)
        runCurrent()

        // The discriminator: an implementation that restarted its timer on the second click
        // would not have reached the retry yet, leaving exitRequests at 1.
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
        assertTrue(state.requestExitFullscreen(this))

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
        assertTrue(state.requestExitFullscreen(this))

        // Spurious repeat of a callback the host already delivered.
        state.markFullscreenEntered()
        advanceTimeBy(FULLSCREEN_EXIT_FALLBACK_MS + 1)
        runCurrent()

        // The fallback armed for the live session is still the one running.
        assertEquals(2, exitRequests)
    }
}
