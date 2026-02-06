package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language

/**
 * Fluck Browser panel info (Dynamic Plugin)
 *
 * This panel provides a full-featured embedded web browser
 * with URL navigation, downloads, and secret integration.
 */
object FluckBrowserInfo : PanelInfo {
    override val id = PanelId("fluck-browser", 25)
    override val displayName = "Browser"
    override val icon = Icons.Outlined.Language
    override val defaultSlotPosition = left.bottom
}
