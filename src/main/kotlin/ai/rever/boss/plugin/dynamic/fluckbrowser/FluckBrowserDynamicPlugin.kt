package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.KeyChordSpec
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginShortcutSpec
import ai.rever.boss.plugin.api.ShortcutActionProvider
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

    /**
     * Cmd+L — "Open Location", focusing the address bar of the browser tab in front of the user.
     *
     * Contributed rather than built into the host keymap because the field lives here. The host
     * unregisters this automatically on disable/unload, after which the chord goes inert; a user
     * rebind stored under [FOCUS_ADDRESS_BAR_ACTION] in the keymap file supersedes the default
     * and survives plugin reloads.
     */
    private val addressBarShortcuts =
        object : ShortcutActionProvider {
            override val providerId: String = pluginId

            override fun shortcuts(): List<PluginShortcutSpec> =
                listOf(
                    PluginShortcutSpec(
                        actionId = FOCUS_ADDRESS_BAR_ACTION,
                        displayName = "Focus Address Bar",
                        description = "Focus and select the browser tab's address bar",
                        defaultBinding = KeyChordSpec(key = "L", modifiers = setOf("Cmd")),
                    ),
                )

            override fun onAction(
                actionId: String,
                windowId: String?,
            ) {
                if (actionId != FOCUS_ADDRESS_BAR_ACTION) return
                // False when this window has no composed browser toolbar. Nothing to do — the
                // registry deliberately does not reach into another window to find one.
                AddressBarFocusRegistry.focusActiveIn(windowId)
            }
        }

    override fun register(context: PluginContext) {
        pluginContext = context

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(FluckBrowserTabType) { tabInfo, ctx ->
            FluckBrowserTabComponent(ctx, tabInfo, context)
        }

        // Contribute browser_get_url/navigate/run_js MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(FluckBrowserMcpToolProvider(pluginId, context.activeTabsProvider))

        // Cmd+L. The address bar belongs to this plugin, not the host, so the chord does too —
        // the host has no URL field to focus and every other keymap action it owns acts on a
        // BrowserHandle rather than on our toolbar.
        context.registerShortcutActionProvider(addressBarShortcuts)

        // Co-browse tab sharing: store context. The embedded server binds lazily on
        // the first share() call. Approval is surfaced BossTerm-style — the in-tab
        // ShareRequestToast banner + the Share window's PendingRequestsList — so no
        // host notification toast is posted (it duplicated those and looked off-style).
        BrowserShareManager.start(context)
    }

    override fun dispose() {
        // FIRST: let go of any context menu. DismissWatcher registers an AWTEventListener on the
        // HOST's Toolkit, and NativeContextMenu is a plugin-classloader singleton holding a host
        // Window. Unload with that listener still installed and the host keeps a strong reference
        // into unloaded plugin code - the classloader can never be collected, and every mouse
        // move and key press in the whole app is dispatched into it until the JVM exits.
        SwingContextMenu.hide()

        // Tear down the share server (stops any active capture) before unregistering.
        BrowserShareManager.shutdown()
        pluginContext?.unregisterShortcutActionProvider(addressBarShortcuts.providerId)
        pluginContext?.tabRegistry?.unregisterTabType(FluckBrowserTabType.typeId)
        pluginContext = null
    }

    companion object {
        /** Must be `plugin.<pluginId>.<name>`; the host rejects anything else. */
        const val FOCUS_ADDRESS_BAR_ACTION = "plugin.ai.rever.boss.plugin.dynamic.fluckbrowser.focus_address_bar"
    }
}
