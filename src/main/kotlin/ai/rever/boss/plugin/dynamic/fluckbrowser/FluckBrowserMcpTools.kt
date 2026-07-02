package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * MCP tools contributed by the Fluck Browser plugin: drive an open browser tab
 * (identified by its tab id from `tabs_list`) — read its URL, navigate it, and
 * evaluate JavaScript in it. Registered in [FluckBrowserDynamicPlugin.register];
 * removed automatically on disable/unload.
 */
internal class FluckBrowserMcpToolProvider(
    override val providerId: String,
    private val activeTabsProvider: ActiveTabsProvider?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "browser_get_url",
            description = "Get the current URL of a browser tab (tab id from tabs_list).",
            inputSchema = tabSchema(),
            handler = McpToolHandler { args ->
                val bi = integration(args) ?: return@McpToolHandler missingOrUnknownTab(args)
                val url = bi.getCurrentUrl()
                McpToolResult(url ?: "(no url)")
            },
        ),
        McpToolDefinition(
            name = "browser_navigate",
            description = "Navigate a browser tab to a URL (tab id from tabs_list).",
            inputSchema = """{"type":"object","properties":{"tab_id":{"type":"string","description":"Browser tab id."},"url":{"type":"string","description":"URL to load."}},"required":["tab_id","url"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val bi = integration(args) ?: return@McpToolHandler missingOrUnknownTab(args)
                val url = args.string("url")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: url", isError = true)
                bi.navigate(url)
                McpToolResult("Navigating tab to $url.")
            },
        ),
        McpToolDefinition(
            name = "browser_run_js",
            description = "Evaluate JavaScript in a browser tab and return the result (tab id from tabs_list).",
            inputSchema = """{"type":"object","properties":{"tab_id":{"type":"string","description":"Browser tab id."},"script":{"type":"string","description":"JavaScript to evaluate."}},"required":["tab_id","script"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val bi = integration(args) ?: return@McpToolHandler missingOrUnknownTab(args)
                val script = args.string("script")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: script", isError = true)
                val result = bi.executeJavaScript(script)
                McpToolResult(result?.toString() ?: "null")
            },
        ),
    )

    private fun integration(args: ai.rever.boss.plugin.api.McpToolArgs) =
        args.string("tab_id")?.let { activeTabsProvider?.getBrowserIntegration(it) }

    /** Distinguish a missing tab_id argument from an unknown/non-browser tab. */
    private fun missingOrUnknownTab(args: ai.rever.boss.plugin.api.McpToolArgs): McpToolResult =
        if (args.string("tab_id") == null) {
            McpToolResult("Missing required argument: tab_id", isError = true)
        } else {
            noBrowser()
        }

    private fun noBrowser(): McpToolResult =
        McpToolResult("No browser tab for that tab_id (or browser unavailable).", isError = true)

    private fun tabSchema(): String =
        """{"type":"object","properties":{"tab_id":{"type":"string","description":"Browser tab id from tabs_list."}},"required":["tab_id"]}"""
}
