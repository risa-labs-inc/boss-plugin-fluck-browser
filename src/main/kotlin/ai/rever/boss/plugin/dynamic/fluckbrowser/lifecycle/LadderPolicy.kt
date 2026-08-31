package ai.rever.boss.plugin.dynamic.fluckbrowser.lifecycle

/**
 * Timings and thresholds for one resource tier.
 *
 * A value type rather than scattered constants so the whole policy is one table, and so
 * [LadderPolicy] can be asserted against it without a running app - the shape
 * `BossResourceMode` in the host already uses for the same reason.
 */
data class LadderConfig(
    /** Hidden for this long before the lossless purge is applied. */
    val trimAfterMs: Long = 60_000,
    /**
     * Hidden for this long before freezing.
     *
     * Five minutes is not arbitrary: it is where Chromium's own *intensive* throttling engages.
     * Freezing earlier competes with a throttle that is about to arrive for free; freezing at
     * the same boundary means the rung is only ever asked to beat the baseline at its strongest.
     */
    val freezeAfterMs: Long = 300_000,
    /** Hidden for this long before discarding on idle alone, absent any budget pressure. */
    val discardAfterMs: Long = 1_800_000,
    /** Minimum time at a rung before descending again. The anti-thrash floor. */
    val minDwellMs: Long = 30_000,
    /** Fraction of budget at which escalation begins. */
    val pressureHighWatermark: Double = 0.85,
    /** Fraction of budget at which escalation releases. Hysteresis, so churn cannot oscillate the ladder. */
    val pressureLowWatermark: Double = 0.70,
) {
    init {
        require(pressureLowWatermark < pressureHighWatermark) {
            "low watermark must sit below high, or escalation oscillates on every sample"
        }
    }

    companion object {
        /** Mirrors the host's FULL / LITE / ULTRA_LITE windows, which the plugin does not yet read. */
        val Full = LadderConfig()
        val Lite = LadderConfig(trimAfterMs = 30_000, freezeAfterMs = 300_000, discardAfterMs = 600_000)
        val UltraLite = LadderConfig(trimAfterMs = 15_000, freezeAfterMs = 300_000, discardAfterMs = 120_000)
    }
}

/** What the ladder decided to do to one tab. */
sealed interface LadderAction {
    /** Leave it alone. */
    data object Hold : LadderAction

    /** Descend to [to]. */
    data class Descend(val to: Rung) : LadderAction

    /**
     * Wanted to discard but the tab has no durable snapshot yet.
     *
     * A distinct outcome rather than a silent [Hold] because it is the difference between
     * "nothing to do" and "blocked on the snapshot pipeline", and only one of those is a bug
     * when it persists.
     */
    data object BlockedOnSnapshot : LadderAction
}

/**
 * Pure decision logic for the lifecycle ladder.
 *
 * Every function here is a function of its arguments only - no clock, no coroutines, no browser.
 * That is what makes the interesting behaviour (ordering, hysteresis, saturation) assertable in
 * a unit test, which matters more than usual here: the failure this design is most exposed to is
 * silent non-operation, and silence is exactly what an integration test is worst at catching.
 */
object LadderPolicy {

    /**
     * Escalation from budget occupancy, with hysteresis.
     *
     * Takes [previous] because hysteresis is by definition path-dependent: between the two
     * watermarks the answer is "whatever it already was". Passing it in keeps the function pure
     * rather than hiding the state in the object.
     */
    fun escalationFor(
        usedBytes: Long,
        budgetBytes: Long,
        previous: Escalation,
        config: LadderConfig = LadderConfig.Full,
    ): Escalation {
        // An unknown or nonsensical budget is not evidence of pressure. Escalating on a failed
        // measurement would discard the user's tabs because a `ps` call returned garbage.
        if (budgetBytes <= 0 || usedBytes < 0) return Escalation.IDLE

        val occupancy = usedBytes.toDouble() / budgetBytes.toDouble()
        return when {
            occupancy >= config.pressureHighWatermark ->
                if (previous == Escalation.IDLE) Escalation.SOFT else previous

            occupancy <= config.pressureLowWatermark -> Escalation.IDLE

            // Between the watermarks: hold whatever we were doing.
            else -> previous
        }
    }

