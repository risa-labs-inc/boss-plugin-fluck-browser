package ai.rever.boss.plugin.dynamic.fluckbrowser.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Asserts the ladder's decisions, not its plumbing.
 *
 * The behaviours pinned here are the ones that were wrong in the design before a red-team pass
 * caught them, and each would have shipped silently: an eviction loop with no terminal state, a
 * protection treated as immunity, a budget with no hysteresis, and middle rungs applied by a
 * controller that cannot perform them.
 */
class LadderPolicyTest {

    private fun tab(
        id: String = "t",
        rung: Rung = Rung.HIDDEN,
        hiddenForMs: Long = 0,
        dwellMs: Long = Long.MAX_VALUE,
        protection: Protection = Protection.NONE,
        hasDurableSnapshot: Boolean = true,
    ) = TabFacts(id, rung, hiddenForMs, dwellMs, protection, null, hasDurableSnapshot)

    // ------------------------------------------------------------------ rung descent

    @Test
    fun `foreground and automation tabs are never touched`() {
        assertEquals(LadderAction.Hold, LadderPolicy.nextRung(tab(rung = Rung.ACTIVE), true))
        assertEquals(
            LadderAction.Hold,
            LadderPolicy.nextRung(
                tab(hiddenForMs = Long.MAX_VALUE, protection = Protection.AUTOMATION),
                supportsLosslessRungs = true,
                escalation = Escalation.HARD,
            ),
            "an automation browser is not a tab: freezing it hangs a batch job rather than failing it",
        )
    }

    @Test
    fun `descends one rung at a time, on the configured windows`() {
        val cfg = LadderConfig.Full
        assertEquals(LadderAction.Hold, LadderPolicy.nextRung(tab(hiddenForMs = 10_000), true, config = cfg))
        assertEquals(
            LadderAction.Descend(Rung.TRIMMED),
            LadderPolicy.nextRung(tab(hiddenForMs = cfg.trimAfterMs), true, config = cfg),
        )
        assertEquals(
            LadderAction.Descend(Rung.FROZEN),
            LadderPolicy.nextRung(tab(rung = Rung.TRIMMED, hiddenForMs = cfg.freezeAfterMs), true, config = cfg),
        )
        assertEquals(
            LadderAction.Descend(Rung.DISCARDED),
            LadderPolicy.nextRung(tab(rung = Rung.FROZEN, hiddenForMs = cfg.discardAfterMs), true, config = cfg),
        )
    }

    @Test
    fun `without host lifecycle support the middle rungs are skipped, not faked`() {
        val cfg = LadderConfig.Full
        // Past the trim window, but the controller cannot trim. The honest answer is Hold,
        // not a Descend to a rung nothing will actually apply.
        assertEquals(
            LadderAction.Hold,
            LadderPolicy.nextRung(tab(hiddenForMs = cfg.trimAfterMs), supportsLosslessRungs = false, config = cfg),
        )
        assertEquals(
            LadderAction.Descend(Rung.DISCARDED),
            LadderPolicy.nextRung(tab(hiddenForMs = cfg.discardAfterMs), supportsLosslessRungs = false, config = cfg),
        )
    }

    @Test
    fun `dwell floor blocks descent even under hard escalation`() {
        assertEquals(
            LadderAction.Hold,
            LadderPolicy.nextRung(
                tab(hiddenForMs = Long.MAX_VALUE, dwellMs = 0),
                supportsLosslessRungs = true,
                escalation = Escalation.HARD,
            ),
            "a budget reading that oscillates must not drive freeze/thaw faster than the dwell floor",
        )
    }

    @Test
    fun `soft escalation exhausts lossless rungs and never discards`() {
        val frozen = tab(rung = Rung.FROZEN, hiddenForMs = 1_000)
        assertEquals(
            LadderAction.Hold,
            LadderPolicy.nextRung(frozen, true, escalation = Escalation.SOFT),
            "a soft escalation that discards is a hard one with a friendlier name",
        )
        assertEquals(
            LadderAction.Descend(Rung.DISCARDED),
            LadderPolicy.nextRung(frozen, true, escalation = Escalation.HARD),
        )
    }

