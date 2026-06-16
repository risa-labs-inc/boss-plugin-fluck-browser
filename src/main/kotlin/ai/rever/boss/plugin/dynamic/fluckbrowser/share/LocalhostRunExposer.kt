package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * localhost.run reverse-tunnel launcher — the co-browse fallback for when the
 * Cloudflare quick tunnel is unavailable (e.g. rate-limited with HTTP 429 after
 * many tunnels, or cloudflared isn't installed).
 *
 * Unlike cloudflared this needs **no extra binary**: it rides the system `ssh`
 * client (always present on macOS/Linux) to open a reverse forward to
 * localhost.run's public relay:
 *
 *     ssh -R 80:localhost:<port> nokey@localhost.run
 *
 * The `nokey@` user connects without an SSH key (the relay accepts `none` auth),
 * the relay terminates TLS and assigns a public `https://<random>.lhr.life` URL
 * that routes back down the SSH connection to our local `127.0.0.1:<port>`. It is a
 * full cross-network tunnel (reachable from any network), and its rate limits are
 * independent of Cloudflare's — so it works while quick tunnels are throttled.
 *
 * The tunnel lives only as long as the ssh process; [start] returns an [LhrTunnel]
 * the caller holds and [LhrTunnel.destroy]s on teardown.
 */
object LocalhostRunExposer {
    private val log = Logger.getLogger("CoBrowseLocalhostRun")

    private val isWindows: Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private val sshBin: String = if (isWindows) "ssh.exe" else "ssh"

    /** True if a working `ssh` client is present. Blocking — call off the UI thread. */
    fun isInstalled(): Boolean = runCmd(listOf(sshBin, "-V"), 5)

    /**
     * Start a reverse tunnel to `127.0.0.1:`[port]. Returns immediately with an
     * [LhrTunnel] (the ssh process is long-lived); null if ssh can't be spawned.
     * Call off the UI thread.
     */
    fun start(port: Int): LhrTunnel? {
        return try {
            // -T            no pty (we only read banner output; avoids tty allocation)
            // StrictHostKeyChecking=no + UserKnownHostsFile=/dev/null  no host-key prompt/hang
            // ExitOnForwardFailure=yes  bail if the remote forward can't be set up
            // ServerAliveInterval keepalive so NAT/idle timeouts don't silently drop us
            val proc = ProcessBuilder(
                sshBin, "-T",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ConnectTimeout=15",
                "-o", "ServerAliveInterval=30",
                "-o", "ServerAliveCountMax=3",
                "-R", "80:localhost:$port",
                "nokey@localhost.run",
            )
                .redirectErrorStream(true)
                .start()
                .also { runCatching { it.outputStream.close() } }
            LhrTunnel(proc)
        } catch (e: Exception) {
            log.warning("failed to start ssh for localhost.run: ${e.message}")
            null
        }
    }

    /**
     * A running localhost.run tunnel. A daemon thread drains ssh's output, completing
     * [awaitUrl]/[awaitReady] when the relay prints the assigned `*.lhr.life` URL
     * (which it does only once the tunnel is actually established).
     */
    class LhrTunnel internal constructor(val process: Process) : RemoteTunnel {
        private val urlFuture = CompletableFuture<String?>()
        // The relay prints the URL only after the forward is live, so URL == ready.
        private val urlRe = Regex("""https://[a-z0-9-]+\.lhr\.life""")

        init {
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!urlFuture.isDone) urlRe.find(line)?.value?.let { urlFuture.complete(it) }
                    }
                }
                urlFuture.complete(null) // EOF without a URL (auth/forward failure)
            }.apply { isDaemon = true; name = "cobrowse-localhostrun"; start() }
        }

        /** The assigned `*.lhr.life` URL, or null on timeout / early exit. */
        override fun awaitUrl(timeoutMs: Long): String? =
            runCatching { urlFuture.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

        /** localhost.run prints the URL only once routable, so a URL implies ready. */
        override fun awaitReady(timeoutMs: Long): Boolean = awaitUrl(timeoutMs) != null

        /** Kill the tunnel (ends the public URL). */
        override fun destroy() { runCatching { process.destroyForcibly() } }
    }

    private fun runCmd(cmd: List<String>, timeoutSec: Long): Boolean = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        runCatching { p.inputStream.readBytes() } // drain so the pipe never blocks
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly(); false
        } else {
            // `ssh -V` exits 0 and prints the version to stderr; some builds exit 255
            // on bad args but the binary is still present. Treat "ran at all" as installed.
            true
        }
    } catch (e: Exception) {
        false
    }
}
