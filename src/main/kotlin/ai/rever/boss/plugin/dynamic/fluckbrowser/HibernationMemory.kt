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
     * How long a macOS `vm_stat` reading may be reused.
     *
     * `effectiveIdleMs()` is called once per tab backgrounding, which is user-paced, so this is
     * about bursts (restoring a session, closing a split) rather than a poll loop.
     */
    private const val CACHE_TTL_NANOS = 30_000L * 1_000_000L

    private const val VM_STAT_TIMEOUT_SECONDS = 5L

    private val osBean: com.sun.management.OperatingSystemMXBean? =
        java.lang.management.ManagementFactory
            .getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    private data class Reading(val bytes: Long, val takenAtNanos: Long)

    @Volatile
    private var macCache: Reading? = null

    /** Total installed RAM in bytes, or 0 when unreadable. */
    fun totalBytes(): Long = osBean?.totalMemorySize?.takeIf { it > 0L } ?: 0L

    /**
     * Memory available without evicting the user's working set, or 0 when it cannot be read.
     *
     * The platform branch is **total**: on a known platform an unreadable value is 0, meaning
     * "unknown", never a different and known-wrong metric. Falling back to `freeMemorySize` here
     * would reintroduce exactly the bug above whenever `vm_stat` was slow or unavailable.
     */
    fun availableBytes(): Long =
        when {
            osName.startsWith("linux") -> linuxAvailableBytes() ?: 0L
            osName.startsWith("mac") -> macAvailableBytes() ?: 0L
            // Windows: ullAvailPhys already means "available" rather than "untouched".
            else -> osBean?.freeMemorySize ?: 0L
        }

    /**
     * Available memory as a fraction of total, or null when either reading failed.
     *
     * Null rather than 0.0 on purpose. The caller must not read "could not measure" as "out of
     * memory" and start hibernating the user's tabs after a minute.
     */
    fun availableFraction(): Double? {
        val total = totalBytes()
        val available = availableBytes()
        if (total <= 0L || available <= 0L) return null
        return (available.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun linuxAvailableBytes(): Long? =
        runCatching {
            val meminfo = File("/proc/meminfo")
            if (!meminfo.exists()) return@runCatching null
            parseMemAvailableKb(meminfo.readText())?.times(1024L)
        }.getOrNull()

    /** `MemAvailable` in kB from `/proc/meminfo` text, or null when absent. */
    internal fun parseMemAvailableKb(meminfo: String): Long? =
        meminfo
            .lineSequence()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()

    private fun macAvailableBytes(): Long? {
        val now = System.nanoTime()
        val cached = macCache?.takeIf { now - it.takenAtNanos < CACHE_TTL_NANOS }
        return cached?.bytes ?: vmStatBytes()?.also { macCache = Reading(it, now) }
    }

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
                // destroyForcibly does not close these, and the timeout branch never reads them.
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

        fun pages(label: String): Long =
            Regex("""^Pages $label:\s+(\d+)\.""", RegexOption.MULTILINE)
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: 0L

        val total = pages("free") + pages("inactive") + pages("speculative") + pages("purgeable")
        return if (total > 0L) total * pageSize else null
    }
}
