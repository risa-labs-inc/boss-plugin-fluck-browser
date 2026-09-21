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
        McpToolDefinition(
            name = "browser_keep_alive",
            description =
                "Declare that a Fluck workspace is driving a browser tab, so it is not hibernated. " +
                    "Call on every routed tool call and with hold=true while a routed handle is held; " +
                    "call with release=true on hand-off. A touch lasts 15 minutes.",
            inputSchema =
                """{"type":"object","properties":{"tab_id":{"type":"string","description":"Browser tab id from tabs_list."},""" +
                    """"hold":{"type":"boolean","description":"Keep the tab awake until released."},""" +
                    """"release":{"type":"boolean","description":"Hand the tab back to the idle timer."}},"required":["tab_id"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val tabId = args.string("tab_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: tab_id", isError = true)
                // Deliberately NOT routed through `integration`: a keep-alive must still register
                // for a tab whose handle is momentarily absent - that is the case it exists for.
                when {
                    args.boolean("release") == true -> {
                        AgentKeepAlive.release(tabId)
                        McpToolResult("Released tab $tabId to the idle timer.")
                    }
                    args.boolean("hold") == true -> {
                        AgentKeepAlive.hold(tabId)
                        McpToolResult("Holding tab $tabId awake until released.")
                    }
                    else -> {
                        AgentKeepAlive.touch(tabId)
                        McpToolResult(
                            "Tab $tabId kept awake for the next " +
                                "${AgentKeepAlive.KEEP_ALIVE_WINDOW_MS / 60_000} minutes.",
                        )
                    }
                }
            },
        ),
    )

    /**
     * Every routed tool call is also a keep-alive touch - see [AgentKeepAlive].
     *
     * Here rather than in each handler so a tool added later cannot forget it: a tab the agent is
     * driving must not be hibernated out from under the next call.
     */
    private fun integration(args: ai.rever.boss.plugin.api.McpToolArgs) =
        args.string("tab_id")?.let { tabId ->
            AgentKeepAlive.touch(tabId)
            activeTabsProvider?.getBrowserIntegration(tabId)
        }

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
