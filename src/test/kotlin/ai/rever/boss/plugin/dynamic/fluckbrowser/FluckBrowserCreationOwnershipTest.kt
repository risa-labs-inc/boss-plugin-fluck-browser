package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks in the ownership invariants of the browser-creation lifecycle helpers:
 * an abandoned creation must dispose whatever browser it eventually produces
 * ("no leak"), must dispose nothing on null/exceptional completions, and
 * [completedBrowserOrNull] must map exceptional completions to null. The full
 * create→timeout→adopt sequencing lives in composition and needs the host
 * engine; these helpers are the mechanics that sequencing relies on.
 */
class FluckBrowserCreationOwnershipTest {

    /**
     * BrowserHandle via dynamic proxy: only dispose() is ever invoked by the
     * code under test, so no full stub implementation is needed.
     */
    private fun fakeHandle(onDispose: () -> Unit): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "dispose" -> { onDispose(); null }
                "toString" -> "FakeBrowserHandle"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as BrowserHandle

    @Test
    fun `abandon disposes the result of an already-completed creation`() {
        val disposed = CountDownLatch(1)
        val creation = CompletableDeferred<BrowserHandle?>()
        creation.complete(fakeHandle { disposed.countDown() })

        abandonBrowserCreation(creation)

        assertTrue(disposed.await(5, TimeUnit.SECONDS), "already-completed creation must be disposed")
    }

    @Test
    fun `abandon disposes the result when the creation completes later`() {
        val disposed = CountDownLatch(1)
        val creation = CompletableDeferred<BrowserHandle?>()

        abandonBrowserCreation(creation) // abandoned while still "in flight"
        creation.complete(fakeHandle { disposed.countDown() })

        assertTrue(disposed.await(5, TimeUnit.SECONDS), "late-completing creation must be disposed")
    }

    @Test
    fun `abandon disposes nothing for a null completion`() {
        val disposed = CountDownLatch(1)
        val nullCreation = CompletableDeferred<BrowserHandle?>()
        abandonBrowserCreation(nullCreation)
        nullCreation.complete(null)

        // No handle exists — nothing must be disposed (the latch never counts
        // down because no dispose can run against a null result).
        assertFalse(disposed.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `abandon survives an exceptional completion without disposing anything`() {
        // The abandonment callback must swallow the failure (invokeOnCompletion +
        // runCatching around getCompleted) rather than throw on the completer's
        // thread, and there is no handle to dispose. Completing exceptionally
        // after abandonment must therefore be a no-op that doesn't blow up.
        val failedCreation = CompletableDeferred<BrowserHandle?>()
        abandonBrowserCreation(failedCreation)
        failedCreation.completeExceptionally(IllegalStateException("boot failed"))

        assertTrue(failedCreation.isCompleted)
        assertNull(completedBrowserOrNull(failedCreation))
    }

    @Test
    fun `completedBrowserOrNull returns the value for success and null for failure`() {
        val handle = fakeHandle { }
        val ok = CompletableDeferred<BrowserHandle?>().apply { complete(handle) }
        // assertSame: the proxy fake stubs equals() to false, so compare identity.
        assertSame(handle, completedBrowserOrNull(ok))

        val failed = CompletableDeferred<BrowserHandle?>().apply {
            completeExceptionally(IllegalStateException("boot failed"))
        }
        assertNull(completedBrowserOrNull(failed))

        val nullResult = CompletableDeferred<BrowserHandle?>().apply { complete(null) }
        assertNull(completedBrowserOrNull(nullResult))
    }
}
