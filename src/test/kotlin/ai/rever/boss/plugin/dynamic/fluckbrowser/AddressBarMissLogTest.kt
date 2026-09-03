package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The throttle on Cmd+L's miss logging.
 *
 * Small, but not obvious, and only observable through `println` in situ - which is why the rule
 * is hoisted out of the map access around it. The two extremes it exists between: a line per
 * keypress if a user rebinds this action to a globally-scoped chord, and a one-shot latch that
 * keeps only the first miss for the life of the JVM (about whichever window happened to miss
 * first, since the message names one).
 */
class AddressBarMissLogTest {
    @Test
    fun `a miss never logged before is always due`() {
        assertTrue(AddressBarFocusRegistry.missLogDue(previous = null, now = 0))
    }

    @Test
    fun `a repeat inside the window stays quiet`() {
        val start = 1_000_000L

        assertFalse(AddressBarFocusRegistry.missLogDue(previous = start, now = start))
        assertFalse(AddressBarFocusRegistry.missLogDue(previous = start, now = start + 29_999))
    }

    @Test
    fun `the window reopens`() {
        val start = 1_000_000L

        assertTrue(AddressBarFocusRegistry.missLogDue(previous = start, now = start + 30_000))
    }

    @Test
    fun `a clock stepping backwards reads as due, not as half a century of silence`() {
        // now - previous is negative, which is outside the throttle range either way. Erring
        // toward logging costs a line; erring the other way costs the diagnosis.
        val start = 1_000_000L

        assertTrue(AddressBarFocusRegistry.missLogDue(previous = start, now = start - 5_000))
    }
}