    @Test
    fun `discard without a durable snapshot is blocked, not silently held`() {
        assertEquals(
            LadderAction.BlockedOnSnapshot,
            LadderPolicy.nextRung(
                tab(rung = Rung.FROZEN, hiddenForMs = 1_000, hasDurableSnapshot = false),
                supportsLosslessRungs = true,
                escalation = Escalation.HARD,
            ),
        )
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun `protection is priority, not immunity`() {
        val order = LadderPolicy.evictionOrder(
            listOf(
                tab("audible", hiddenForMs = 9_000_000, protection = Protection.AUDIBLE),
                tab("plain", hiddenForMs = 1_000),
                tab("dirty", hiddenForMs = 5_000_000, protection = Protection.DIRTY_FORM),
            ),
        ).map { it.tabId }
        assertEquals(listOf("plain", "dirty", "audible"), order)
        assertTrue("audible" in order, "a protected tab must remain reclaimable or the budget is unreachable")
    }

    @Test
    fun `absolute protection is removed from the candidate list entirely`() {
        val order = LadderPolicy.evictionOrder(
            listOf(tab("rpa", protection = Protection.AUTOMATION), tab("plain")),
        ).map { it.tabId }
        assertEquals(listOf("plain"), order)
    }

    @Test
    fun `oldest-first means longest since foregrounded`() {
        val order = LadderPolicy.evictionOrder(
            listOf(tab("recent", hiddenForMs = 1_000), tab("stale", hiddenForMs = 900_000)),
        ).map { it.tabId }
        assertEquals(listOf("stale", "recent"), order)
    }

    // ------------------------------------------------------------------ budget + escalation

    @Test
    fun `budget hysteresis holds between the watermarks`() {
        val cfg = LadderConfig.Full
        val budget = 1_000L
        assertEquals(Escalation.SOFT, LadderPolicy.escalationFor(900, budget, Escalation.IDLE, cfg))
        // 80% sits between the watermarks: whatever we were doing, keep doing.
        assertEquals(Escalation.SOFT, LadderPolicy.escalationFor(800, budget, Escalation.SOFT, cfg))
        assertEquals(Escalation.IDLE, LadderPolicy.escalationFor(800, budget, Escalation.IDLE, cfg))
        assertEquals(Escalation.IDLE, LadderPolicy.escalationFor(690, budget, Escalation.HARD, cfg))
    }

    @Test
    fun `an unreadable budget is not evidence of pressure`() {
        assertEquals(Escalation.IDLE, LadderPolicy.escalationFor(5_000, 0, Escalation.HARD))
        assertEquals(Escalation.IDLE, LadderPolicy.escalationFor(-1, 1_000, Escalation.HARD))
    }

    @Test
    fun `escalation terminates at SATURATED instead of looping`() {
        assertEquals(Escalation.HARD, LadderPolicy.advance(Escalation.SOFT, listOf(LadderAction.Hold)))
        assertEquals(
            Escalation.SATURATED,
            LadderPolicy.advance(Escalation.HARD, listOf(LadderAction.BlockedOnSnapshot, LadderAction.Hold)),
            "no move available under HARD is the terminal state, not another lap",
        )
        assertEquals(Escalation.SATURATED, LadderPolicy.advance(Escalation.SATURATED, listOf(LadderAction.Hold)))
    }

    @Test
    fun `progress under HARD keeps escalating rather than saturating`() {
        assertEquals(
            Escalation.HARD,
            LadderPolicy.advance(Escalation.HARD, listOf(LadderAction.Descend(Rung.DISCARDED))),
        )
        assertFalse(LadderPolicy.isSaturated(Escalation.HARD, listOf(LadderAction.Descend(Rung.FROZEN))))
    }

    @Test
    fun `config rejects inverted watermarks`() {
        val threw = runCatching { LadderConfig(pressureHighWatermark = 0.5, pressureLowWatermark = 0.9) }.isFailure
        assertTrue(threw, "inverted watermarks would oscillate escalation on every sample")
    }
}
