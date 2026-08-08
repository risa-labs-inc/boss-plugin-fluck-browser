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

    // Explicit thresholds so these do not change behaviour on a machine that happens to have
    // BOSS_TAB_HIBERNATION_* set - which is the developer most likely to run them.
    private fun accelerate(
        baseline: Long,
        available: Double?,
        onBattery: Boolean = false,
    ) = TabHibernation.accelerate(
        baseline = baseline,
        availableFraction = available,
        onBattery = onBattery,
        pressureThreshold = 0.15,
        pressureDelayMs = 60_000L,
        batteryDelayMs = 120_000L,
        batteryAware = true,
    )

    @Test
    fun `plenty of memory leaves the baseline alone`() {
        assertEquals(600_000L, accelerate(600_000L, available = 0.85))
    }

    @Test
    fun `real pressure shortens the wait`() {
        val accelerated = accelerate(600_000L, available = 0.02)
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
        assertEquals(600_000L, accelerate(600_000L, available = 0.92))
    }

    /**
     * Null is "could not measure", not "no memory left". Reading it as pressure would hibernate
     * every backgrounded tab after a minute on any machine whose memory we cannot see.
     */
    @Test
    fun `an unreadable memory reading is not treated as pressure`() {
        assertEquals(600_000L, accelerate(600_000L, available = null))
    }

    @Test
    fun `an accelerant never lengthens the wait`() {
        val shortBaseline = 1_000L
        for (fraction in listOf(null, 0.0, 0.01, 0.5, 1.0)) {
            for (battery in listOf(true, false)) {
                val result = accelerate(shortBaseline, fraction, battery)
                assertTrue(result <= shortBaseline, "fraction=$fraction battery=$battery gave $result")
            }
        }
    }

    // endregion

    // region busy-tab policy

    private val IDLE = TabHibernation.BusyState.IDLE
    private val MEDIA = TabHibernation.BusyState.PLAYING_MEDIA
    private val FULLSCREEN = TabHibernation.BusyState.FULLSCREEN

    @Test
    fun `an idle tab hibernates immediately`() = runBlocking {
        var waits = 0
        assertEquals(IDLE, TabHibernation.awaitQuiet(probe = { IDLE }, onWait = { waits++ }))
        assertEquals(0, waits, "an idle tab should not have waited")
    }

    @Test
    fun `an audible tab is waited out, then hibernates once quiet`() = runBlocking {
        var probes = 0
        val waits = mutableListOf<Long>()
        val hibernate =
            TabHibernation.awaitQuiet(
                probe = { if (probes++ < 3) MEDIA else IDLE },
                onWait = { waits.add(it) },
            )
        assertEquals(IDLE, hibernate, "a tab that went quiet should hibernate")
        assertEquals(3, waits.size)
    }

    /**
     * Fullscreen must be waited out exactly like audio. It rides the same path only via
     * `!= IDLE`, so a later `if (busy == PLAYING_MEDIA)` special case would silently start
     * hibernating tabs mid-video again with nothing else failing.
     */
    @Test
    fun `a fullscreen tab is waited out, then hibernates once it exits`() = runBlocking {
        var probes = 0
        val waits = mutableListOf<Long>()
        val hibernate =
            TabHibernation.awaitQuiet(
                probe = { if (probes++ < 2) FULLSCREEN else IDLE },
                onWait = { waits.add(it) },
            )
        assertEquals(IDLE, hibernate, "a tab that left fullscreen should hibernate")
        assertEquals(2, waits.size)
    }

    @Test
    fun `a tab still in fullscreen at the recheck limit is left alone`() = runBlocking {
        val hibernate =
            TabHibernation.awaitQuiet(probe = { FULLSCREEN }, onWait = {}, maxRechecks = 3)
        // The terminal state is returned, not just "no", so the log line cannot drift from
        // what was actually decided.
        assertEquals(FULLSCREEN, hibernate)
    }

    /**
     * The backoff and the budget are both there to bound the cost of probing an *audible* page.
     * Carrying either across a change of reason charges the new reason for the old one's waits -
     * and can hand a newly audible tab an already-spent budget, exempting it for no reason.
     */
    @Test
    fun `a changed reason restarts the backoff and the budget`() = runBlocking {
        var probes = 0
        val waits = mutableListOf<Long>()
        val hibernate =
            TabHibernation.awaitQuiet(
                // Four fullscreen rechecks would exhaust maxRechecks = 4 on their own; the
                // switch to audio must hand it a fresh budget rather than a spent one.
                probe = { if (probes++ < 4) FULLSCREEN else if (probes < 8) MEDIA else IDLE },
                onWait = { waits.add(it) },
                maxRechecks = 4,
            )
        assertEquals(IDLE, hibernate, "the budget should have restarted at the switch")
        assertEquals(
            TabHibernation.MEDIA_RECHECK_MS,
            waits[4],
            "the first wait after the reason changed should be back at the floor: $waits",
        )
    }

    /** Rechecks must back off, or a three-hour video costs an eval every 30s for three hours. */
    @Test
    fun `rechecks back off toward the ceiling`() = runBlocking {
        val waits = mutableListOf<Long>()
        TabHibernation.awaitQuiet(probe = { MEDIA }, onWait = { waits.add(it) }, maxRechecks = 12)
        assertEquals(TabHibernation.MEDIA_RECHECK_MS, waits.first())
        for (i in 1 until waits.size) {
            assertTrue(waits[i] >= waits[i - 1], "interval shrank at $i: $waits")
        }
        assertTrue(waits.all { it <= TabHibernation.MAX_RECHECK_MS }, waits.toString())
        assertEquals(TabHibernation.MAX_RECHECK_MS, waits.last(), "should reach the ceiling")
    }

    /**
     * Fullscreen has to win over the page probe, and be answered without one. The JS probe runs
     * inside the document, which cannot see which window the host is rendering it into, so asking
     * it about fullscreen would be asking the wrong component - and on a null handle there is
     * nobody to ask at all.
     */
    @Test
    fun `fullscreen outranks the page probe, and a missing handle is idle`() = runBlocking {
        assertEquals(
            FULLSCREEN,
            TabHibernation.busyStateFor(isInFullscreen = true, handle = null),
            "a null handle must not stop fullscreen being reported",
        )
        assertEquals(IDLE, TabHibernation.busyStateFor(isInFullscreen = false, handle = null))
    }

    /**
     * A bound on polling must not become a licence to cut the audio. When a tab is *still* playing
     * after the last recheck, the answer is to leave it alone - not to hibernate it anyway.
     */
    @Test
    fun `a tab still playing at the recheck limit is left alone, not hibernated`() = runBlocking {
        var waits = 0
        val hibernate =
            TabHibernation.awaitQuiet(probe = { MEDIA }, onWait = { waits++ }, maxRechecks = 5)
        assertEquals(MEDIA, hibernate, "hibernating here would cut audio mid-play")
        // Exactly maxRechecks, not one more: the last attempt decides without sleeping on a
        // result already determined, which used to park a live coroutine for up to 5 minutes.
        assertEquals(5, waits, "expected no sleep on the deciding attempt")
    }

    // endregion

    // region pressure re-evaluation

    /**
     * The accelerant used to sample memory once, when the tab backgrounded, then sleep on that
     * answer for up to thirty minutes - so it could only ever see pressure that already existed,
     * never the normal case of pressure caused afterwards by the other tabs.
     */
    @Test
    fun `the idle target is re-evaluated while waiting, so later pressure still shortens it`() =
        runBlocking {
            var slept = 0L
            var asked = 0
            TabHibernation.awaitIdleWindow(
                // 10 minutes, until pressure arrives partway through and drops it to one.
                idleMsNow = { asked++; if (slept >= 60_000L) 60_000L else 600_000L },
                sleep = { slept += it },
                chunkMs = 30_000L,
            )
            assertTrue(asked > 1, "target was only sampled once - pressure could never be seen")
            assertTrue(slept < 600_000L, "waited out the original target despite the shortening")
        }

    /**
     * Coarse while the deadline is far off, fine as it approaches. A flat chunk woke every
     * backgrounded tab every 30s for its whole window - at 40 tabs on the Full tier, over one
     * wakeup a second sustained for half an hour, which is the same cost this PR rejected
     * elsewhere. Resolution only buys anything near the deadline.
     */
    @Test
    fun `sleeps are coarse early and fine near the deadline`() = runBlocking {
        val chunks = mutableListOf<Long>()
        TabHibernation.awaitIdleWindow(
            idleMsNow = { 30 * 60_000L },
            sleep = { chunks.add(it) },
            chunkMs = 30_000L,
        )
        assertEquals(30 * 60_000L, chunks.sum(), "must still wait the full target")
        assertTrue(chunks.first() > 30_000L, "first sleep was not coarse: ${chunks.first()}")
        assertTrue(chunks.last() <= 30_000L, "last sleep was not fine: ${chunks.last()}")
        assertTrue(chunks.size < 60, "still one wake per floor interval: ${chunks.size}")
    }

    @Test
    fun `a steady target is slept out in chunks`() = runBlocking {
        val chunks = mutableListOf<Long>()
        TabHibernation.awaitIdleWindow(
            idleMsNow = { 100_000L },
            sleep = { chunks.add(it) },
            chunkMs = 30_000L,
        )
        assertEquals(100_000L, chunks.sum())
        assertTrue(chunks.all { it <= 30_000L }, chunks.toString())
        // The final chunk is the remainder, never an overshoot past the target.
        assertEquals(10_000L, chunks.last())
    }

    @Test
    fun `an already-elapsed target returns without sleeping`() = runBlocking {
        var slept = 0
        TabHibernation.awaitIdleWindow(idleMsNow = { 0L }, sleep = { slept++ }, chunkMs = 30_000L)
        assertEquals(0, slept)
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
        assertEquals(0L, accelerate(0L, available = 0.0))
        // 0.0 is below any sane threshold, so it must accelerate rather than be ignored.
        val accelerated = accelerate(600_000L, available = 0.0)
        assertTrue(accelerated < 600_000L, "a zero available fraction did not accelerate: $accelerated")
    }

    @Test
    fun `unparseable vm_stat output reads as unknown rather than zero`() {
        assertNull(HibernationMemory.parseVmStatAvailableBytes(""))
        assertNull(HibernationMemory.parseVmStatAvailableBytes("command not found"))
        // Page size present but no page counts: nothing to sum, so still unknown.
        assertNull(HibernationMemory.parseVmStatAvailableBytes("(page size of 4096 bytes)"))
    }

    /**
     * A vm_stat table that genuinely sums to zero reclaimable pages is the deepest-pressure
     * reading there is. Returning null for it would have the caller ignore the single case the
     * accelerant exists for - and the accelerate() test above passes either way, which is exactly
     * the false confidence worth closing here.
     */
    @Test
    fun `a vm_stat table summing to zero is zero, not unknown`() {
        val output =
            """
            Mach Virtual Memory Statistics: (page size of 4096 bytes)
            Pages free:                                 0.
            Pages active:                          500000.
            Pages inactive:                             0.
            Pages speculative:                          0.
            Pages purgeable:                            0.
            """.trimIndent()
        assertEquals(0L, HibernationMemory.parseVmStatAvailableBytes(output))
    }

    /** The per-call property read is the point of the change, so it needs its own pin. */
    @Test
    fun `currentIdleMs reflects the host property at the time it is called`() {
        val previous = System.getProperty(TabHibernation.HOST_IDLE_PROPERTY)
        try {
            // fromEnvironment explicitly null: the env var outranks the property, so a developer
            // with BOSS_TAB_HIBERNATION_IDLE_MS set would otherwise fail this. The live property
            // read is still what is being exercised.
            System.setProperty(TabHibernation.HOST_IDLE_PROPERTY, "123456")
            assertEquals(123_456L, TabHibernation.currentIdleMs(fromEnvironment = null))
            System.setProperty(TabHibernation.HOST_IDLE_PROPERTY, "654321")
            assertEquals(
                654_321L,
                TabHibernation.currentIdleMs(fromEnvironment = null),
                "value was captured, not re-read",
            )
        } finally {
            if (previous == null) {
                System.clearProperty(TabHibernation.HOST_IDLE_PROPERTY)
            } else {
                System.setProperty(TabHibernation.HOST_IDLE_PROPERTY, previous)
            }
        }
    }

    /**
     * The ratio's guards, now that it is a pure function. `total <= 0` is an unreadable machine,
     * and a null numerator is an unreadable reading - neither may present as pressure.
     */
    @Test
    fun `the available fraction guards its inputs`() {
        assertNull(HibernationMemory.fraction(available = null, total = 100L))
        assertNull(HibernationMemory.fraction(available = 50L, total = 0L))
        assertNull(HibernationMemory.fraction(available = 50L, total = -1L))
        assertNull(HibernationMemory.fraction(available = -1L, total = 100L))
        assertEquals(0.5, HibernationMemory.fraction(available = 50L, total = 100L))
        assertEquals(0.0, HibernationMemory.fraction(available = 0L, total = 100L))
        // Clamped: a cgroup-limited total can be smaller than a host-wide MemAvailable.
        assertEquals(1.0, HibernationMemory.fraction(available = 500L, total = 100L))
    }

    /**
     * Numerator and denominator must come from the same place. The JDK reports a cgroup-limited
     * total while /proc/meminfo reports the host's, and mixing them pins the ratio at 1.0 through
     * the clamp - an accelerant that silently never fires.
     */
    @Test
    fun `MemTotal is readable from the same meminfo text as MemAvailable`() {
        val meminfo = "MemTotal:       32000000 kB\nMemAvailable:   20000000 kB"
        assertEquals(32_000_000L, HibernationMemory.parseMemTotalKb(meminfo))
        assertEquals(20_000_000L, HibernationMemory.parseMemAvailableKb(meminfo))
        assertNull(HibernationMemory.parseMemTotalKb("MemFree: 1 kB"))
    }

    /**
     * The only link in the probe chain with no other coverage, and its failure points the wrong
     * way: anything unexpected reads as IDLE, which hibernates a tab that may be mid-playback,
     * with no error and no log line. `executeJavaScript` returns `Any?`, so a marshalling change
     * that wrapped or quoted the string would silently collapse every branch.
     */
    @Test
    fun `the script result maps to a state, tolerating how it is marshalled`() {
        assertEquals(MEDIA, TabHibernation.busyStateFromScriptResult("media"))
        // Quoted, padded and differently-cased forms all still resolve.
        assertEquals(MEDIA, TabHibernation.busyStateFromScriptResult("\"media\""))
        assertEquals(MEDIA, TabHibernation.busyStateFromScriptResult("  media  "))
        // "input" was a state until the predicate behind it was cut; it must not linger.
        assertEquals(IDLE, TabHibernation.busyStateFromScriptResult("input"))
        assertEquals(MEDIA, TabHibernation.busyStateFromScriptResult("MEDIA"))
    }

    @Test
    fun `an absent or unrecognized script result reads as idle`() {
        assertEquals(IDLE, TabHibernation.busyStateFromScriptResult(""))
        assertEquals(IDLE, TabHibernation.busyStateFromScriptResult(null))
        assertEquals(IDLE, TabHibernation.busyStateFromScriptResult(false))
        assertEquals(IDLE, TabHibernation.busyStateFromScriptResult(42))
        assertEquals(IDLE, TabHibernation.busyStateFromScriptResult("undefined"))
    }

    // endregion
}
