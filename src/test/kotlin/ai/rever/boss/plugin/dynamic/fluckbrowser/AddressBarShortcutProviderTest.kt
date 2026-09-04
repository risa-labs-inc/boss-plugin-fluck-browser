package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `the action id matches the manifest's plugin id`() {
        // The host rejects an action id that is not "plugin.<the manifest's pluginId>.<name>",
        // and the shortcut then vanishes with no error anyone sees.
        //
        // This reads the MANIFEST, because that is the only drift still reachable. Comparing
        // FOCUS_ADDRESS_BAR_ACTION against PLUGIN_ID would be comparing an expression with
        // itself: the id is a const template over the constant, so the compiler already settles
        // it. plugin.json's pluginId is hand-written and nothing connects the two.
        val root =
            generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .firstOrNull { java.io.File(it, "build.gradle.kts").isFile && java.io.File(it, "src/main/kotlin").isDirectory }
        assertNotNull(root, "could not locate the plugin root")
        val manifest = java.io.File(root, "src/main/resources/META-INF/boss-plugin/plugin.json").readText()
        val manifestId =
            Regex(""""pluginId"\s*:\s*"([^"]+)""").find(manifest)?.groupValues?.get(1)

        assertEquals(FluckBrowserDynamicPlugin.PLUGIN_ID, manifestId, "the manifest and PLUGIN_ID have drifted")
        assertEquals(
            "plugin.$manifestId.focus_address_bar",
            FluckBrowserDynamicPlugin.FOCUS_ADDRESS_BAR_ACTION,
        )
        // The host's keymap preset spells the same string out by hand
        // (KeymapPresets.FLUCK_FOCUS_ADDRESS_BAR_ACTION) because it does not compile against this
        // plugin. That duplication is across repos and no test on either side can close it.
        assertEquals(
            "plugin.ai.rever.boss.plugin.dynamic.fluckbrowser.focus_address_bar",
            FluckBrowserDynamicPlugin.FOCUS_ADDRESS_BAR_ACTION,
            "the host preset pins this literal; changing it here silently unbinds Cmd+L",
        )
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
