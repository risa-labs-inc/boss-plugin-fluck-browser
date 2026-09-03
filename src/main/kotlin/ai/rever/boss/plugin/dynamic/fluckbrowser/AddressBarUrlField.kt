package ai.rever.boss.plugin.dynamic.fluckbrowser

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The two rules the URL field's text obeys when something other than typing writes to it.
 *
 * Extracted from the composable so both can be tested, the way the visible-page-url and
 * suggestion-delete logic are (`VisiblePageUrlTest`, `SuggestionDeleteTest`): the interesting one
 * is [navigationMayRewrite], whose interaction with Cmd+L is a timing race no Compose test in
 * this repo could reach anyway.
 */
internal object AddressBarUrlField {
    /**
     * How long after a user edit the navigation listener keeps its hands off the field.
     *
     * A buffer for Tab completion: a navigation that lands mid-completion must not overwrite what
     * the user is assembling.
     */
    const val USER_EDIT_GRACE_MS = 300L

    /**
     * Whether a navigation callback may replace the field's contents with the page's URL.
     *
     * False while the user owns the field, which is what Cmd+L relies on. The focus callback sets
     * `isUserEditingUrl` BEFORE it selects the text, so on a still-loading or redirecting page a
     * navigation landing a beat later cannot rewrite `urlBarText` with a COLLAPSED selection while
     * the field keeps focus — which would leave the next keystroke appending to the URL instead of
     * replacing it, exactly what selecting it exists to prevent.
     *
     * @param msSinceUserEdit now minus the last user edit. Negative or absurd values (a clock
     *   step) simply read as "recent", which errs toward leaving the user's text alone.
     */
    fun navigationMayRewrite(
        isUserEditing: Boolean,
        msSinceUserEdit: Long,
    ): Boolean = !isUserEditing && msSinceUserEdit > USER_EDIT_GRACE_MS

    /**
     * [current] with its whole text selected, the way every browser's Cmd+L leaves the field.
     *
     * Keeps the text rather than building a fresh [TextFieldValue] from it: the composition and
     * everything else the value carries belong to the field the user is looking at.
     */
    fun selectAll(current: TextFieldValue): TextFieldValue = current.copy(selection = TextRange(0, current.text.length))

    /**
     * The field as it should read once the user abandons an edit with Escape: whatever the page
     * actually loaded, caret at the end.
     *
     * The paired half of releasing the claim, and the reason it matters is Cmd+L. Before Cmd+L
     * existed, "claimed" implied the user had TYPED, so the only way into the claimed state was
     * one the user could see. Cmd+L claims the field on a keystroke that types nothing, which
     * makes claim-then-Escape reachable in two keys - and while the claim stands,
     * [navigationMayRewrite] is false and the URL bar silently stops following navigations.
     */
    fun restoreTo(loadedUrl: String): TextFieldValue = TextFieldValue(loadedUrl, TextRange(loadedUrl.length))
}
