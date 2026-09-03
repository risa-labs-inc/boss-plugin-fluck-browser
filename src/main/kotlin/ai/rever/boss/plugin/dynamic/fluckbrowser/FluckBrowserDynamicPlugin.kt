package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.DynamicPlugin
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
     * "Focus Address Bar" - the handler behind Cmd+L, focusing the address bar of the browser tab
     * in front of the user.
     *
     * Contributed rather than built into the host keymap because the field lives here: the host
     * has no URL field, and every keymap action it owns acts on a BrowserHandle instead.
     *
     * **Registered with no defaultBinding on purpose.** A plugin default is GLOBAL in the host's
     * v1 contract and is consumed whenever a provider owns the action, so a Cmd+L default would
     * shadow the host's EDITOR_GO_TO_LINE (also Cmd+L) and swallow it - the editor plugin opens
     * Go To Line from its own key handling, so the chord has to reach it. The host's keymap
     * presets therefore carry Cmd+L for this action id with `context = BROWSER`, which is the
     * only place a context can be expressed, and hosts too old to have that entry simply leave
     * Cmd+L alone rather than breaking Go To Line. Bumping `minBossVersion` was the other option
     * and is the wrong lever here - see the note in PageEventChannel on what that did in 1.2.22.
     *
     * The host unregisters this automatically on disable/unload, after which the chord goes
     * inert; a user rebind under [FOCUS_ADDRESS_BAR_ACTION] persists across plugin reloads.
     *
     * **Why this is not a `Key.L` branch in `FluckBrowserTabContent`'s own `onPreviewKeyEvent`,
     * next to the Cmd+R / Cmd+0 / zoom chords it already serves.** That would be far less code
     * and would answer "whose address bar" by construction. It is rejected for one reason:
     * a locally handled chord is INVISIBLE AND UNCHANGEABLE. Going through the host's registry is
     * what puts "Focus Address Bar" in Settings → Shortcuts, lets a user rebind it to something
     * their keyboard can actually produce, and keeps that rebind across plugin reloads — none of
     * which a `when (keyEvent.key)` branch can offer. It is also the layer where Cmd+L can be
     * CONTEXT-scoped so the editor's Go To Line keeps the same chord, which is the whole reason
     * the binding lives in the host presets. The four locally handled chords are the older
     * pattern, not the target; do not "simplify" this into one.
     */
    internal val addressBarShortcuts =
        object : ShortcutActionProvider {
            override val providerId: String = pluginId

            override fun shortcuts(): List<PluginShortcutSpec> =
                listOf(
                    PluginShortcutSpec(
                        actionId = FOCUS_ADDRESS_BAR_ACTION,
                        displayName = "Focus Address Bar",
                        description = "Focus and select the browser tab's address bar",
                        // No default: the host preset binds Cmd+L to this id with BROWSER
                        // context. See the class KDoc above.
                        defaultBinding = null,
                    ),
                )

            /**
             * Called on the UI thread. Not an assumption: the host documents it on
             * `PluginShortcutRegistryImpl.dispatch` ("Called on the UI thread by the
             * interceptor"), and the interceptor is an AWT `KeyEventDispatcher`, so the caller is
             * the EDT. That is what makes it safe to touch a FocusRequester and Compose state
             * from here without posting to a scope.
             *
             * **When the chord reaches here at all**, given the host's interceptor is an AWT
             * dispatcher and the page is a native Chromium surface:
             *  - macOS, either rendering mode: yes, including while focus is inside page content.
             *    The host's `FluckEngine.ownsChordsNatively` is macOS-exempt precisely because
             *    the chord reaches AWT there, and its native key callback declines to serve
             *    chords for that reason.
             *  - OFF_SCREEN anywhere: yes - the keystroke arrives through AWT and Compose.
             *  - Windows/Linux in HARDWARE_ACCELERATED, focus inside the page: NO. Chromium's
             *    native child window consumes the key before the JVM sees it, which is the whole
             *    reason `ownsChordsNatively` exists. That affects every keymap chord equally, not
             *    just this one; the host serves the handful it owns (reload, zoom, N/T/W,
             *    Shift+F/S, find) from its own `PressKeyCallback`, and that callback dispatches
             *    named host actions rather than plugin action ids. Closing it for THIS action is a
             *    host-side change, in that callback - it cannot be done from the plugin.
             *
             * Taking Compose focus is enough to redirect typing: the page is JxBrowser's Compose
             * `BrowserViewState` inside the window's single ComposePanel, not a sibling AWT
             * component, so there is no separate AWT focus owner still holding the keyboard.
             */
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

        // Co-browse tab sharing: store context. The embedded server binds lazily on
        // the first share() call. Approval is surfaced BossTerm-style — the in-tab
        // ShareRequestToast banner + the Share window's PendingRequestsList — so no
        // host notification toast is posted (it duplicated those and looked off-style).
        BrowserShareManager.start(context)

        // Cmd+L, LAST and in a runCatching, both deliberately. The address bar belongs to this
        // plugin, not the host, so the chord does too — the host has no URL field to focus and
        // every other keymap action it owns acts on a BrowserHandle rather than on our toolbar.
        // But it is a convenience, and this is a systemPlugin that IS the browser tab: the host
        // rejects a malformed action id and can reject a duplicate provider id on a reload race,
        // and an exception escaping register() here would cost the tab type and the share server
        // for a keyboard shortcut. Ordering alone would leave dispose() to unwind a half-built
        // plugin, so it is both.
        //
        // Safe at the declared api floor: registerShortcutActionProvider /
        // unregisterShortcutActionProvider / ShortcutActionProvider / PluginShortcutSpec all
        // landed in api 1.0.62, LocalWindowIdProvider in 1.0.16 and LocalIsPanelActive in 1.0.38
        // — all at or below plugin.json's minApiVersion 1.0.73, so no host that loads this jar
        // can be missing them. That check is the point of PageEventChannelSourceTest; see its
        // floor rationale for why getting it wrong costs the whole plugin rather than the chord.
        // Existing is not the same as not throwing, which is what the runCatching is for.
        runCatching { context.registerShortcutActionProvider(addressBarShortcuts) }
            .onFailure { println("[FluckBrowser] Cmd+L unavailable: ${it.message}") }
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
        // Guarded for the same reason the registration is: everything below this line matters
        // more than the shortcut, and an unload that stops here leaves the tab type registered
        // against dead plugin code.
        runCatching { pluginContext?.unregisterShortcutActionProvider(addressBarShortcuts.providerId) }
            .onFailure { println("[FluckBrowser] could not unregister the Cmd+L provider: ${it.message}") }
        // Symmetric with the registrations the tab compositions make. Nothing can invoke them
        // once the provider is gone, so this is hygiene rather than a fix, but it stops entries
        // holding torn-down compositions across a disable/re-enable on the same classloader.
        AddressBarFocusRegistry.clear()
        pluginContext?.tabRegistry?.unregisterTabType(FluckBrowserTabType.typeId)
        pluginContext = null
    }

    companion object {
        /** Must be `plugin.<pluginId>.<name>`; the host rejects anything else. */
        const val FOCUS_ADDRESS_BAR_ACTION = "plugin.ai.rever.boss.plugin.dynamic.fluckbrowser.focus_address_bar"
    }
}
