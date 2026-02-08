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
 * - Browser-specific properties (initialUrl, currentUrl)
 * - Navigation history for back/forward tracking
 *
 * This is a regular class (not data class) to provide explicit control over
 * equals() and copy() behavior, matching bundled FluckTabInfo patterns.
 *
 * @param id Unique identifier for this tab instance
 * @param title Display title for the tab (defaults to "New Tab")
 * @param icon Tab icon vector (defaults to Language icon)
 * @param tabIcon Tab icon wrapper for favicon support
 * @param initialUrl URL to load when the tab opens
 * @param currentUrl Current URL being viewed (may differ from initialUrl after navigation)
 * @param faviconCacheKey Cache key for persisted favicon
 * @param navigationHistory List of (title, url) pairs representing navigation history
 * @param historyIndex Current position in navigation history (-1 if empty)
 */
class FluckBrowserTabData(
    override val id: String,
    private var _title: String = "New Tab",
    override val icon: ImageVector = Icons.Outlined.Language,
    override val tabIcon: TabIcon? = null,
    val initialUrl: String = DEFAULT_URL,
    private var _currentUrl: String = initialUrl,
    val faviconCacheKey: String? = null,
    val navigationHistory: MutableList<Pair<String, String>> = mutableListOf(),
    var historyIndex: Int = -1
) : TabInfo {
    override val typeId: TabTypeId = FluckBrowserTabType.typeId
    override val title: String get() = _title
    val currentUrl: String get() = _currentUrl

    companion object {
        /** Maximum length for browser tab titles */
        const val MAX_TITLE_LENGTH = 64

        /** Default URL for new browser tabs */
        const val DEFAULT_URL = "https://www.risalabs.ai"
    }

    // Explicit equals() based on id AND content that affects display
    // This ensures Compose detects when tab content changes (title, URL, etc.)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is FluckBrowserTabData) return false

        return id == other.id &&
               _title == other._title &&
               _currentUrl == other._currentUrl &&
               icon == other.icon &&
               tabIcon == other.tabIcon &&
               faviconCacheKey == other.faviconCacheKey
    }

    // Keep hashCode based on ID only for HashMap performance
    override fun hashCode(): Int = id.hashCode()

    /**
     * Creates a copy of this tab data with optional overrides.
     * Navigation history is deep-copied to prevent shared reference issues.
     */
    fun copy(
        id: String = this.id,
        _title: String = this._title,
        icon: ImageVector = this.icon,
        tabIcon: TabIcon? = this.tabIcon,
        initialUrl: String = this.initialUrl,
        _currentUrl: String = this._currentUrl,
        faviconCacheKey: String? = this.faviconCacheKey,
        navigationHistory: MutableList<Pair<String, String>>? = null,
        historyIndex: Int = this.historyIndex
    ): FluckBrowserTabData {
        val newTab = FluckBrowserTabData(
            id = id,
            _title = _title,
            icon = icon,
            tabIcon = tabIcon,
            initialUrl = initialUrl,
            _currentUrl = _currentUrl,
            faviconCacheKey = faviconCacheKey
        )

        // Deep copy navigation history to prevent shared reference issues
        if (navigationHistory != null) {
            newTab.navigationHistory.addAll(navigationHistory)
        } else {
            newTab.navigationHistory.addAll(this.navigationHistory)
        }
        newTab.historyIndex = historyIndex

        return newTab
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
        return copy(_title = truncatedTitle)
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

    /**
     * Returns a copy with updated navigation state.
     * Adds a new entry to navigation history and updates current URL.
     * Truncates forward history if navigating from a non-end position.
     *
     * @param title Page title
     * @param url Page URL
     */
    fun updateNavigation(title: String, url: String): FluckBrowserTabData {
        // Calculate new history and index WITHOUT mutating
        val newHistory = navigationHistory.toMutableList()
        var newIndex = historyIndex

        // Truncate forward history if needed
        if (newIndex < newHistory.size - 1) {
            while (newHistory.size > newIndex + 1) {
                newHistory.removeAt(newHistory.size - 1)
            }
        }

        // Add new entry if not duplicate
        if (newHistory.isEmpty() || newHistory.lastOrNull()?.second != url) {
            newHistory.add(Pair(title, url))
            newIndex = newHistory.size - 1
        }

        return copy(
            _currentUrl = url,
            navigationHistory = newHistory,
            historyIndex = newIndex
        )
    }

    /**
     * Navigate back in history.
     * Updates historyIndex and currentUrl to the previous entry.
     */
    fun navigateBack(): FluckBrowserTabData {
        if (historyIndex > 0) {
            val newIndex = historyIndex - 1
            val newUrl = navigationHistory[newIndex].second
            return copy(
                _currentUrl = newUrl,
                historyIndex = newIndex
            )
        }
        return this
    }

    /**
     * Navigate forward in history.
     * Updates historyIndex and currentUrl to the next entry.
     */
    fun navigateForward(): FluckBrowserTabData {
        if (historyIndex < navigationHistory.size - 1) {
            val newIndex = historyIndex + 1
            val newUrl = navigationHistory[newIndex].second
            return copy(
                _currentUrl = newUrl,
                historyIndex = newIndex
            )
        }
        return this
    }

    /**
     * Check if back navigation is possible based on history state.
     */
    fun canNavigateBack(): Boolean = historyIndex > 0

    /**
     * Check if forward navigation is possible based on history state.
     */
    fun canNavigateForward(): Boolean = historyIndex < navigationHistory.size - 1
}
