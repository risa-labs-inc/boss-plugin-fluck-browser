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
     * How long an UNTYPED claim survives before a navigation may write through it.
     *
     * The claim has no other expiry. Enter, picking a suggestion, and Escape all release it, and
     * so does losing focus - but only if a focus-changed event actually arrives, which a click
     * into the native page surface may not produce on Windows/Linux HARDWARE_ACCELERATED. Cmd+L
     * takes the claim on a keystroke that types nothing, so "claim it, then click the page"
     * would otherwise stop the URL bar following navigations for the life of the tab.
     *
     * Bounded rather than released outright, and gated on nothing having been TYPED UNDER THE
     * CURRENT CLAIM, because those are the two things that keep it from undoing the other two
     * rules here: Cmd+L's selection needs protecting for seconds, not a minute, and text typed
     * under a standing claim is not discarded by this rule however long it has sat there.
     *
     * "Under the current claim" is the exact scope, and it is narrower than "the user typed it":
     *  - `onFocusLost`'s delayed release ends the claim, after which a navigation past
     *    [USER_EDIT_GRACE_MS] overwrites the field like it always did;
     *  - Cmd+L over an existing draft starts a FRESH claim, so that draft becomes staleable. That
     *    is deliberate: Cmd+L selects the whole field, which is the user saying they are about to
     *    replace it, so treating the text as precious would be reading the gesture backwards.
     *
     * Note also that this branch does not clear `isUserEditingUrl` - it only stops consulting it.
     * "Claimed" and "protected" therefore diverge permanently once a claim goes stale, until the
     * next keystroke re-arms protection through [USER_EDIT_GRACE_MS].
     *
     * **A TYPED claim is still unbounded, and this rule does not close that.** The same freeze is
     * reachable without Cmd+L: type into the bar on Windows/Linux HARDWARE_ACCELERATED, click
     * into the page, and if no focus-changed event arrives - the very reason this bound exists -
     * `onFocusLost` never runs, `typedSinceClaim` stays true, and the URL bar stops following
     * navigations for the life of the tab. That path predates Cmd+L, which is why it is not
     * fixed here rather than why it does not matter: closing it means either a second, much
     * longer bound for typed claims (with the data-loss window that implies for a draft someone
     * was interrupted mid-way through) or releasing on a navigation the user demonstrably did
     * not start. Both are behaviour changes for users who never press Cmd+L, and belong with the
     * claim lifecycle rather than with the chord that exposed them.
     */
    const val UNTYPED_CLAIM_STALE_MS = 60_000L

    /**
     * Whether a navigation callback may replace the field's contents with the page's URL.
     *
     * False while the user owns the field, which is what Cmd+L relies on. The focus callback sets
     * `isUserEditingUrl` BEFORE it selects the text, so on a still-loading or redirecting page a
     * navigation landing a beat later cannot rewrite `urlBarText` with a COLLAPSED selection while
     * the field keeps focus — which would leave the next keystroke appending to the URL instead of
     * replacing it, exactly what selecting it exists to prevent.
     *
     * The one way through a standing claim is [UNTYPED_CLAIM_STALE_MS]: an old claim over text the
     * user never changed is an abandoned one, not an active edit.
     *
     * @param msSinceUserEdit now minus the last user edit. Negative or absurd values (a clock
     *   step) simply read as "recent", which errs toward leaving the user's text alone.
     * @param typedSinceClaim whether anything has been typed since the current claim was taken.
     *   Tracked by the caller rather than inferred from the field's contents: "the field still
     *   reads as the loaded URL" looks like the same question and is not, because the bar
     *   legitimately shows something else on a home tab (`about:blank` over an empty
     *   `loadedUrl`) and after an abandoned draft outlives the claim released with it - and in
     *   exactly those states an inferred version would never let the bound below fire.
     */
    fun navigationMayRewrite(
        isUserEditing: Boolean,
        msSinceUserEdit: Long,
        typedSinceClaim: Boolean,
    ): Boolean =
        when {
            // Nothing typed and nothing touched in a minute: the claim is stale, not active.
            !typedSinceClaim && msSinceUserEdit > UNTYPED_CLAIM_STALE_MS -> true
            isUserEditing -> false
            else -> msSinceUserEdit > USER_EDIT_GRACE_MS
        }

    /**
     * Whether a navigation rewrite should KEEP the field's selection rather than collapse it.
     *
     * True in exactly one case: the staleness branch of [navigationMayRewrite] is what let the
     * write through while the field is still claimed. Without this, an abandoned Cmd+L that goes
     * stale gets the page URL written in with a collapsed caret while the field may still hold
     * focus - so the user comes back, types, and appends to the URL instead of replacing it.
     * That is the original selection bug, deferred by [UNTYPED_CLAIM_STALE_MS] rather than fixed.
     *
     * Deliberately narrow: an unclaimed field has no selection worth preserving, and a rewrite
     * allowed by [USER_EDIT_GRACE_MS] is the ordinary "nobody is editing" path, where a caret at
     * the end is what every browser does.
     */
    fun keepSelectionOnRewrite(
        isUserEditing: Boolean,
        msSinceUserEdit: Long,
        typedSinceClaim: Boolean,
    ): Boolean = isUserEditing && !typedSinceClaim && msSinceUserEdit > UNTYPED_CLAIM_STALE_MS

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
     *
     * Callers guard on there being something to revert to: `loadedUrl` is deliberately blank on
     * a home tab, and blanking the field is not reverting it.
     */
    fun restoreTo(loadedUrl: String): TextFieldValue = TextFieldValue(loadedUrl, TextRange(loadedUrl.length))

    /**
     * Whether Escape should rewrite the field, as opposed to only releasing the claim.
     *
     * The claim is released either way - that is what closes the stuck-claim freeze - so this
     * decides the text alone, and each term rules out a different way of making things worse:
     *  - [isUserEditing]: the submit path writes the resolved URL into the bar immediately while
     *    `loadedUrl` waits for `NavigationFinished`, so mid-navigation the text differs from it
     *    with nobody editing. Reverting there would put the PREVIOUS page's URL back until the
     *    load commits, then jump forward again.
     *  - [loadedUrl] non-blank: it is deliberately "" on a home tab (about:blank fires no
     *    navigation events, so home is the one surface that has to say "nothing loaded" itself),
     *    and blanking the field is not reverting it.
     *  - text differing: a field that already reads as the loaded URL has nothing to revert, and
     *    rewriting it would collapse Cmd+L's selection and drop the caret for no reason.
     */
    fun shouldRestore(
        isUserEditing: Boolean,
        loadedUrl: String,
        currentText: String,
    ): Boolean = isUserEditing && loadedUrl.isNotBlank() && currentText != loadedUrl
}
