package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.fluckbrowser.share.BrowserShareManager
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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

    // One INDEFINITE approval toast per pending share request, dismissed when it resolves.
    private val approvalToastIds = ConcurrentHashMap<String, String>()

    override fun register(context: PluginContext) {
        pluginContext = context

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(FluckBrowserTabType) { tabInfo, ctx ->
            FluckBrowserTabComponent(ctx, tabInfo, context)
        }

        // Co-browse tab sharing: store context + start surfacing approval toasts.
        // The embedded server itself binds lazily on the first share() call.
        BrowserShareManager.start(context)
        wireApprovalNotifications(context)
    }

    override fun dispose() {
        // Tear down the share server (stops any active capture) before unregistering.
        BrowserShareManager.shutdown()
        approvalToastIds.clear()
        pluginContext?.tabRegistry?.unregisterTabType(FluckBrowserTabType.typeId)
        pluginContext = null
    }

    /**
     * Mirror [BrowserShareManager.pendingRequests] into host toasts: one
     * INDEFINITE toast per pending viewer with a one-tap Approve action,
     * dismissed automatically when the request resolves. Collected on
     * [PluginContext.pluginScope], so plugin dispose cancels it.
     */
    private fun wireApprovalNotifications(context: PluginContext) {
        val notifications = context.notificationProvider ?: return
        context.pluginScope.launch {
            BrowserShareManager.pendingRequests.collect { requests ->
                val live = requests.map { it.id }.toSet()
                approvalToastIds.keys.filter { it !in live }.forEach { id ->
                    approvalToastIds.remove(id)?.let { notifications.dismiss(it) }
                }
                requests.filter { !approvalToastIds.containsKey(it.id) }.forEach { request ->
                    val verb = if (request.wantsControl) "control of" else "to view"
                    val toastId = notifications.showToast(
                        message = "${request.deviceName} requests $verb your shared browser",
                        type = NotificationType.WARNING,
                        duration = NotificationDuration.INDEFINITE,
                        title = "Browser tab sharing",
                        actionLabel = "Approve",
                        onAction = { BrowserShareManager.approveRequest(request.id) }
                    )
                    approvalToastIds[request.id] = toastId
                }
            }
        }
    }
}
