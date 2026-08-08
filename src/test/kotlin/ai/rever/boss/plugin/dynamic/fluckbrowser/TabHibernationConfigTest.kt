package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards how hibernation decides whether to run, and how long to wait.
 *
 * All three of these were broken in ways that produced no error and no log line. Hibernation was
 * opt-in behind an environment variable, so it effectively never ran. The tier timing the host
 * publishes was not read at all. And the pressure accelerant compared the wrong memory number, so
 * it fired permanently on macOS and quietly replaced whatever timeout was configured. Silent
 * wrongness is why these are pure functions with tests rather than inline reads.
 */
class TabHibernationConfigTest {
    // region enabled

    @Test
    fun `hibernation is on when nothing says otherwise`() {
        assertTrue(TabHibernation.resolveEnabled(null))
        assertTrue(TabHibernation.resolveEnabled(""))
        assertTrue(TabHibernation.resolveEnabled("   "))
    }

    @Test
    fun `it can be turned off explicitly`() {
        for (raw in listOf("false", "0", "no", "off", "FALSE", " Off ")) {
            assertFalse(TabHibernation.resolveEnabled(raw), raw)
        }
    }

    @Test
    fun `the old opt-in spellings still mean on`() {
        for (raw in listOf("1", "true", "yes", "on", "TRUE")) {
            assertTrue(TabHibernation.resolveEnabled(raw), raw)
        }
    }

    /**
     * An unrecognized value must not read as "off". Someone writing `BOSS_TAB_HIBERNATION=enabled`
     * plainly wants it enabled, and silently disabling memory reclamation is the worst available
     * reading of that.
     */
    @Test
    fun `an unrecognized value does not silently disable it`() {
        for (raw in listOf("enabled", "yep", "!!", "2")) {
            assertTrue(TabHibernation.resolveEnabled(raw), raw)
        }
    }

    // endregion

    // region idle timeout

    @Test
    fun `the host tier is used when no environment override is set`() {
        assertEquals(120_000L, TabHibernation.resolveIdleMs(fromEnvironment = null, fromHost = "120000"))
    }

    @Test
    fun `an environment override outranks the host tier`() {
        assertEquals(5_000L, TabHibernation.resolveIdleMs(fromEnvironment = "5000", fromHost = "120000"))
    }

    @Test
    fun `with neither set it falls back to the default`() {
        assertEquals(
            TabHibernation.DEFAULT_IDLE_MS,
            TabHibernation.resolveIdleMs(fromEnvironment = null, fromHost = null),
        )
    }

    /**
     * An older host publishes nothing, and a garbled value is not an instruction. Neither may
     * produce a zero or negative delay, which would hibernate the tab the instant it backgrounds.
     */
    @Test
    fun `junk and non-positive values fall through rather than being obeyed`() {
        for (raw in listOf("", "  ", "abc", "0", "-1", "1e5")) {
            assertEquals(
                TabHibernation.DEFAULT_IDLE_MS,
                TabHibernation.resolveIdleMs(fromEnvironment = raw, fromHost = null),
                "env '$raw'",
            )
            assertEquals(
                TabHibernation.DEFAULT_IDLE_MS,
                TabHibernation.resolveIdleMs(fromEnvironment = null, fromHost = raw),
                "host '$raw'",
            )
        }
    }

    /** A junk env var must not shadow a perfectly good tier value. */
    @Test
    fun `a junk override falls through to the host tier`() {
        assertEquals(120_000L, TabHibernation.resolveIdleMs(fromEnvironment = "soon", fromHost = "120000"))
    }

    @Test
    fun `the property name matches what the host publishes`() {
        assertEquals("boss.browser.hibernationIdleMs", TabHibernation.HOST_IDLE_PROPERTY)
    }

    // endregion

    // region accelerants

    @Test
    fun `plenty of memory leaves the baseline alone`() {
        assertEquals(600_000L, TabHibernation.accelerate(600_000L, availableFraction = 0.85, onBattery = false))
    }

    @Test
    fun `real pressure shortens the wait`() {
        val accelerated = TabHibernation.accelerate(600_000L, availableFraction = 0.02, onBattery = false)
        assertTrue(accelerated < 600_000L, "expected acceleration, got $accelerated")
    }

    /**
     * The bug this whole change exists for. `freeMemorySize / total` reads about 0.0073 on a
     * healthy 128 GB Mac, so the old accelerant fired on every evaluation and the pressure delay
     * became the delay - a tier's configured timeout could never take effect. With a correct
     * reading, a machine macOS calls "92% free" must be left alone.
     */
    @Test
    fun `a healthy mac reading does not trigger the accelerant`() {
        assertEquals(600_000L, TabHibernation.accelerate(600_000L, availableFraction = 0.92, onBattery = false))
    }

    /**
     * Null is "could not measure", not "no memory left". Reading it as pressure would hibernate
     * every backgrounded tab after a minute on any machine whose memory we cannot see.
     */
    @Test
    fun `an unreadable memory reading is not treated as pressure`() {
        assertEquals(600_000L, TabHibernation.accelerate(600_000L, availableFraction = null, onBattery = false))
    }

