package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a creation attempt is allowed to conclude about the tab.
 *
 * The case this exists for is the watchdog firing on a boot that is still running. A cold
 * first-install engine boot spawns the whole Chromium process tree, and on a fresh machine that
 * can outlast the timeout - which used to be reported as "Browser initialization timed out. The
 * browser engine may not be available in this environment." That describes a failure that has not
 * happened: the creation runs on a scope nothing cancels, and the late-adoption nudge picks it up
 * whenever it lands. Calling it an error is what made a first run look broken to the person
 * watching it.
 */
class BootStateTest {
    @Test
    fun `a boot still in flight is slow, not failed`() {
        val state = bootStateFor(adopted = false, completed = false)

        assertNull(state.error, "a running boot must never be reported as an error")
        assertEquals(SLOW_BOOT_MESSAGE, state.initMessage)
        assertTrue(state.isInitializing)
    }

    @Test
    fun `a creation that came back null is a real failure`() {
        val state = bootStateFor(adopted = false, completed = true)

        assertNotNull(state.error)
        assertFalse(state.isInitializing)
    }

    @Test
    fun `adoption clears the error rather than leaving it for a page load to clear`() {
        val state = bootStateFor(adopted = true, completed = true)

        assertNull(state.error)
        assertFalse(state.isInitializing)
    }

    @Test
    fun `adoption resets the starting message so a later boot does not inherit it`() {
        // initMessage is hoisted onto the Component, so without this a tab that once said
        // "Still starting..." or "Browser crashed. Recovering..." would still be saying it the
        // next time it had no handle - a hibernation wake, for instance.
        assertEquals(INITIALIZING_MESSAGE, bootStateFor(adopted = true, completed = true).initMessage)
    }

    @Test
    fun `adoption wins even when the deferred had not completed`() {
        // The late-adoption path: withTimeoutOrNull returned null, the snapshot said not
        // completed, and the boot finished between the two. A handle in hand outranks both.
        val state = bootStateFor(adopted = true, completed = false)

        assertNull(state.error)
        assertFalse(state.isInitializing)
    }
}
