package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    // The same hooks AddressBarFocusRegistryTest and AddressBarShortcutProviderTest use. The
    // registry is a process-global singleton, so a class that touches it and does not reset is
    // relying on being the only one - cheap insurance for whenever maxParallelForks shows up.
    @BeforeTest
    @AfterTest
    fun reset() {
        AddressBarFocusRegistry.clear()
    }

    @Test
    fun `a miss never logged before is always due`() {
        assertTrue(AddressBarFocusRegistry.missLogDue(previous = null, now = 0))
    }

    @Test
    fun `a repeat inside the window stays quiet`() {
        val start = 1_000_000_000L

        assertFalse(AddressBarFocusRegistry.missLogDue(previous = start, now = start))
        assertFalse(
            AddressBarFocusRegistry.missLogDue(
                previous = start,
                now = start + AddressBarFocusRegistry.MISS_LOG_THROTTLE_NANOS - 1,
            ),
        )
    }

    @Test
    fun `the window reopens`() {
        val start = 1_000_000_000L

        assertTrue(
            AddressBarFocusRegistry.missLogDue(
                previous = start,
                now = start + AddressBarFocusRegistry.MISS_LOG_THROTTLE_NANOS,
            ),
        )
    }

    @Test
    fun `an unregisterable tab is reported, and throttled by reason`() {
        // The path that makes "Cmd+L never works in this tab" diagnosable. Keyed on the reason
        // rather than the tab so a long session cannot accumulate one permanent throttle entry
        // per tab - this plugin has canUnload:false, so permanent means the whole process.
        AddressBarFocusRegistry.noteUnregisterable("tab-1", "the host reported no window id")
        AddressBarFocusRegistry.noteUnregisterable("tab-2", "the host reported no window id")
        AddressBarFocusRegistry.noteUnregisterable("", "the tab has no id")

        // Two distinct reasons, however many tabs hit them.
        assertEquals(2, AddressBarFocusRegistry.throttleKeyCount())
    }

    @Test
    fun `the throttle is measured on a monotonic clock`() {
        // The reason there is no backwards-step case any more: nanoTime cannot step in either
        // direction, so the rule is a plain >= rather than a range with a special case for a
        // wall clock that moved. Pinned as a unit check on the constant, since the clock itself
        // is not injectable here.
        assertEquals(30_000_000_000L, AddressBarFocusRegistry.MISS_LOG_THROTTLE_NANOS)
        assertTrue(
            AddressBarFocusRegistry.MISS_LOG_THROTTLE_NANOS > 1_000_000_000L,
            "a nanosecond threshold mistaken for milliseconds would throttle for 30 nanoseconds",
        )
    }
}
