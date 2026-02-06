package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language

/**
 * Fluck Browser tab type info (Dynamic Plugin)
 *
 * This tab type provides a full-featured embedded web browser
 * with URL navigation, zoom controls, downloads, and secret integration.
 */
object FluckBrowserTabType : TabTypeInfo {
    // Use same typeId as built-in for compatibility with existing FluckTabInfo
    override val typeId = TabTypeId("fluck")
    override val displayName = "Browser"
    override val icon = Icons.Outlined.Language
}
