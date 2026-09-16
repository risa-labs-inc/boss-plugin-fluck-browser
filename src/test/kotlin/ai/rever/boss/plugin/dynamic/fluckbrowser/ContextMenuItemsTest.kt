package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserContextMenuInfo
import ai.rever.boss.plugin.browser.FormFieldInfo
import ai.rever.boss.plugin.browser.FormFieldType
import ai.rever.boss.plugin.api.SecretEntryData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The menu is built from whatever the host reports about the click target, so the
 * item set is the contract worth pinning: each target shape must offer its own
 * actions, and must not offer another shape's.
 */
class ContextMenuItemsTest {

    private fun labels(
        info: BrowserContextMenuInfo?,
        canGoBack: Boolean = false,
        canGoForward: Boolean = false,
        isBookmarked: Boolean = false,
        secrets: List<SecretEntryData> = emptyList(),
    ): List<String> =
        buildContextMenuItems(
            info = info,
            browserHandle = null,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onNavigate = {},
            onOpenInNewTab = {},
            isBookmarked = isBookmarked,
            secrets = secrets,
        ).filterNot { it.isDivider }.map { it.text }

    private val loginField =
        FormFieldInfo(
            fieldType = FormFieldType.USERNAME,
            fieldName = "username",
            fieldId = "login",
            fieldPlaceholder = "Email",
            fieldValue = "",
            parentFormAction = null,
            inputType = "email",
            autocomplete = "username",
        )

    private fun secret(website: String, username: String) =
        SecretEntryData(
            id = "$website|$username",
            website = website,
            username = username,
            password = "password",
            createdAt = "",
            updatedAt = "",
        )

    @Test
    fun `plain page offers page actions and no link or image actions`() {
        val items = labels(BrowserContextMenuInfo(pageUrl = "https://example.com/"))

        assertTrue(items.contains("Reload"))
        assertTrue(items.contains("Copy Page URL"))
        assertTrue(items.contains("Add Bookmark"))
        assertTrue(items.contains("Inspect Element"))
        assertFalse(items.any { it.contains("Link") })
        assertFalse(items.any { it.contains("Image") })
    }

    @Test
    fun `history entries appear only when there is history to move through`() {
        assertFalse(labels(BrowserContextMenuInfo()).contains("Back"))
        assertFalse(labels(BrowserContextMenuInfo()).contains("Forward"))

        val both = labels(BrowserContextMenuInfo(), canGoBack = true, canGoForward = true)
        assertTrue(both.contains("Back"))
        assertTrue(both.contains("Forward"))
    }

    @Test
    fun `right-clicking a link offers the link actions alongside the page URL`() {
        val items = labels(
            BrowserContextMenuInfo(
                linkUrl = "https://example.com/target",
                pageUrl = "https://example.com/"
            )
        )

        assertTrue(items.contains("Open Link"))
        assertTrue(items.contains("Open Link in New Tab"))
        assertTrue(items.contains("Copy Link URL"))
        assertTrue(items.contains("Copy Page URL"))
    }

    @Test
    fun `right-clicking an image offers image actions`() {
        val items = labels(
            BrowserContextMenuInfo(
                hasImage = true,
                imageUrl = "https://example.com/cat.png",
                pageUrl = "https://example.com/"
            )
        )

        assertTrue(items.contains("Open Image in New Tab"))
        assertTrue(items.contains("Copy Image URL"))
    }

    @Test
    fun `a linked image offers both link and image actions`() {
        val items = labels(
            BrowserContextMenuInfo(
                linkUrl = "https://example.com/target",
                hasImage = true,
                imageUrl = "https://example.com/cat.png"
            )
        )

        assertTrue(items.contains("Open Link in New Tab"))
        assertTrue(items.contains("Open Image in New Tab"))
    }

    @Test
    fun `an image with no resolved source offers no image actions`() {
        val items = labels(BrowserContextMenuInfo(hasImage = true, imageUrl = null))

        assertFalse(items.any { it.contains("Image") })
    }

    @Test
    fun `selected text offers copy and search`() {
        val items = labels(BrowserContextMenuInfo(selectedText = "hello world"))

        assertTrue(items.contains("Copy"))
        assertTrue(items.contains("Search with Google"))
    }

    @Test
    fun `an editable field offers the full set of edit operations`() {
        // canGoBack/Forward are true so the absent-Back assertion discriminates: it proves
        // the field branch was taken, not merely that there was no history to offer.
        val items = labels(
            BrowserContextMenuInfo(isEditable = true),
            canGoBack = true,
            canGoForward = true
        )

        assertEquals(listOf("Cut", "Copy", "Paste", "Select All"), items.take(4))
        // The page-level actions belong to the page menu, not the field menu.
        assertFalse(items.contains("Back"))
        assertFalse(items.contains("Forward"))
        assertFalse(items.contains("Add Bookmark"))
    }

    @Test
    fun `matching credential rows replace the generic fill action`() {
        val items =
            labels(
                BrowserContextMenuInfo(
                    isEditable = true,
                    pageUrl = "https://accounts.example.com/login",
                    formFieldInfo = loginField,
                ),
                secrets = listOf(secret("example.com", "me@example.com")),
            )

        assertTrue(items.contains("Example.com (me@example.com)"))
        assertFalse(items.any { it.startsWith("🔑 Fill Credential") })
    }

    @Test
    fun `generic fill action opens the picker when the site has no match`() {
        var pickerOpened = false
        val items =
            buildContextMenuItems(
                info = BrowserContextMenuInfo(
                    isEditable = true,
                    pageUrl = "https://example.com/login",
                    formFieldInfo = loginField,
                ),
                browserHandle = null,
                canGoBack = false,
                canGoForward = false,
                onNavigate = {},
                onOpenInNewTab = {},
                secrets = listOf(secret("elsewhere.test", "other")),
                onShowAllSecrets = { pickerOpened = true },
            ).first { it.text == "🔑 Fill Credential..." }

        items.onClick()
        assertTrue(pickerOpened)
    }

    @Test
    fun `a non-web link can be copied but not opened`() {
        listOf("javascript:alert(1)", "data:text/html,hi", "file:///etc/passwd").forEach { href ->
            val items = labels(BrowserContextMenuInfo(linkUrl = href))

            assertFalse(items.contains("Open Link"), "should not offer to navigate to $href")
            assertFalse(items.contains("Open Link in New Tab"), "should not open $href in a tab")
            assertTrue(items.contains("Copy Link URL"), "copying $href is inert and stays")
            assertTrue(items.contains("Copy Page URL"), "the page is still copyable on $href")
        }
    }

    @Test
    fun `a non-web image can be copied but not opened`() {
        val items = labels(
            BrowserContextMenuInfo(hasImage = true, imageUrl = "data:image/png;base64,AAAA")
        )

        assertFalse(items.contains("Open Image in New Tab"))
        assertTrue(items.contains("Copy Image URL"))
    }

    @Test
    fun `bookmark entry reflects whether the page is already bookmarked`() {
        assertTrue(labels(BrowserContextMenuInfo(), isBookmarked = true).contains("Remove Bookmark"))
        assertTrue(labels(BrowserContextMenuInfo(), isBookmarked = false).contains("Add Bookmark"))
    }

    @Test
    fun `no click information still yields a usable page menu`() {
        val items = labels(null)

        assertTrue(items.contains("Reload"))
        assertTrue(items.contains("Inspect Element"))
    }
}
