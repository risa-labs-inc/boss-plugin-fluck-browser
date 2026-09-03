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
import kotlin.test.assertTrue

/**
 * Pins the ordering [disposeBrowserHandleOffThread] exists for: the jobs it is handed finish
 * BEFORE `dispose()` runs.
 *
 * `installDirtyMarker` and `restoreScrollOnSettle` run `executeJavaScript` against a handle the
 * dispose paths are about to destroy; joining them first is what stops one landing mid-dispose.
 * Callers no longer assemble that list themselves - [ReleasedHandle] carries it out of
 * `releaseBrowserHandle` - and [ReleasedHandleTest] covers that half. This covers what happens
 * once the list arrives: the wait, the bound, and that dispose runs regardless.
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
        // Dispatchers.IO, not Default: these jobs block on a latch, and Default's parallelism is
        // the CPU count - two blocked jobs saturate it outright on a 2-vCPU CI runner.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

        // No "has it disposed yet" assertion here: dispose is posted to an executor, so a
        // not-yet-scheduled broken implementation would pass it just as well as a correct one.
        // The timestamp comparison below is what actually proves the ordering.
        disposeBrowserHandleOffThread(recorder.handle, awaitJobs = jobs)
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recorder = RecordingHandle()
        val never = CountDownLatch(1)
        val jobs = List(2) { scope.launch { never.await() } }

        val startedAt = System.nanoTime()
        disposeBrowserHandleOffThread(recorder.handle, awaitJobs = jobs)
        awaitDispose(recorder)
        val waitedMs = (recorder.disposedAt.get() - startedAt) / 1_000_000

        assertTrue(waitedMs >= 1_500, "must actually wait for the jobs, not skip the join (waited ${waitedMs}ms)")
        // Ceiling is well clear of the 2s bound rather than snug against it: this is wall-clock on
        // shared CI, and the failure this guards against is a per-job bound (2 jobs = 4s), which
        // 3.9s still separates from a single one.
        assertTrue(waitedMs < 3_900, "one 2s bound for the whole list, not 2s per job (waited ${waitedMs}ms)")
        never.countDown()
        scope.coroutineContext[Job]?.cancel()
    }
}
