package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Tailscale Funnel launcher for co-browse remote sharing — a fast cross-network
 * tunnel alternative to Cloudflare/localhost.run, modeled on BossTerm's
 * `TailscaleExposer`. Shells out to the `tailscale` CLI:
 *
 *     tailscale funnel --bg <port>
 *
 * which exposes the local co-browse port to the public internet at
 * `https://<magic-dns-name>` via Tailscale's edge (TLS included). Because Tailscale
 * is WireGuard-based and often establishes a near-direct path, it is typically much
 * faster than a relay tunnel — and has no per-IP creation rate limit like Cloudflare
 * quick tunnels.
 *
 * Requires the user's own Tailscale: installed, logged in, and Funnel enabled for the
 * node (tailnet ACL / `tailscale funnel` consent). If any of that is missing the call
 * fails fast and the caller falls back to the next tunnel. All calls are best-effort
 * with short timeouts; stdin is closed so a CLI that would prompt gets EOF instead of
 * hanging.
 */
object TailscaleExposer {
    private val log = Logger.getLogger("CoBrowseTailscale")
    private val json = Json { ignoreUnknownKeys = true }

    private val isWindows: Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true

    // "tailscale" first (honors PATH), then typical macOS/brew/Windows locations.
    private fun candidates(): List<String> = buildList {
        add("tailscale")
        if (isWindows) {
            add("C:\\Program Files\\Tailscale\\tailscale.exe")
        } else {
            add("/usr/local/bin/tailscale")
            add("/opt/homebrew/bin/tailscale")
            add("/Applications/Tailscale.app/Contents/MacOS/Tailscale")
        }
    }

    private fun bin(): String? = candidates().firstOrNull { runCmd(listOf(it, "version"), 3) != null }

    /** True if a working `tailscale` CLI/app is present. Blocking — call off the UI thread. */
    fun isInstalled(): Boolean = bin() != null

    /**
     * Expose [port] via `tailscale funnel --bg`. Returns an [TsTunnel] holding the
     * published `https://<magic-dns>` URL, or null if the CLI is missing / not logged
     * in / Funnel isn't enabled. Call off the UI thread.
     */
    fun start(port: Int): TsTunnel? {
        val b = bin() ?: run { log.info("tailscale CLI not found; skipping Funnel"); return null }
        if (runCmd(listOf(b, "funnel", "--bg", port.toString()), 20) == null) {
            log.info("`tailscale funnel --bg $port` failed (not logged in or Funnel not enabled?)")
            return null
        }
        val dns = magicDnsName(b) ?: run {
            log.warning("tailscale funnel up but could not resolve MagicDNS name")
            runCatching { runCmd(listOf(b, "funnel", "--https=443", "off"), 10) }
            return null
        }
        val url = "https://$dns"
        log.info("Tailscale Funnel active for co-browse port $port → $url")
        return TsTunnel(b, port, url)
    }

    private fun magicDnsName(b: String): String? {
        val out = runCmd(listOf(b, "status", "--json"), 5) ?: return null
        return runCatching {
            json.parseToJsonElement(out).jsonObject["Self"]
                ?.jsonObject?.get("DNSName")?.jsonPrimitive?.content?.trimEnd('.')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** A live Tailscale Funnel mapping. [destroy] tears the funnel down for the port. */
    class TsTunnel internal constructor(
        private val bin: String,
        private val port: Int,
        private val url: String,
    ) : RemoteTunnel {
        // `funnel --bg` returns only once the mapping is live, so the URL is ready now.
        override fun awaitUrl(timeoutMs: Long): String = url
        override fun awaitReady(timeoutMs: Long): Boolean = true
        override fun destroy() {
            runCatching { runCmd(listOf(bin, "funnel", "--https=443", "off"), 10) }
        }
    }

    /** Run [cmd], draining output off-thread so a non-terminating process can't block the deadline. */
    private fun runCmd(cmd: List<String>, timeoutSec: Long): String? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        runCatching { p.outputStream.close() } // EOF to anything that would prompt
        val sb = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly(); null
        } else {
            reader.join(500)
            if (p.exitValue() == 0) sb.toString() else null
        }
    } catch (e: Exception) {
        null
    }
}
