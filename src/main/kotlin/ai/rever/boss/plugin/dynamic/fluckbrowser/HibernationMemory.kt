package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.io.File

/**
 * Available-memory readings for the hibernation pressure accelerant.
 *
 * A near-copy of the host's `SystemMemory`, duplicated deliberately: plugins load in their own
 * classloader and cannot see host classes, so the alternative is a new plugin-api surface for one
 * number. Keep the two in step if either changes.
 *
 * **Why this exists at all.** The accelerant used to divide the JDK's `freeMemorySize` by total
 * and compare against 0.15. That reading reports genuinely-unused pages, which a healthy OS keeps
 * near zero because it would rather cache than idle. Measured on a 128 GB Mac that macOS itself
 * called "92% free", it returned 0.9 GB - a fraction of **0.0073**. Permanently below 0.15, so the
 * accelerant fired on every single evaluation and the pressure delay silently became *the* delay
 * on every Mac. A tier's configured idle timeout could never take effect.
 */
internal object HibernationMemory {
    /**
     * How long a reading may be reused, on **every** platform.
     *
     * Deliberately longer than `TabHibernation.PRESSURE_RECHECK_CHUNK_MS`. The two used to be
     * exactly equal, which sounds like a match and guarantees the opposite: the cached value is
     * always just-expired when the next chunk wakes, so a single backgrounded tab forked
     * `/usr/bin/vm_stat` every 30 seconds for its whole idle window - precisely what the cache
     * exists to prevent. A longer TTL makes a hit actually possible.
     *
     * This started as a macOS-only cache sized for bursts (restoring a session, closing a split).
     * The chunked re-evaluation in `TabHibernation.awaitIdleWindow` turned it into a poll loop,
     * which is what it is now sized for.
     */
    internal const val CACHE_TTL_MS = 60_000L

    private const val CACHE_TTL_NANOS = CACHE_TTL_MS * 1_000_000L

    private const val VM_STAT_TIMEOUT_SECONDS = 5L

    private val osBean: com.sun.management.OperatingSystemMXBean? =
        java.lang.management.ManagementFactory
            .getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    /** [fraction] is null for a reading that failed, which is cached like any other. */
    private data class Reading(val fraction: Double?, val takenAtNanos: Long)

    @Volatile
    private var cache: Reading? = null

    /** Total installed RAM in bytes, or 0 when unreadable. */
    fun totalBytes(): Long = osBean?.totalMemorySize?.takeIf { it > 0L } ?: 0L

    /**
     * Available memory as a fraction of total, or null when either reading failed.
     *
     * Cached on **every** platform, not just macOS. The Linux path reads and parses
     * `/proc/meminfo`, and before this it did so twice per call - once for `MemAvailable`, once
     * for `MemTotal` - with no cache at all. A 40-tab session meant 80 file reads every 30 seconds
     * for the whole idle window, while the comment at the call site claimed re-asking was nearly
     * free.
     *
     * Null rather than 0.0 on purpose. The caller must not read "could not measure" as "out of
     * memory" and start hibernating the user's tabs after a minute.
     */
    fun availableFraction(): Double? {
        val now = System.nanoTime()
        cache?.takeIf { now - it.takenAtNanos < CACHE_TTL_NANOS }?.let { return it.fraction }
        // Synchronized on the miss. Tabs wake on roughly the cache cadence, so a session's tabs
        // tend to cross the expiry boundary together - each seeing a stale cache and each taking
        // its own reading. Re-checked inside the lock so only the first arrival pays.
        return synchronized(this) { refresh(now) }
    }

    private fun refresh(now: Long): Double? {
        cache?.takeIf { now - it.takenAtNanos < CACHE_TTL_NANOS }?.let { return it.fraction }
        val reading = measureFraction()
        cache = Reading(reading, now)
        return reading
    }