    @Test
    fun `an accelerant never lengthens the wait`() {
        val shortBaseline = 1_000L
        for (fraction in listOf(null, 0.0, 0.01, 0.5, 1.0)) {
            for (battery in listOf(true, false)) {
                val result = TabHibernation.accelerate(shortBaseline, fraction, battery)
                assertTrue(result <= shortBaseline, "fraction=$fraction battery=$battery gave $result")
            }
        }
    }

    // endregion

    // region busy-tab guard

    /**
     * The behaviour that makes default-on safe, exercised rather than grepped.
     *
     * An earlier version of these tests asserted `script.contains("m.paused")`, which stays green
     * if the predicate is inverted or a `!` is dropped - it pinned the formatting of a JavaScript
     * string, not the logic, and locked the string in place while proving nothing. What actually
     * protects the user is the defer loop, so that is what is tested.
     */
    @Test
    fun `an idle tab hibernates immediately`() = runBlocking {
        var waits = 0
        TabHibernation.waitUntilIdle(isBusyNow = { false }, onWait = { waits++ })
        assertEquals(0, waits, "an idle tab should not have waited")
    }

    @Test
    fun `a busy tab is deferred until it goes quiet, then hibernates`() = runBlocking {
        var probes = 0
        var waits = 0
        // Audible for the first three checks, quiet on the fourth.
        TabHibernation.waitUntilIdle(
            isBusyNow = { probes++ < 3 },
            onWait = { waits++ },
        )
        assertEquals(3, waits, "expected one wait per busy probe")
        assertEquals(4, probes, "expected a final probe that found the tab quiet")
    }

    /** Deferred, never exempted: the tab must be re-checked rather than given up on. */
    @Test
    fun `the recheck interval is positive so a deferred tab is revisited`() = runBlocking {
        assertTrue(TabHibernation.MEDIA_RECHECK_MS > 0)
        TabHibernation.waitUntilIdle(isBusyNow = { false }, onWait = { fail("must not wait") })
    }

    @Test
    fun `the wait interval passed to the caller is the recheck interval`() = runBlocking {
        var probes = 0
        val intervals = mutableListOf<Long>()
        TabHibernation.waitUntilIdle(isBusyNow = { probes++ < 2 }, onWait = { intervals.add(it) })
        assertEquals(listOf(TabHibernation.MEDIA_RECHECK_MS, TabHibernation.MEDIA_RECHECK_MS), intervals)
    }

    // endregion

    // region memory parsing

    @Test
    fun `MemAvailable is read from proc meminfo`() {
        val meminfo =
            """
            MemTotal:       32000000 kB
            MemFree:          500000 kB
            MemAvailable:   20000000 kB
            Buffers:          100000 kB
            """.trimIndent()
        assertEquals(20_000_000L, HibernationMemory.parseMemAvailableKb(meminfo))
    }

    /** Pre-3.14 kernels and unusual procfs have no MemAvailable. That is unknown, not zero. */
    @Test
    fun `a meminfo without MemAvailable reads as unknown`() {
        assertNull(HibernationMemory.parseMemAvailableKb("MemTotal: 32000000 kB\nMemFree: 500000 kB"))
    }

    @Test
    fun `vm_stat pages are summed at the reported page size`() {
        val output =
            """
            Mach Virtual Memory Statistics: (page size of 16384 bytes)
            Pages free:                               100.
            Pages active:                            5000.
            Pages inactive:                           200.
            Pages speculative:                         50.
            Pages throttled:                            0.
            Pages wired down:                        3000.
            Pages purgeable:                           25.
            """.trimIndent()
        // free + inactive + speculative + purgeable = 375 pages
        assertEquals(375L * 16384L, HibernationMemory.parseVmStatAvailableBytes(output))
    }

    /**
     * A genuine zero and an unreadable value must not collapse together. They used to, which meant
     * a machine that really had run out of available memory - the single case the accelerant exists
     * for - was indistinguishable from an unmeasurable one, and so was ignored.
     */
    @Test
    fun `a real zero reading is pressure, not unknown`() {
        assertEquals(0L, TabHibernation.accelerate(0L, availableFraction = 0.0, onBattery = false))
        // 0.0 is below any sane threshold, so it must accelerate rather than be ignored.
        val accelerated = TabHibernation.accelerate(600_000L, availableFraction = 0.0, onBattery = false)
        assertTrue(accelerated < 600_000L, "a zero available fraction did not accelerate: $accelerated")
    }

    @Test
    fun `unparseable vm_stat output reads as unknown rather than zero`() {
        assertNull(HibernationMemory.parseVmStatAvailableBytes(""))
        assertNull(HibernationMemory.parseVmStatAvailableBytes("command not found"))
        // Page size present but no page counts: nothing to sum, so still unknown.
        assertNull(HibernationMemory.parseVmStatAvailableBytes("(page size of 4096 bytes)"))
    }

    // endregion
}
