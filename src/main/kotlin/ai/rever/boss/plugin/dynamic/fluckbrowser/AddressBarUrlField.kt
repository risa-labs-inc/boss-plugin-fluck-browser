package ai.rever.boss.plugin.dynamic.fluckbrowser

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The two rules the URL field's text obeys when something other than typing writes to it.
 *
 * Extracted from the composable so both can be tested, the way the visible-page-url and
 * suggestion-delete logic are (`VisiblePageUrlTest`, `SuggestionDeleteTest`): the interesting one
 * is [navigationWrite], whose interaction with Cmd+L is a timing race no Compose test in
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
     * Whether anything has been TYPED under the current claim, after a field change.
     *
     * The distinction this exists for: `BasicTextField`'s `TextFieldValue` overload calls
     * `onValueChange` whenever the VALUE changes, which includes selection-only changes - a caret
     * move, Home/End, click-to-place-caret, shift-selection. Setting the flag unconditionally
     * there made it mean "the value changed", and the staleness bound in [navigationWrite] is
     * gated on it, so two keystrokes defeated the bound entirely: Cmd+L (claim, nothing typed),
     * Left arrow (selection collapses, flag set), then a click into the page on a platform where
     * no focus-changed event arrives - and the URL bar stops following navigations for the life
     * of the tab.
     *
     * That is distinct from the typed-claim freeze [UNTYPED_CLAIM_STALE_MS] deliberately does not
     * close. This path discards nothing: the user pressed Cmd+L and moved the caret.
     *
     * Sticky once set, because the claim is what resets it - see the Cmd+L focus callback.
     */
    fun typedUnderClaim(
        alreadyTyped: Boolean,
        previous: String,
        next: String,
    ): Boolean = alreadyTyped || textChanged(previous, next)

    /**
     * Whether a field change altered the TEXT, as opposed to only the selection.
     *
     * Trivial, and a named function on purpose: it takes [previous] as a parameter, so a caller
     * physically cannot ask the question after it has already written the new value over the old
     * one. That is not hypothetical - the first version of the suggestion gate read
     * `newValue.text != urlBarText.text` two statements AFTER `urlBarText = newValue`, so it was
     * always false and the history dropdown and inline autocomplete never appeared at all.
     *
     * Both callers ask the same question about the same pair, so they ask it here.
     */
    fun textChanged(
        previous: String,
        next: String,
    ): Boolean = previous != next

    /**
     * Whether a field change should touch the suggestion dropdown at all.
     *
     * The field's `onValueChange` fires for selection-only changes too, and the three outcomes
     * are genuinely different:
     *  - **text changed** - recompute or clear, as before. The only case that can change what the
     *    suggestions ARE.
     *  - **text unchanged, selection not collapsed** (Cmd+A, shift-arrow, drag-select) - clear,
     *    as before. Selecting the whole field is not a query, and leaving a stale inline
     *    completion standing changes what Enter does: it would take the
     *    `autocompleteSuggestion != null` branch and navigate to a suggestion computed for text
     *    the user has just selected over, instead of resolving what they typed.
     *  - **text unchanged, selection collapsed** (a caret move) - leave everything ALONE. This is
     *    the only new case, and the one worth having: it skips a `getSuggestions()` call per
     *    arrow key, and it stops Cmd+L then Left from re-opening the dropdown, which would spend
     *    the next Escape dismissing it rather than releasing the claim.
     *
     * Gating on [textChanged] alone was wrong, and the reasoning that justified it was too: it
     * assumed an arrow key reached the clearing branch, when a collapsed caret move actually took
     * the recompute branch and simply recomputed the same answer.
     *
     * Takes the two strings rather than a pre-computed `textChanged` so this is ONE call at the
     * call site. The version that took a boolean invited passing [textChanged] on its own, which
     * is precisely the bug above.
     */
    fun suggestionsNeedUpdate(
        previous: String,
        next: String,
        selectionCollapsed: Boolean,
    ): Boolean = textChanged(previous, next) || !selectionCollapsed

    /** What a navigation callback should do to the URL field. */
    sealed interface NavigationWrite {
        /** Leave the field alone: someone is editing it. */
        data object Skip : NavigationWrite

        /** Write the page's URL with the caret at the end, the ordinary case. */
        data object CollapseCaret : NavigationWrite

        /** Write the page's URL but keep the whole thing selected. */
        data object KeepSelection : NavigationWrite
    }

    /**
     * What a navigation callback should do to the field, as one decision.
     *
     * ONE function rather than a "may I write" predicate plus a "how" predicate, because the two
     * take the same arguments and one is a refinement of the other - so they have to agree, and
     * two calls means two clock reads and a correctness argument that lives in neither of them.
     *
     * The rules, in the order they resolve:
     *  1. **A stale untyped claim is not an active edit.** Nothing has been typed under it and
     *     nothing has touched it in [UNTYPED_CLAIM_STALE_MS], so the write goes through. If the
     *     field is still claimed the SELECTION IS KEPT: writing a collapsed caret into a field
     *     the user may still be focused in is the original bug, deferred a minute rather than
     *     fixed - they come back, type, and append instead of replacing.
     *  2. **Otherwise a claimed field is untouchable.** This is what Cmd+L relies on: the focus
     *     callback sets the claim BEFORE it selects, so a navigation landing a beat later on a
     *     still-loading or redirecting page cannot wipe the selection.
     *  3. **Otherwise the [USER_EDIT_GRACE_MS] buffer**, so a navigation landing mid-Tab-completion
     *     does not overwrite what the user is assembling.
     *
     * Note that rule 1 does not RELEASE the claim, only bypass it - so once a claim goes stale,
     * every later navigation in that tab keeps taking rule 1 and re-selecting the URL. That is
     * deliberate rather than overlooked: releasing would put the next navigation on rule 3, which
     * collapses the caret, and the user who wandered off mid-Cmd+L would be back to appending.
     * An invisible selection on an unfocused field costs nothing; the append does.
     *
     * @param msSinceUserEdit now minus the last user edit. Negative or absurd values (a clock
     *   step) simply read as "recent", which errs toward leaving the user's text alone.
     * @param typedSinceClaim whether anything has been typed since the current claim was taken.
     *   Tracked by the caller rather than inferred from the field's contents: "the field still
     *   reads as the loaded URL" looks like the same question and is not, because the bar
     *   legitimately shows something else on a home tab (`about:blank` over an empty
     *   `loadedUrl`) and after an abandoned draft outlives the claim released with it - and in
     *   exactly those states an inferred version would never let rule 1 fire.
     */
    fun navigationWrite(
        isUserEditing: Boolean,
        msSinceUserEdit: Long,
        typedSinceClaim: Boolean,
    ): NavigationWrite =
        when {
            !typedSinceClaim && msSinceUserEdit > UNTYPED_CLAIM_STALE_MS ->
                if (isUserEditing) NavigationWrite.KeepSelection else NavigationWrite.CollapseCaret

            isUserEditing -> NavigationWrite.Skip
            msSinceUserEdit > USER_EDIT_GRACE_MS -> NavigationWrite.CollapseCaret
            else -> NavigationWrite.Skip
        }

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
     * [navigationWrite] returns Skip and the URL bar silently stops following navigations.
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
