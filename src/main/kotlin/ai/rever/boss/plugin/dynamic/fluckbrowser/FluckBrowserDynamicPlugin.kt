package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Fluck Browser dynamic plugin - Loaded from external JAR.
 *
 * Provides embedded web browser TAB (main panel) using BrowserService from PluginContext.
 * This plugin offers a full-featured browser experience with:
 * - URL bar with navigation controls
 * - Back/forward/reload buttons
 * - Zoom controls (dropdown with common zoom levels)
 * - Loading indicator (progress bar)
 * - Security indicator (HTTPS lock icon)
 * - Tab title and favicon updates
 * - Download management integration
 * - Secret/credential integration for form filling
 *
 * NOTE: This is a main panel TAB plugin, not a sidebar panel.
 * It registers as a TabType via tabRegistry.registerTabType().
 *
 * PRIVATE: This plugin is proprietary and not open source.
 */
class FluckBrowserDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.fluckbrowser"
    override val displayName: String = "Fluck Browser"
    override val version: String = "1.0.0"
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
    }

    override fun dispose() {
        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(FluckBrowserTabType.typeId)
        pluginContext = null
    }
}