    /**
     * The next rung for one tab, or [LadderAction.Hold].
     *
     * Descends at most one rung per call. Skipping rungs would mean a tab that has just been
     * backgrounded could be discarded in a single decision, which is both surprising and
     * unnecessary - the caller runs on a timer, so one rung per tick converges quickly enough
     * and keeps every transition individually observable.
     */
    fun nextRung(
        facts: TabFacts,
        supportsLosslessRungs: Boolean,
        escalation: Escalation = Escalation.IDLE,
        config: LadderConfig = LadderConfig.Full,
    ): LadderAction {
        if (facts.rung == Rung.ACTIVE) return LadderAction.Hold
        if (facts.protection.isAbsolute) return LadderAction.Hold
        if (facts.rung == Rung.DISCARDED) return LadderAction.Hold

        // Anti-thrash floor. Applies to every descent including a forced one: a budget reading
        // that oscillates must not be able to drive freeze/thaw cycles faster than this.
        if (facts.dwellMs < config.minDwellMs) return LadderAction.Hold

        // Without host lifecycle support the middle rungs do not exist. Say so by skipping them
        // rather than by applying them and having them quietly do nothing.
        if (!supportsLosslessRungs) {
            return if (readyToDiscard(facts, escalation, config)) discardOrBlock(facts) else LadderAction.Hold
        }

        return when (facts.rung) {
            Rung.HIDDEN ->
                if (facts.hiddenForMs >= config.trimAfterMs || escalation != Escalation.IDLE) {
                    LadderAction.Descend(Rung.TRIMMED)
                } else {
                    LadderAction.Hold
                }

            Rung.TRIMMED ->
                if (facts.hiddenForMs >= config.freezeAfterMs || escalation != Escalation.IDLE) {
                    LadderAction.Descend(Rung.FROZEN)
                } else {
                    LadderAction.Hold
                }

            Rung.FROZEN ->
                if (readyToDiscard(facts, escalation, config)) discardOrBlock(facts) else LadderAction.Hold

            Rung.ACTIVE, Rung.DISCARDED -> LadderAction.Hold
        }
    }

    /**
     * Discard is reached two ways: the idle window expired, or escalation reached [Escalation.HARD].
     *
     * [Escalation.SOFT] deliberately does not discard. Soft exists to exhaust everything lossless
     * first, and a soft escalation that discards is just a hard one with a friendlier name.
     */
    private fun readyToDiscard(
        facts: TabFacts,
        escalation: Escalation,
        config: LadderConfig,
    ): Boolean = facts.hiddenForMs >= config.discardAfterMs || escalation == Escalation.HARD

    private fun discardOrBlock(facts: TabFacts): LadderAction =
        if (facts.hasDurableSnapshot) LadderAction.Descend(Rung.DISCARDED) else LadderAction.BlockedOnSnapshot

    /**
     * Eviction order: least valuable first.
     *
     * Sorted by protection weight ascending, then by longest-hidden first. Absolutely protected
     * tabs ([Protection.isAbsolute]) are removed rather than sorted last - an automation browser
     * is not a tab and must never be a candidate at any escalation.
     *
     * "Oldest first" is ambiguous unless pinned, so it is pinned here: oldest by *time since
     * last foregrounded*, not by time at the current rung. The user's sense of which tab they
     * have abandoned tracks the former.
     */
    fun evictionOrder(tabs: List<TabFacts>): List<TabFacts> =
        tabs.asSequence()
            .filterNot { it.protection.isAbsolute }
            .filter { it.rung != Rung.ACTIVE && it.rung != Rung.DISCARDED }
            .sortedWith(compareBy<TabFacts> { it.protection.weight }.thenByDescending { it.hiddenForMs })
            .toList()

    /**
     * Whether the ladder has run out of moves while still over budget.
     *
     * Saturation is not "no tabs left" - it is "no tab can be moved". A list of frozen tabs that
     * all lack snapshots saturates just as surely as an empty one, and that distinction is the
     * reason this takes actions rather than counts.
     */
    fun isSaturated(
        escalation: Escalation,
        actions: List<LadderAction>,
    ): Boolean = escalation == Escalation.HARD && actions.none { it is LadderAction.Descend }

    /**
     * Escalation for the next tick, given what this tick achieved.
     *
     * Promotes SOFT to HARD only once the lossless rungs are exhausted, so the ladder always
     * spends its cheap moves before its expensive one. Saturation is terminal for as long as
     * pressure lasts; it clears when [escalationFor] drops back to IDLE at the low watermark.
     */
    fun advance(
        current: Escalation,
        actionsThisTick: List<LadderAction>,
    ): Escalation =
        when (current) {
            Escalation.IDLE -> Escalation.IDLE
            Escalation.SOFT ->
                if (actionsThisTick.none { it is LadderAction.Descend }) Escalation.HARD else Escalation.SOFT
            Escalation.HARD ->
                if (isSaturated(Escalation.HARD, actionsThisTick)) Escalation.SATURATED else Escalation.HARD
            Escalation.SATURATED -> Escalation.SATURATED
        }
}
