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
    fun `the action id has the shape the host requires`() {
        // Must be exactly "plugin.<pluginId>.<name>" or the host rejects the registration and the
        // shortcut is silently lost. Drift between the id and the plugin id is now impossible -
        // FOCUS_ADDRESS_BAR_ACTION is a const template over PLUGIN_ID - so this asserts the
        // SHAPE, which the compiler cannot. The host's keymap preset spells the same string out
        // by hand (KeymapPresets.FLUCK_FOCUS_ADDRESS_BAR_ACTION) because it does not compile
        // against this plugin; that duplication is the one a test still has to carry.
        val plugin = FluckBrowserDynamicPlugin()

        assertEquals(
            "plugin.${plugin.pluginId}.focus_address_bar",
            FluckBrowserDynamicPlugin.FOCUS_ADDRESS_BAR_ACTION,
        )
        assertEquals(FluckBrowserDynamicPlugin.PLUGIN_ID, plugin.pluginId)
    }

    @Test
    fun `the action ships with no default binding, deliberately`() {
        // A GLOBAL Cmd+L default would swallow the editor's Go To Line; the chord lives in the
        // host's keymap presets instead. The reasoning is spelled out once, on
        // FluckBrowserDynamicPlugin.addressBarShortcuts - this pins the outcome.
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
