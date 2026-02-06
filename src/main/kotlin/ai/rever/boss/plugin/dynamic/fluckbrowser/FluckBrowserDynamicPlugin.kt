package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.browser.BrowserService

/**
 * Fluck Browser dynamic plugin - Loaded from external JAR.
 *
 * Provides embedded web browser panel using BrowserService from PluginContext.
 * This plugin offers a full-featured browser experience with:
 * - URL bar with navigation controls
 * - Tab title and favicon updates
 * - Download management integration
 * - Secret/credential integration for form filling
 *
 * PRIVATE: This plugin is proprietary and not open source.
 */
class FluckBrowserDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.fluckbrowser"
    override val displayName: String = "Fluck Browser (Dynamic)"
    override val version: String = "1.0.0"
    override val description: String = "Full-featured embedded web browser with download and secret integration"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-fluck-browser"

    private var browserService: BrowserService? = null

    override fun register(context: PluginContext) {
        browserService = context.browserService

        // Check if browser service is available
        if (browserService == null || browserService?.isAvailable() != true) {
            // Register stub panel when browser is not available
            context.panelRegistry.registerPanel(FluckBrowserInfo) { ctx, panelInfo ->
                FluckBrowserComponent(ctx, panelInfo, null)
            }
            return
        }

        // Register the browser panel with full functionality
        context.panelRegistry.registerPanel(FluckBrowserInfo) { ctx, panelInfo ->
            FluckBrowserComponent(ctx, panelInfo, browserService)
        }
    }
}
