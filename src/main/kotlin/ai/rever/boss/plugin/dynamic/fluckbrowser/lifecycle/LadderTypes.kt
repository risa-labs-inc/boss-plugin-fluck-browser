package ai.rever.boss.plugin.dynamic.fluckbrowser.lifecycle

/**
 * The rungs a background tab can occupy, in increasing order of what it gives up.
 *
 * Named for what BOSS does, not for what the tab is: [HIDDEN] is the rung where BOSS does
 * *nothing* and Chromium's own hidden-tab throttling is the whole effect. Naming it as a rung
 * rather than leaving it implicit is deliberate - the first version of this design measured the
 * freeze win against ACTIVE, which flatters it enormously, when the honest comparison is against
 * an already-throttled hidden tab. A rung you cannot name is a baseline you will forget to
 * subtract.
 */
enum class Rung {
    /** Foreground. Nothing applied. */
    ACTIVE,

    /**
     * Backgrounded. BOSS has applied nothing; Chromium is throttling on its own.
     *
     * Not free of effect: timer throttling engages immediately and *intensive* throttling at
     * five minutes hidden. BOSS passes no switch disabling either (verified against
     * `FluckEngine.performanceSwitchesFor` and the live process arguments), so this rung is
     * always in force and every rung below is a delta on top of it.
     */
    HIDDEN,

    /** Chromium asked to purge decoded images, tiles and V8 caches. Page still live and interactive. */
    TRIMMED,

    /** Task queues suspended. Renderer alive, heap intact, thaw is one command and loses nothing. */
    FROZEN,

    /** Renderer process gone. The only rung that returns the big number, and the only one that loses state. */
    DISCARDED,
    ;

    /** True for the rungs that need no snapshot, because nothing is lost reaching them. */
    val isLossless: Boolean get() = this != DISCARDED
}

/**
 * How hard the ladder is currently pushing, and - critically - what it does when it cannot win.
 *
 * The terminal state is the point of this enum. An earlier design said "evict until under
 * budget", which does not terminate when every remaining tab is protected: it spins at exactly
 * the moment the app is about to die, which is the failure it exists to prevent.
 */
enum class Escalation {
    /** Under budget. Tabs still descend on idle timers, but nothing is being forced. */
    IDLE,

    /** Over budget. Apply lossless rungs ([Rung.TRIMMED], [Rung.FROZEN]) to unprotected tabs. */
    SOFT,

    /**
     * Still over budget after everything lossless. Discard, oldest first, **only after a durable
     * snapshot**. A tab whose snapshot fails is skipped, never force-killed.
     */
    HARD,

    /**
     * Nothing left to evict and still over budget.
     *
     * Defined behaviour, not a loop: stop evicting, raise the existing memory-pressure notice
     * with its "Restart in Ultra Lite" button, and refuse new browser creation with a visible
     * reason. Saturation is a state the user is told about, not one the process discovers by
     * crashing.
     */
    SATURATED,
}

/**
 * Why a tab should be evicted late. **Priority, not immunity.**
 *
 * The distinction is load-bearing. Treating these as immunity means a long-lived tab with a
 * dirty form - precisely the long-lived renderer that drove the 35-hour death - can never be
 * reclaimed, so the budget becomes unreachable and the ladder saturates while a leak keeps
 * growing. Treating them as priority means such a tab sorts last and is reclaimed only after
 * its state is safely captured.
 */
enum class Protection(
    /** Higher sorts later for eviction. */
    val weight: Int,
) {
    /** Nothing special about this tab. */
    NONE(0),

    /** User has typed something not yet submitted. Reported live over the page-event bridge. */
    DIRTY_FORM(30),

    /** Audible. Evicting mid-audio is the most user-visible failure the ladder can produce. */
    AUDIBLE(40),

    /** An active download is attached to this tab. */
    DOWNLOADING(50),

    /**
     * Popped out into the always-on-top video-call window, or otherwise driving live media.
     *
     * The host already publishes `isPoppedOut` to plugins for exactly this reason - v9.5.6 added
     * it so the hibernation guard would stop disposing a live call joined with the camera off.
     */
    LIVE_MEDIA(60),

    /**
     * Automation-owned: created with a `profileName` or `ephemeralProfile`.
     *
     * The one genuine immunity in the list, and it is not really a protection - it is a
     * statement that this browser is not a tab. RPA drives OncoEMR and CoverMyMeds through the
     * same browser layer, and a frozen automation tab does not fail, it *hangs*, which is worse
     * than a crash for a batch job. See [isAbsolute].
     */
    AUTOMATION(Int.MAX_VALUE),
    ;

    /** The only protection the ladder may never override, at any escalation. */
    val isAbsolute: Boolean get() = this == AUTOMATION
}

/**
 * What the ladder knows about one tab when it decides.
 *
 * A value type on purpose: every decision in [LadderPolicy] is a pure function of these, so the
 * policy is testable without a browser, a Compose runtime, or a machine of a particular size.
 */
data class TabFacts(
    val tabId: String,
    val rung: Rung,
    /** Wall-clock ms since this tab was last foregrounded. Zero while it is foreground. */
    val hiddenForMs: Long,
    /** Ms since the last rung change, for anti-thrash dwell enforcement. */
    val dwellMs: Long,
    val protection: Protection = Protection.NONE,
    /** Renderer RSS in KB if known, else null. Null must never be read as zero. */
    val rendererRssKb: Long? = null,
    /** False when the tab has never had a snapshot written, which blocks discard under HARD. */
    val hasDurableSnapshot: Boolean = false,
)

/**
 * The mechanism half of the ladder, kept behind an interface because the plugin cannot reach it yet.
 *
 * Rungs 1-2 are Chromium lifecycle commands that only the host can issue - the plugin has no CDP
 * access and `BrowserHandle` exposes no lifecycle call today. Splitting the interface out means
 * the ladder's *policy* ships and is tested now, and the mechanism plugs in when the host API
 * lands, rather than the whole feature waiting on it.
 *
 * [supportsLosslessRungs] is the honest signal: when false, [LadderPolicy] skips [Rung.TRIMMED]
 * and [Rung.FROZEN] entirely rather than pretending to apply them. A ladder that silently no-ops
 * its middle rungs is the failure mode this whole plan is most exposed to.
 */
interface LifecycleController {
    /** False today. True once the host exposes the lifecycle calls. */
    val supportsLosslessRungs: Boolean

    suspend fun trim(tabId: String): Boolean

    suspend fun freeze(tabId: String): Boolean

    suspend fun thaw(tabId: String): Boolean

    companion object {
        /**
         * The controller in force until the host API lands: no lossless rungs, discard only.
         *
         * This is not a placeholder that fails - it is the accurate description of what BOSS can
         * do today, and the ladder behaves correctly under it (it degrades to the existing
         * discard-only behaviour, but with the escalation ladder, ordering and terminal state
         * that the per-tab timers never had).
         */
        val DiscardOnly: LifecycleController =
            object : LifecycleController {
                override val supportsLosslessRungs = false

                override suspend fun trim(tabId: String) = false

                override suspend fun freeze(tabId: String) = false

                override suspend fun thaw(tabId: String) = false
            }
    }
}
