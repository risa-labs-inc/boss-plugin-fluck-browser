package ai.rever.boss.plugin.dynamic.fluckbrowser.share

/**
 * A running reverse tunnel that exposes the local co-browse server (`127.0.0.1:<port>`)
 * at a public HTTPS URL reachable from any network. Implemented by
 * [CloudflaredExposer.QuickTunnel] (primary) and [LocalhostRunExposer.LhrTunnel]
 * (fallback) so [BrowserShareManager] can hold either behind one type and try them
 * in order.
 *
 * The tunnel lives only as long as its backing process; the holder calls [destroy]
 * on teardown / refresh.
 */
interface RemoteTunnel {
    /** The assigned public URL, or null on timeout / early process exit. */
    fun awaitUrl(timeoutMs: Long = 30_000): String?

    /** True once the tunnel is actually routable (an edge/relay connection is up). */
    fun awaitReady(timeoutMs: Long = 20_000): Boolean

    /** Kill the tunnel (ends the public URL). Idempotent. */
    fun destroy()
}
