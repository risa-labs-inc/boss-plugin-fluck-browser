package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tab data for Fluck Browser tabs.
 *
 * Contains configuration for a browser tab instance including:
 * - Standard tab properties (id, title, icon)
 * - Browser-specific properties (initialUrl)
 *
 * @param id Unique identifier for this tab instance
 * @param title Display title for the tab (defaults to "New Tab")
 * @param icon Tab icon vector (defaults to Language icon)
 * @param tabIcon Tab icon wrapper for favicon support
 * @param initialUrl URL to load when the tab opens
 */
data class FluckBrowserTabData(
    override val id: String,
    override val title: String = "New Tab",
    override val icon: ImageVector = Icons.Outlined.Language,
    override val tabIcon: TabIcon? = null,
    val initialUrl: String = DEFAULT_URL,
    val faviconCacheKey: String? = null
) : TabInfo {
    override val typeId: TabTypeId = FluckBrowserTabType.typeId

    companion object {
        /** Maximum length for browser tab titles */
        const val MAX_TITLE_LENGTH = 64

        /** Default URL for new browser tabs */
        const val DEFAULT_URL = "https://www.risalabs.ai"
    }

    /**
     * Returns a copy of this tab data with an updated title.
     * Used when the browser page title changes.
     * Title is truncated to [MAX_TITLE_LENGTH] characters.
     */
    fun updateTitle(newTitle: String): FluckBrowserTabData {
        val truncatedTitle = if (newTitle.length > MAX_TITLE_LENGTH) {
            newTitle.take(MAX_TITLE_LENGTH)
        } else {
            newTitle
        }
        return copy(title = truncatedTitle)
    }

    /**
     * Returns a copy of this tab data with an updated favicon cache key.
     * Used when the browser page favicon changes and is cached.
     *
     * @param cacheKey Cache key for the favicon, or null to use default icon
     */
    fun updateFaviconCacheKey(cacheKey: String?): FluckBrowserTabData {
        return copy(faviconCacheKey = cacheKey)
    }
}
