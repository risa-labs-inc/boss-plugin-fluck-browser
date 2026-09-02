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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ordering [disposeBrowserHandleOffThread] exists for: the jobs it is handed finish
 * BEFORE `dispose()` runs.
 *
 * Worth a test rather than a comment because the invariant is entirely in the caller's hands and
 * fails silently when broken. `installDirtyMarker` and `restoreScrollOnSettle` run
 * `executeJavaScript` against a handle the hibernation path is about to dispose; the protection is
 * that the hibernation site snapshots both Job references *before* calling `releaseBrowserHandle()`
 * (which cancels them, and each nulls its own field on completion) and passes them here. A future
 * refactor that builds the list from `hoistedState` after the release reads back nulled fields,
 * passes an empty list, and this function silently stops awaiting anything - no compile error, no
 * failing assertion anywhere else, just a race that shows up as an occasional crash in the field.
 *
 * Deliberately not a test of the pre-existing wedged-renderer gap: a `Job.join()` returning does
 * not prove an orphaned native call has stopped (BossConsole#300). What is pinned here is the
 * normal-completion case this function actually claims.
 */
class DisposeAwaitJobsTest {
    /** Records when dispose() ran, so the test can compare it against when the jobs finished. */
    private class RecordingHandle {
        val disposedAt = AtomicLong(0)
        val disposed = CountDownLatch(1)

        val handle: BrowserHandle =
            Proxy.newProxyInstance(
                BrowserHandle::class.java.classLoader,
                arrayOf(BrowserHandle::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "dispose" -> {
                        disposedAt.set(System.nanoTime())
                        disposed.countDown()
                        null
                    }
                    "toString" -> "RecordingBrowserHandle"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    // isValid and friends: a benign default beats throwing from an unrelated call.
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as BrowserHandle
    }

    private fun awaitDispose(recorder: RecordingHandle) {
        assertTrue(
            recorder.disposed.await(10, TimeUnit.SECONDS),
            "dispose() must run - it is posted to a background executor, never skipped",
        )
    }

    @Test
    fun `dispose waits for every job it was handed`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = RecordingHandle()
        val finishedAt = AtomicLong(0)
        val release = CountDownLatch(1)

        val jobs =
            List(2) {
                scope.launch {
                    release.await()
                    finishedAt.set(System.nanoTime())
                }
            }

        disposeBrowserHandleOffThread(recorder.handle, awaitJobs = jobs)
        // Nothing may dispose while the jobs are still running.
        assertEquals(1L, recorder.disposed.count, "dispose must not run ahead of the jobs it was given")

        release.countDown()
        awaitDispose(recorder)
        assertTrue(
            recorder.disposedAt.get() >= finishedAt.get(),
            "dispose() ran before the jobs finished - the await is not actually joining them",
        )
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * The empty list is what a caller that snapshotted the jobs too late passes. It must still
     * dispose - a broken snapshot loses the protection, it must not also leak the handle.
     */
    @Test
    fun `no jobs disposes immediately rather than waiting or skipping`() {
        val recorder = RecordingHandle()
        disposeBrowserHandleOffThread(recorder.handle)
        awaitDispose(recorder)
    }

    /**
     * A job that never completes must not hold the handle forever: hibernation exists partly to
     * recover FROM a wedged renderer, so the wait is bounded at 2s and dispose runs regardless.
     * One combined bound for the whole list, not one per job - two jobs must not cost 4s.
     */
    @Test
    fun `a job that never finishes still disposes, once, within the combined bound`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = RecordingHandle()
        val never = CountDownLatch(1)
        val jobs = List(2) { scope.launch { never.await() } }

        val startedAt = System.nanoTime()
        disposeBrowserHandleOffThread(recorder.handle, awaitJobs = jobs)
        awaitDispose(recorder)
        val waitedMs = (recorder.disposedAt.get() - startedAt) / 1_000_000

        assertTrue(waitedMs >= 1_500, "must actually wait for the jobs, not skip the join (waited ${waitedMs}ms)")
        assertTrue(waitedMs < 3_500, "one 2s bound for the whole list, not 2s per job (waited ${waitedMs}ms)")
        never.countDown()
        scope.coroutineContext[Job]?.cancel()
    }
}