    /**
     * Takes a fresh reading, with numerator and denominator from the **same source**.
     *
     * On Linux both come out of one `/proc/meminfo` read: the JDK reports a cgroup-constrained
     * total while the file reports the host's, and mixing them describes two different machines.
     *
     * That makes the ratio self-consistent rather than container-accurate - in an unmodified
     * container `/proc/meminfo` still reports the host, so this describes the host. Reading
     * `memory.max` from the cgroup would be the actual container figure, if that case ever proves
     * worth handling.
     */
    private fun measureFraction(): Double? =
        when {
            osName.startsWith("linux") -> {
                // Both fields or neither. Falling back to the JDK total when MemTotal is absent
                // would divide a /proc numerator by a cgroup-constrained denominator - exactly the
                // source-mixing this branch exists to avoid.
                val meminfo = readMeminfo()
                fraction(
                    available = meminfo?.let { parseMemAvailableKb(it) }?.times(1024L),
                    total = meminfo?.let { parseMemTotalKb(it) }?.times(1024L) ?: return null,
                )
            }

            osName.startsWith("mac") -> fraction(vmStatBytes(), totalBytes())

            // Windows only: ullAvailPhys already means "available" rather than "untouched".
            osName.startsWith("windows") -> fraction(osBean?.freeMemorySize, totalBytes())

            // Anything else - FreeBSD, Solaris, an unrecognized name - is unknown rather than
            // freeMemorySize. Using the JDK reading there would reintroduce the free-vs-available
            // bug this class exists to fix, on exactly the platforms nobody is testing.
            else -> null
        }

    /**
     * Pure ratio, split out so the null propagation and guards are testable.
     *
     * [available] is nullable so that a genuine zero stays distinguishable from a failed read.
     * Collapsing both into 0 meant a machine that really had run out - the single case the
     * accelerant exists for - looked exactly like one we could not measure, and was ignored.
     */
    internal fun fraction(
        available: Long?,
        total: Long,
    ): Double? {
        if (available == null || available < 0L || total <= 0L) return null
        return (available.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun readMeminfo(): String? =
        runCatching {
            val meminfo = File("/proc/meminfo")
            if (meminfo.exists()) meminfo.readText() else null
        }.getOrNull()

    /** `MemAvailable` in kB from `/proc/meminfo` text, or null when absent. */
    internal fun parseMemAvailableKb(meminfo: String): Long? = parseKb(meminfo, "MemAvailable:")

    /** `MemTotal` in kB from `/proc/meminfo` text, or null when absent. */
    internal fun parseMemTotalKb(meminfo: String): Long? = parseKb(meminfo, "MemTotal:")

    private fun parseKb(
        meminfo: String,
        field: String,
    ): Long? =
        meminfo
            .lineSequence()
            .firstOrNull { it.startsWith(field) }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()

    private fun vmStatBytes(): Long? =
        runCatching {
            val process =
                ProcessBuilder("/usr/bin/vm_stat")
                    .redirectErrorStream(true)
                    .start()
            try {
                // waitFor before draining. Reading to EOF first makes the timeout unreachable,
                // because a wedged vm_stat blocks in readText() and waitFor is never called. Safe
                // here only because vm_stat emits a fixed ~1 KB table, well under the pipe buffer.
                if (!process.waitFor(VM_STAT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                parseVmStatAvailableBytes(process.inputStream.bufferedReader().use { it.readText() })
            } finally {
                // Only load-bearing for the timeout branch, which returns without reading either
                // stream; destroyForcibly does not close them. Closing an already-closed stream
                // is a no-op, so the success path double-closing inputStream is harmless.
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }.getOrNull()

    /**
     * Reclaimable bytes from `vm_stat` output, or null when unparseable.
     *
     * Sums free, inactive, speculative and purgeable pages. **This over-counts** - Mach's buckets
     * are not disjoint - so the bias is toward "plenty available", which makes the accelerant
     * under-fire rather than false-alarm. Under-firing is the safe direction: the tab simply waits
     * its normal idle timeout.
     */
    internal fun parseVmStatAvailableBytes(output: String): Long? {
        val pageSize =
            Regex("page size of (\\d+) bytes")
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: return null

        fun pages(label: String): Long? =
            Regex("""^Pages $label:\s+(\d+)\.""", RegexOption.MULTILINE)
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()

        val counts = listOf("free", "inactive", "speculative", "purgeable").map(::pages)
        // Null only when no page-count line matched at all, which means output we do not
        // understand. A set of lines that genuinely sums to zero is the deepest-pressure reading
        // there is, and returning null for it would have the caller ignore the one case the
        // accelerant exists for - the same zero-versus-unknown conflation this file argues against
        // everywhere else.
        if (counts.all { it == null }) return null
        return counts.filterNotNull().sum() * pageSize
    }
}
