package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Cloudflare Quick Tunnel launcher for co-browse remote sharing — the same
 * mechanism BossTerm uses. Shells out to:
 *
 *     cloudflared --no-autoupdate tunnel --url http://127.0.0.1:<port>
 *
 * which opens an OUTBOUND connection to Cloudflare's edge (traverses NAT, no
 * port-forwarding, TLS included) and prints a public
 * `https://<random>.trycloudflare.com` URL with no account/DNS/config. The tunnel
 * lives only as long as the process, so [start] returns a [QuickTunnel] the caller
 * holds and [QuickTunnel.destroy]s on teardown.
 *
 * Reuses an already-installed cloudflared: BossTerm drops a managed copy at
 * `~/.bossterm/bin/cloudflared`; we also check PATH and Homebrew. Auto-install /
 * SHA-pinned download (BossTerm's CloudflaredExposer) is intentionally out of scope
 * here — if cloudflared is absent, sharing falls back to the loopback URL.
 */
object CloudflaredExposer {
    private val log = Logger.getLogger("CoBrowseCloudflared")

    private val isWindows: Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private val binName: String = if (isWindows) "cloudflared.exe" else "cloudflared"

    // Reuse BossTerm's managed binary first, then PATH, then Homebrew (Unix).
    private fun candidates(): List<String> = buildList {
        add(File(System.getProperty("user.home"), ".bossterm/bin/$binName").absolutePath)
        add(binName)
        if (!isWindows) {
            add("/opt/homebrew/bin/cloudflared")
            add("/usr/local/bin/cloudflared")
        }
    }

    private fun bin(): String? = candidates().firstOrNull { runCmd(listOf(it, "--version"), 5) != null }

    /** True if a working cloudflared is present. Blocking — call off the UI thread. */
    fun isInstalled(): Boolean = bin() != null

    /**
     * Start a quick tunnel to `127.0.0.1:`[port]. Returns immediately with a
     * [QuickTunnel] (the process is long-lived); null if cloudflared is missing or
     * failed to spawn. Call off the UI thread (the `--version` probe blocks briefly).
     */
    fun start(port: Int): QuickTunnel? {
        val b = bin() ?: run { log.warning("cloudflared not found; cannot start quick tunnel"); return null }
        return try {
            // `--no-autoupdate` is GLOBAL and must precede `tunnel`, or an auto-update
            // mid-session would restart the process and drop the tunnel.
            val proc = ProcessBuilder(b, "--no-autoupdate", "tunnel", "--url", "http://127.0.0.1:$port")
                .redirectErrorStream(true)
                .start()
                .also { runCatching { it.outputStream.close() } }
            QuickTunnel(proc)
        } catch (e: Exception) {
            log.warning("failed to start cloudflared: ${e.message}")
            null
        }
    }

    /**
     * A running quick tunnel. A daemon thread drains cloudflared's output, completing
     * [awaitUrl] when the public URL prints and [awaitReady] when an edge connection
     * registers (the point at which the URL actually routes to us).
     */
    class QuickTunnel internal constructor(val process: Process) {
        private val urlFuture = CompletableFuture<String?>()
        private val readyFuture = CompletableFuture<Boolean>()
        private val urlRe = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")
        private val readyRe = Regex("Registered tunnel connection")

        init {
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!urlFuture.isDone) urlRe.find(line)?.value?.let { urlFuture.complete(it) }
                        if (!readyFuture.isDone && readyRe.containsMatchIn(line)) readyFuture.complete(true)
                    }
                }
                urlFuture.complete(null)    // EOF without a URL
                readyFuture.complete(false) // EOF without an edge connection
            }.apply { isDaemon = true; name = "cobrowse-cloudflared"; start() }
        }

        /** The assigned `*.trycloudflare.com` URL, or null on timeout / early exit. */
        fun awaitUrl(timeoutMs: Long = 30_000): String? =
            runCatching { urlFuture.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

        /** True once cloudflared registers an edge connection (the tunnel is routable). */
        fun awaitReady(timeoutMs: Long = 20_000): Boolean =
            runCatching { readyFuture.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

        /** Kill the tunnel (ends the public URL). */
        fun destroy() { runCatching { process.destroyForcibly() } }
    }

    private fun runCmd(cmd: List<String>, timeoutSec: Long): String? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly(); null
        } else if (p.exitValue() == 0) "ok" else null
    } catch (e: Exception) {
        null
    }
}
