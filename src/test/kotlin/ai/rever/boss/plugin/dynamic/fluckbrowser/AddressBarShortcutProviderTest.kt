package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract the plugin's Cmd+L action keeps with the HOST, as distinct from
 * [AddressBarFocusRegistryTest], which pins who answers once the chord has arrived.
 *
 * Everything here is a rule the host enforces silently: a malformed action id is rejected at
 * registration, a default binding is consumed globally, and `dispatch` routes purely by id. Get
 * one wrong and the shortcut is simply gone, or worse, someone else's is.
 */
class AddressBarShortcutProviderTest {
    private val focused = mutableListOf<String>()

    @BeforeTest
    @AfterTest
    fun reset() {
        AddressBarFocusRegistry.clear()
        focused.clear()
    }

    private fun registerToolbar(
        tabId: String,
        windowId: String,
    ) = AddressBarFocusRegistry.register(tabId, windowId, panelActive = true) { focused.add(tabId) }

    @Test
    fun `the action id matches what the host requires of it`() {
        // Must be exactly "plugin.<pluginId>.<name>" or the host rejects the registration and the
        // shortcut is silently lost. The id is a const and the plugin id is a separate literal,
        // so this is what stops the two drifting. The host's keymap preset spells the same string
        // out by hand (KeymapPresets.FLUCK_FOCUS_ADDRESS_BAR_ACTION) because it does not compile
        // against this plugin, so a drift here also costs the binding.
        val plugin = FluckBrowserDynamicPlugin()

        assertEquals(
            "plugin.${plugin.pluginId}.focus_address_bar",
            FluckBrowserDynamicPlugin.FOCUS_ADDRESS_BAR_ACTION,
        )
        assertTrue(FluckBrowserDynamicPlugin.FOCUS_ADDRESS_BAR_ACTION.startsWith("plugin."))
    }

    @Test
    fun `the action ships with no default binding, deliberately`() {
        // A plugin default is GLOBAL in the host's v1 contract and is consumed whenever a
        // provider owns the action, so a Cmd+L default here would shadow the host's
        // EDITOR_GO_TO_LINE (also Cmd+L) and swallow it - the editor plugin opens Go To Line from
        // its own key handling, so the chord has to reach it.
        //
        // The chord therefore lives in the host's keymap presets, bound to this action id with
        // BROWSER context, which is the only layer where a context can be expressed. A host too
        // old to carry that entry leaves Cmd+L alone rather than breaking Go To Line, which is
        // what makes this safe to ship independently of the host.
        val spec = FluckBrowserDynamicPlugin().addressBarShortcuts.shortcuts().single()

        assertEquals(FluckBrowserDynamicPlugin.FOCUS_ADDRESS_BAR_ACTION, spec.actionId)
        assertNull(spec.defaultBinding, "a GLOBAL Cmd+L default would swallow the editor's Go To Line")
        assertEquals("Focus Address Bar", spec.displayName)
    }

    @Test
    fun `the provider id is the plugin id, as the host expects`() {
        val plugin = FluckBrowserDynamicPlugin()

        assertEquals(plugin.pluginId, plugin.addressBarShortcuts.providerId)
    }

    @Test
    fun `the provider ignores action ids that are not its own`() {
        // dispatch() routes by action id, but a provider that acted on anything handed to it
        // would move focus on an unrelated plugin's chord.
        registerToolbar("tab-1", "window-1")
        val provider = FluckBrowserDynamicPlugin().addressBarShortcuts

        provider.onAction("plugin.something.else", "window-1")

        assertTrue(focused.isEmpty(), "only this plugin's action should focus the address bar")
    }

    @Test
    fun `the provider focuses on its own action id`() {
        registerToolbar("tab-1", "window-1")
        val provider = FluckBrowserDynamicPlugin().addressBarShortcuts

        provider.onAction(FluckBrowserDynamicPlugin.FOCUS_ADDRESS_BAR_ACTION, "window-1")

        assertEquals(listOf("tab-1"), focused)
    }
}
