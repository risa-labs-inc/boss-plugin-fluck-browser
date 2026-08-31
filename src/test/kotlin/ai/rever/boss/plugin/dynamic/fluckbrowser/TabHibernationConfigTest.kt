package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle
import java.lang.reflect.Proxy
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
    private val PIP = TabHibernation.BusyState.PICTURE_IN_PICTURE

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
     * The *budget* is what must restart across a change of reason: carrying a spent one over
     * hands a newly audible tab an already-exhausted allowance and exempts it for no reason.
     * The interval deliberately does not - see the test below.
     */
    @Test
    fun `a changed reason restarts the budget`() = runBlocking {
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
    }

    /**
     * The budget resets on a changed reason; the interval must not. Resetting both pins an
     * alternating tab at the floor for the whole total ceiling - more probing than no reset at
     * all, and the opposite of what the backoff is for.
     */
    @Test
    fun `a changed reason does not restart the backoff`() = runBlocking {
        var probes = 0
        val waits = mutableListOf<Long>()
        TabHibernation.awaitQuiet(
            probe = { if (probes++ % 2 == 0) FULLSCREEN else MEDIA },
            onWait = { waits.add(it) },
            maxRechecks = 4,
        )
        assertTrue(waits.size >= 4, "expected several waits, got $waits")
        for (i in 1 until waits.size) {
            assertTrue(waits[i] >= waits[i - 1], "interval reset at $i despite alternating: $waits")
        }
        assertTrue(waits.last() > TabHibernation.MEDIA_RECHECK_MS, "never climbed off the floor: $waits")
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
        // The live handle is the load-bearing half: it errors on every unstubbed call, so
        // reaching FULLSCREEN proves the page was never probed. A null handle alone cannot
        // tell "fullscreen wins" from "there was nobody to ask".
        val exploding =
            Proxy.newProxyInstance(
                BrowserHandle::class.java.classLoader,
                arrayOf(BrowserHandle::class.java),
            ) { _, method, _ -> error("busyStateFor probed the page: ${method.name}") }
                as BrowserHandle
        assertEquals(FULLSCREEN, TabHibernation.busyStateFor(fullscreenBlocks = true, handle = exploding))
        assertEquals(
            FULLSCREEN,
            TabHibernation.busyStateFor(fullscreenBlocks = true, handle = null),
            "a null handle must not stop fullscreen being reported",
        )
        assertEquals(IDLE, TabHibernation.busyStateFor(fullscreenBlocks = false, handle = null))
    }

    /**
     * The per-reason reset must not cost the global bound. A tab that alternates - toggling
     * fullscreen on an audible video - resets the per-reason budget on every iteration, so
     * without a total ceiling the loop never terminates and the interval never climbs past its
     * first doubling.
     */
    @Test
    fun `an alternating tab still terminates`() = runBlocking {
        var probes = 0
        var waits = 0
        val hibernate =
            TabHibernation.awaitQuiet(
                probe = { if (probes++ % 2 == 0) FULLSCREEN else MEDIA },
                onWait = { waits++ },
                maxRechecks = 4,
            )
        assertTrue(hibernate != IDLE, "an always-busy tab must not be hibernated")
        assertTrue(waits <= 4 * 2, "expected the total ceiling to stop the loop, got $waits waits")
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
        // A pop-out on screen. Hibernating disposes the handle and takes the window with it, and
        // a pop-out only ever exists on a backgrounded tab - which is precisely the tab the idle
        // timer is coming for.
        assertEquals(PIP, TabHibernation.busyStateFromScriptResult("pip"))
        assertEquals(PIP, TabHibernation.busyStateFromScriptResult("\"PIP\"  "))
    }

    /**
     * The ordering inside the probe script, asserted here because it is the difference between a
     * call surviving and being cut: a popped-out call is muted on the near side and its remote
     * audio may go through Web Audio, so the media test can find nothing while a window on screen
     * is showing the meeting. Reversing the two lines in BUSY_SCRIPT would report IDLE.
     */
    @Test
    fun `a pop-out outranks playback in the probe script`() {
        val script = TabHibernation.BUSY_SCRIPT

        val pipAt = script.indexOf("pictureInPictureElement")
        val mediaAt = script.indexOf("volume")
        assertTrue(pipAt >= 0, "the probe no longer looks for a pop-out at all:\n$script")
        // Both kinds. A site-owned pop-out is a Document PiP window and leaves
        // pictureInPictureElement null, so the element check alone misses Meet entirely.
        assertTrue(
            script.contains("documentPictureInPicture.window"),
            "the probe misses a Document PiP window, which is the kind Meet opens:\n$script",
        )
        // The pop-out this app actually uses reparents the tab's surface, so neither PiP API
        // reports it - the page is simply visible while its tab is backgrounded.
        val shownAt = script.indexOf("visibilityState")
        assertTrue(
            shownAt >= 0,
            "the probe misses a surface pop-out, the kind BossConsole opens:\n$script",
        )
        // Compound, not visibility alone: a rendering mode that never reported a backgrounded
        // tab as hidden would otherwise exempt every tab and disable hibernation outright. The
        // playing-video test must sit INSIDE the condition that returns, not merely somewhere
        // after it - the media check further down also mentions `paused`, and a window-based
        // assertion was satisfied by that while the pairing was gone.
        val returnsShownAt = script.indexOf("return 'shown'", shownAt)
        assertTrue(
            returnsShownAt > shownAt,
            "the visibility check never returns 'shown':\n$script",
        )
        assertTrue(
            script.indexOf("paused", shownAt) in shownAt..returnsShownAt,
            "the visibility check is not paired with a playing-video test:\n$script",
        )
        assertTrue(mediaAt >= 0, "the probe no longer looks for playback:\n$script")
        assertTrue(
            pipAt < mediaAt,
            "playback is tested before the pop-out, so a muted call reports idle and gets cut",
        )
    }

    /**
     * A handle whose class really declares the member, which a `Proxy` over the plugin's own
     * (older) copy of `BrowserHandle` cannot do - the point of the reflective lookup is that the
     * host's class carries members this plugin never compiled against.
     */
    private class PoppedOutHandle(
        private val delegate: BrowserHandle,
    ) : BrowserHandle by delegate {
        @Suppress("unused")
        fun isPoppedOut(): Boolean = true
    }

    private fun probeReturning(result: Any?): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { _, _, _ -> result } as BrowserHandle

    private fun explodingHandle(): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { _, method, _ -> error("the page was probed: ${'$'}{method.name}") } as BrowserHandle

    @Test
    fun `the reflective lookup uses the JVM name Kotlin actually emits`() {
        // Kotlin names an `is`-prefixed Boolean property's getter after the property, so the host
        // method is `isPoppedOut()`. Looking for `getIsPoppedOut` throws, the lookup swallows it,
        // and the guard silently protects nothing - so the name is pinned rather than trusted.
        assertTrue(TabHibernation.isPoppedOut(PoppedOutHandle(explodingHandle())))
    }

    @Test
    fun `a popped-out handle is busy without consulting the probe`() =
        runBlocking {
            // The stub errors on every call, so reaching SHOWN_IN_POP_OUT proves the flag was
            // read and the page never probed - which is the whole point for a camera-off call,
            // where the probe has nothing to find.
            assertEquals(
                TabHibernation.BusyState.SHOWN_IN_POP_OUT,
                TabHibernation.busyStateFor(
                    fullscreenBlocks = false,
                    handle = PoppedOutHandle(explodingHandle()),
                ),
            )
        }

    @Test
    fun `a host with no isPoppedOut member falls back to the probe`() =
        runBlocking {
            // An older host's handle has no such member at all. The lookup must answer false
            // rather than throw, or every hibernation decision on that host fails.
            val noMember =
                Proxy.newProxyInstance(
                    BrowserHandle::class.java.classLoader,
                    arrayOf(BrowserHandle::class.java),
                ) { _, _, _ -> null } as BrowserHandle

            assertFalse(TabHibernation.isPoppedOut(noMember))
        }

    @Test
    fun `fullscreen still outranks a popped-out handle`() =
        runBlocking {
            assertEquals(
                TabHibernation.BusyState.FULLSCREEN,
                TabHibernation.busyStateFor(
                    fullscreenBlocks = true,
                    handle = PoppedOutHandle(explodingHandle()),
                ),
            )
        }

    @Test
    fun `the host's off switch stops the pop-out exemption`() =
        runBlocking {
            // Off means the host will not pop anything out, so a lingering isPoppedOut must not
            // keep exempting a tab the user asked to be left alone. The probe still runs, and
            // this handle answers nothing, so the tab is idle and hibernates.
            assertEquals(
                TabHibernation.BusyState.IDLE,
                TabHibernation.busyStateFor(
                    fullscreenBlocks = false,
                    handle = PoppedOutHandle(probeReturning(null)),
                    popOutEnabled = false,
                ),
            )
        }

    @Test
    fun `an absent host property leaves the pop-out guard on`() {
        // A plugin on a host too old to publish the key must not switch the guard off and start
        // cutting calls, so anything unparseable - absent included - reads as ON.
        assertTrue(TabHibernation.autoPipEnabled(fromHost = null))
        assertTrue(TabHibernation.autoPipEnabled(fromHost = "maybe"))
        assertTrue(TabHibernation.autoPipEnabled(fromHost = "true"))
        assertFalse(TabHibernation.autoPipEnabled(fromHost = "false"))
        assertFalse(TabHibernation.autoPipEnabled(fromHost = " OFF "))
    }

    @Test
    fun `a shown result maps to the pop-out state`() {
        assertEquals(
            TabHibernation.BusyState.SHOWN_IN_POP_OUT,
            TabHibernation.busyStateFromScriptResult("shown"),
        )
        // Quoted and cased the way a marshalling change could deliver it.
        assertEquals(
            TabHibernation.BusyState.SHOWN_IN_POP_OUT,
            TabHibernation.busyStateFromScriptResult("\"SHOWN\""),
        )
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
