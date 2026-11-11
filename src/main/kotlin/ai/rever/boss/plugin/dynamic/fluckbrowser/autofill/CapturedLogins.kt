package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill


/**
 * Record of the last submitted login per origin, so a SavePasswordCallback (which carries
 * url + login only) can be paired with the value. In memory only, short-lived, never logged.
 */
internal class CapturedLogins(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
) {
    class Captured(
        val origin: String,
        val username: String,
        val password: String,
        val atMs: Long,
    ) {
        override fun toString() = "Captured($origin)"
    }

    private val entries = ArrayDeque<Captured>()

    @Synchronized
    fun record(
        pageUrl: String,
        username: String,
        password: String,
    ) {
        val origin = AutofillMapping.normalizeOrigin(pageUrl) ?: return
        if (password.isEmpty() || password.length > MAX_VALUE || username.length > MAX_VALUE) return
        prune()
        entries.removeAll { it.origin == origin && sameLogin(it.username, username) }
        entries.addLast(Captured(origin, username.trim(), password, clock()))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /**
     * The captured value for a Chromium save of ([url], [login]): same origin and same login.
     * A capture with no username only pairs with a blank login.
     */
    @Synchronized
    @Suppress("ReturnCount")
    fun take(
        url: String,
        login: String,
    ): Captured? {
        prune()
        val origin = AutofillMapping.normalizeOrigin(url) ?: return null
        val hit = entries.lastOrNull { it.origin == origin && sameLogin(it.username, login) } ?: return null
        entries.remove(hit)
        return hit
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun prune() {
        val now = clock()
        entries.removeAll { now - it.atMs !in 0..ttlMs }
    }

    companion object {
        const val TTL_MS = 3 * 60_000L
        const val MAX_ENTRIES = 16
        const val MAX_VALUE = 1024

        fun sameLogin(
            a: String,
            b: String,
        ) = a.trim().equals(b.trim(), ignoreCase = true)
    }
}
