package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.fluckbrowser.share.BrowserShareManager

/**
 * Fluck Browser dynamic plugin - Loaded from external JAR.
 *
 * Provides embedded web browser TAB (main panel) using BrowserService from PluginContext.
 * In addition to browsing it hosts the co-browse tab-sharing server
 * ([BrowserShareManager]) so a tab's rendered DOM can be mirrored to a remote
 * viewer (web link or peer BossConsole) with approval-gated control.
 *
 * PRIVATE: This plugin is proprietary and not open source.
 */
class FluckBrowserDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.fluckbrowser"
    override val displayName: String = "Fluck Browser"
    override val version: String = "1.0.16"
    override val description: String = "Full-featured embedded web browser tab with zoom, downloads, and secret integration"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-fluck-browser"

    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(FluckBrowserTabType) { tabInfo, ctx ->
            FluckBrowserTabComponent(ctx, tabInfo, context)
        }

        // Co-browse tab sharing: store context. The embedded server binds lazily on
        // the first share() call. Approval is surfaced BossTerm-style — the in-tab
        // ShareRequestToast banner + the Share window's PendingRequestsList — so no
        // host notification toast is posted (it duplicated those and looked off-style).
        BrowserShareManager.start(context)
    }

    override fun dispose() {
        // Tear down the share server (stops any active capture) before unregistering.
        BrowserShareManager.shutdown()
        pluginContext?.tabRegistry?.unregisterTabType(FluckBrowserTabType.typeId)
        pluginContext = null
    }
}
