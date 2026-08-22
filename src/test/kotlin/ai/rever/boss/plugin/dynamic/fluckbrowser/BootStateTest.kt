package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a creation attempt is allowed to conclude about the tab.
 *
 * The case this exists for is a deadline firing on a boot that is still running. A cold
 * first-install engine boot spawns the whole Chromium process tree, and on a fresh machine that can
 * outlast the first deadline - which used to be reported as "Browser initialization timed out. The
 * browser engine may not be available in this environment." That describes a failure that has not
 * happened: the creation runs on a scope nothing cancels, and the late-adoption nudge picks it up
 * whenever it lands. Calling it a failure is what made a first run look broken to the person
 * watching it.
 *
 * The second deadline is the other half. An install with no Chromium at all also arrives here, and
 * it must not be left spinning: past [BROWSER_CREATION_GIVE_UP_MS] the tab says so, which is the
 * one situation the old wording was right about.
 */
class BootStateTest {
    @Test
    fun `a boot still in flight is slow, not failed`() {
        val state = bootStateFor(BootOutcome.SLOW)

        assertNull(state.error, "a running boot must never be reported as an error")
        assertEquals(SLOW_BOOT_MESSAGE, state.initMessage)
    }

    @Test
    fun `a creation that came back null is a real failure`() {
        assertNotNull(bootStateFor(BootOutcome.FAILED).error)
    }

    @Test
    fun `adoption clears the error rather than leaving it for a page load to clear`() {
        assertNull(bootStateFor(BootOutcome.ADOPTED).error)
    }

    @Test
    fun `adoption resets the starting message so a later boot does not inherit it`() {
        // initMessage is hoisted onto the Component, so without this a tab that once said
        // "Still starting..." or "Browser crashed. Recovering..." would still be saying it the
        // next time it had no handle - a hibernation wake, for instance.
        assertEquals(INITIALIZING_MESSAGE, bootStateFor(BootOutcome.ADOPTED).initMessage)
    }

    @Test
    fun `a boot past the give-up deadline is reported rather than spun on forever`() {
        // The other half of not calling a slow boot a failure: an install with no engine at all
        // must still be told what is wrong, and a spinner never says it.
        val state = bootStateFor(BootOutcome.WEDGED)

        assertEquals(WEDGED_BOOT_MESSAGE, state.error)
        assertEquals(INITIALIZING_MESSAGE, state.initMessage)
    }

    @Test
    fun `the two deadlines are ordered, and the second leaves room to be slow in`() {
        // A give-up deadline at or below the first would erase the slow state entirely, and every
        // cold boot would go straight back to being called a failure.
        assertTrue(
            BROWSER_CREATION_GIVE_UP_MS > BROWSER_CREATION_TIMEOUT_MS,
            "the give-up deadline must come after the slow notice, not with it",
        )
    }

    @Test
    fun `every outcome sets both fields, so no caller has to know which it participates in`() {
        // initMessage was nullable once, meaning "leave whatever was there", and one of the three
        // call sites quietly dropped it. Only SLOW writes a message of its own; the rest reset it,
        // because it is hoisted and would otherwise explain a boot that finished long ago the next
        // time this tab had no handle.
        BootOutcome.entries.forEach { outcome ->
            val expected = if (outcome == BootOutcome.SLOW) SLOW_BOOT_MESSAGE else INITIALIZING_MESSAGE
            assertEquals(expected, bootStateFor(outcome).initMessage, "outcome $outcome")
        }
    }
}
