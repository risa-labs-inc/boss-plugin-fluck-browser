package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the half of the dispose protection that used to be a convention: the jobs pending against a
 * handle must come back FROM the release, not be fetched around it.
 *
 * The hazard is entirely one of ordering. `releaseBrowserHandle` cancels both jobs, and each nulls
 * its own field from `invokeOnCompletion` as cancellation completes - usually within microseconds.
 * So a caller that released first and then read `dirtyMarkerInstallJob` / `scrollRestoreJob` got an
 * empty list almost every time, passed it to `disposeBrowserHandleOffThread`, and ended up with an
 * await that looks like protection and waits for nothing. No compile error, no failing assertion:
 * just a race that surfaces as an occasional crash in the field.
 *
 * [ReleasedHandle] makes the wrong order unrepresentable. These tests are what stop a future
 * refactor from unbundling it again.
 */
class ReleasedHandleTest {
    private fun fakeHandle(): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "FakeBrowserHandle"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
            }
        } as BrowserHandle

    @Test
    fun `release hands back the jobs that were pending, not what cancellation left behind`() {
        // Dispatchers.IO: these block on a latch, and Default's parallelism is the CPU count.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val running = CountDownLatch(2)
        val never = CountDownLatch(1)
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.dirtyMarkerInstallJob = scope.launch { running.countDown(); never.await() }
        state.scrollRestoreJob = scope.launch { running.countDown(); never.await() }
        assertTrue(running.await(10, TimeUnit.SECONDS), "both jobs must actually be running before release")

        val released = state.releaseBrowserHandle()

        assertEquals(2, released.pendingJobs.size, "both live jobs must come back with the handle")
        assertTrue(released.pendingJobs.all { it.isCancelled }, "and release must still cancel them")
        never.countDown()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * The exact mistake the bundling removes, written out so its cost is visible: a list read from
     * the state AFTER release. It is allowed to be empty - that is the point - so this asserts the
     * bundle disagrees with it rather than asserting the racy read's own timing.
     */
    @Test
    fun `reading the fields after release is what the bundle exists to replace`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val never = CountDownLatch(1)
        val state = FluckBrowserTabState()
        state.adoptBrowserHandle(fakeHandle())
        state.dirtyMarkerInstallJob = scope.launch { never.await() }

        val released = state.releaseBrowserHandle()
        // Whatever cancellation has managed to null by now, the bundle already holds the job.
        assertEquals(1, released.pendingJobs.size)
        never.countDown()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `release with nothing pending returns the handle and an empty list`() {
        val state = FluckBrowserTabState()
        val handle = fakeHandle()
        state.adoptBrowserHandle(handle)

        val released = state.releaseBrowserHandle()

        assertSame(handle, released.handle)
        assertTrue(released.pendingJobs.isEmpty())
    }

    /** Releasing when there was never a handle must still be safe to hand to the dispose helper. */
    @Test
    fun `releasing nothing disposes nothing`() {
        val released = FluckBrowserTabState().releaseBrowserHandle()
        assertEquals(null, released.handle)
        disposeReleasedHandle(released)
    }
}
