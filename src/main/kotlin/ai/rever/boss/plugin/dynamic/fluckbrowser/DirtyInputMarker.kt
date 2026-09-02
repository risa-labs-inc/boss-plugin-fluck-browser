package ai.rever.boss.plugin.dynamic.fluckbrowser

/**
 * Marks a document as holding unsubmitted typing, so hibernation will not discard it.
 *
 * ## Why a keystroke listener and not a DOM inspection
 *
 * This is the fourth attempt at guarding user input, and the first three are why it looks the
 * way it does. All three (withdrawn in d1552be) inspected the DOM at hibernate time and diffed
 * values against defaults, and each was wrong in a new way: matching any changed input exempted
 * every SPA that writes into its own fields; `defaultValue` reflects the `value=""` attribute
 * that login forms do not set, so an autofilled password was indistinguishable from a typed one
 * and login pages sat exempt for ~20 hours. The withdrawal names the correct shape - "a
 * page-load input listener setting a dirty flag" - because the DOM does not answer "has a human
 * typed here", but the event stream does: only a real keystroke arrives with `isTrusted`.
 * Autofill fires no trusted keydown. A framework writing to `value` fires none either. Both
 * documented failure modes are structurally impossible via those two paths - not "impossible",
 * full stop; see the CDP note below, which is a different path with a different answer.
 *
 * A keystroke basis also covers `contenteditable`, which the DOM approach never could - a
 * rich-text editor has no default to diff against, but typing into one is still typing.
 *
 * **Not a defence against CDP-level input injection.** `Input.dispatchKeyEvent` over the Chrome
 * DevTools Protocol produces `isTrusted: true` by design - Chromium cannot distinguish it from
 * real hardware, which is precisely why legitimate automation (and some password managers) use
 * it to defeat sites that reject programmatic autofill. A tool driving this browser at that
 * layer could set the flag from synthetic input and keep a tab permanently exempt. No JS-level
 * check can close that gap; it would need policy at the layer that grants CDP access at all,
 * which is out of scope for a busy-state probe. Named here so it is a known, accepted boundary
 * rather than a silent one - the guard's actual claim is narrower than "structurally impossible"
 * read in isolation would suggest.
 *
 * ## Why the flag is a window property
 *
 * The busy probe is a separate evaluation in a fresh scope, so cross-evaluation state has to
 * live somewhere both can reach, and for page script that is a window property or nothing (the
 * same conclusion CredentialCapture's install-guard note reaches). Contrast with
 * CredentialCapture, where a reachable global would have exposed *credentials*: same reasoning,
 * different stakes, opposite conclusion.
 *
 * The property is READ-only to the page: [INSTALL_JS] keeps the value in a closure and exposes
 * it through a non-configurable getter. The two directions a reachable global can be abused are
 * not symmetric, and only one of them costs the user anything:
 *
 *  - **Clearing it** would switch the guard off for that tab and hand back exactly the work loss
 *    this exists to prevent, so it is closed on both sides of install. After install: no setter,
 *    so an assignment is ignored in sloppy mode and throws in strict mode, and
 *    `configurable: false` refuses `delete` and redefinition. Before install: the guard tests for
 *    this script's own accessor rather than for the name being defined, so a page (or an
 *    accidental collision) occupying the name does not suppress the install - the name is
 *    reclaimed. The one way out left is a page that pre-defines the property non-configurable,
 *    which makes `defineProperty` throw into the outer `catch`; unavoidable from script, and a
 *    deliberate act rather than a one-line accident.
 *  - **Setting it** cannot be closed the same way and is not worth closing - a page that keeps
 *    itself permanently dirty (by typing into itself with trusted CDP input, say) exempts itself
 *    from hibernation. That is a self-inflicted process-tree leak, bounded by the next visit,
 *    not a data risk.
 *  - **Reading it** fingerprints BOSS, which `window.__bossCoBrowse` already concedes elsewhere.
 *
 * The credential channel (`setPageEventScript`) was deliberately NOT used, although a
 * Kotlin-side flag would avoid the global: the channel has one script slot per handle, owned by
 * credential capture and installed only while "offer to save passwords" is on. Riding it would
 * couple work-loss protection to an unrelated setting; claiming it would clobber credential
 * capture. Plain `executeJavaScript` on navigation is core API and works on every host.
 *
 * ## Install timing
 *
 * Installed from the navigation listener, not at document-start. Document-start exists to beat
 * page scripts; this only has to beat the *user's* first keystroke, and listener installation
 * after a navigation commit beats human reaction time comfortably. A new document starts with
 * the property undefined, so navigation resets the flag for free - submit-and-navigate clears
 * it with no bookkeeping.
 *
 * This is the single host assumption the whole feature rests on, and the API's own KDoc ("called
 * when the browser navigates") does not settle start-vs-commit on its own: confirmed against the
 * host's implementation, which fires this listener from its `NavigationFinished` handler
 * specifically, with a comment on that exact call site noting it "fires on navigation completion" -
 * not from `NavigationStarted`.
 *
 * ## What it deliberately misses, so the next reader does not "fix" it
 *
 * - Mouse-only edits (a checkbox toggled, a select chosen by mouse, drag-and-drop text).
 *   Trusted `change`/`click` could catch them, but Chromium fires trusted change events for
 *   autofill too, which reopens the 20-hour login-page exemption. Redoing a click is cheap;
 *   retyping a paragraph is not. The guard protects typing. One apparent exception: keyboard
 *   type-ahead on a focused `<select>` (pressing a letter to jump to a matching option) DOES mark
 *   dirty, because it is a `keydown` with a length-1 key like any other. Not actually an
 *   inconsistency - a keystroke that changed the select's value is typing by this guard's own
 *   definition; it is *mouse-driven* select changes specifically that are missed, not select
 *   changes in general.
 * - Typing inside frames. The listener runs in the main frame only; a cross-origin frame was
 *   never reachable, and a same-origin frame is deferred until this is worth per-frame plumbing.
 *   A shadow-DOM editor is missed for the same practical reason: a keystroke inside one retargets
 *   `e.target` to the shadow host, where `isContentEditable` is typically false.
 * - A submit whose own handler throws before clearing anything else client-side still clears this
 *   flag - the listener runs in capture phase, ahead of the page's own submit handlers, and only
 *   checks `isTrusted`. A submit is treated as strong-enough evidence the user is done, not proof
 *   the data reached a server; see the `clear` listener's own doc for the trade this makes.
 * - A client-side route change (`pushState`, a hash change, an SPA router) does NOT clear the
 *   flag. The document persists, so [INSTALL_JS]'s install guard correctly early-returns and
 *   nothing resets it; only a trusted `submit` does. A tab typed into once therefore stays exempt
 *   for the whole life of that DOCUMENT - not just that background session. Foregrounding and
 *   backgrounding re-arms the idle timer, but the flag is untouched by it, so the next check
 *   reads dirty and skips again. Same false-positive-safe direction as everything else here, but
 *   named because it lands on exactly the long-lived SPA class hibernation most wants to reclaim,
 *   which makes it a wider sliver than the cancelled-IME case below.
 *
 *   Closing the setter closed it to this plugin too: there is deliberately no Kotlin-side clear.
 *   One could be added (a nonce-named clear function defined at install time, so the page cannot
 *   guess it) but nothing today would call it - an age-based or wake-time reset is a policy
 *   decision, not a mechanism gap, and adding the mechanism with no caller would be a second
 *   reachable escape hatch maintained for a hypothetical.
 * - One flag, one `lastForm`, for the whole document. Typing into form B and then into form A
 *   moves `lastForm` to A, so submitting A clears the flag while B's draft is still unsubmitted.
 *   The form check below narrows the common shape of this (type a draft, submit an unrelated
 *   search box) but cannot close it; per-form state would need a set of dirty forms and a
 *   correspondingly richer probe result, which is more machinery than the remaining sliver of
 *   risk justifies.
 */
internal object DirtyInputMarker {
    /**
     * The window property name, shared with [FluckBrowserTabComponent.BUSY_SCRIPT] (the reader)
     * so setter and reader cannot drift independently. Both a `const val`, so - like
     * `PAGE_EVENT_BRIDGE` elsewhere in this plugin - the value is inlined into each referencing
     * file's constant pool at compile time rather than looked up at runtime; the two files still
     * recompile together, so the single source of truth holds without a runtime cross-module
     * dependency.
     */
    const val DIRTY_FLAG_PROPERTY: String = "__fluckDirty"

    /**
     * Idempotent: the property's DESCRIPTOR is the install guard - a non-configurable accessor is
     * this script's own signature - so re-running on the same document neither re-arms listeners
     * nor clears a flag already set. Deliberately not the property's value: that let a page
     * occupy the name first and suppress the install entirely (see the guard's own comment).
     * States: no accessor = not installed, `0` = installed and clean, `1` = the user has typed.
     *
     * Keys: any printable character (length 1 covers space), plus Backspace and Delete - editing
     * is typing. Enter is deliberately excluded: on a form it usually IS the submit, and marking
     * the tab dirty on the keystroke that saves the work inverts the guard's meaning.
     *
     * `compositionstart` is a second, independent trigger for the same flag - not a variant of
     * the keydown check. CJK/Korean/Japanese IME composition reports `key === 'Process'` (or
     * `'Unidentified'`) on every keydown in the sequence; the actual characters never arrive as a
     * keydown that satisfies the length-1-or-edit-key filter above, so a user composing a whole
     * paragraph through an IME would leave the flag unset and hibernate mid-draft with no signal
     * at all. `compositionstart` fires only for a real, user-driven input-method session - there
     * is no autofill or programmatic-value-assignment path that goes through IME composition, so
     * adding it does not reopen the autofill hole the keydown length check exists to avoid.
     *
     * Capture phase, so a page that stops propagation on its own editor cannot hide typing from
     * the guard - the same reasoning CredentialCapture documents for its submit listeners.
     * Listeners stay attached for the document's whole lifetime rather than removing themselves
     * once the flag is set - see the `submit` listener below for why "nothing left to learn"
     * stopped being true.
     *
     * A trusted `submit` (capture phase) resets the flag to `0`, but only when the submitted form
     * is the one the typing went into (`lastForm`), or when the typing was not in a form at all.
     * Without any clear at all, one keystroke into a search box exempts a long-lived SPA tab
     * (Gmail, Slack, Jira, Linear-shaped apps) from hibernation for the rest of its document
     * lifetime, which is exactly the class of heavy, long-lived renderer hibernation exists to
     * reclaim. Classic forms already clear the flag for free on navigation; this covers the SPA
     * case that `preventDefault()`s the submit and never navigates - the `submit` event still
     * fires either way, so the listener does not need to know whether the app went on to
     * navigate. The `lastForm` check is what keeps that from over-reaching in the other
     * direction: an unrelated form's submit says nothing about a draft sitting in a different
     * one, and clearing on it would silently make the draft hibernatable.
     *
     * This is a real trade in the opposite direction from every other over-triggering case in this
     * file: a submit is evidence the work was likely saved, not certain proof - a client-side
     * validation failure or a network error can fire `submit` and still lose the draft if
     * hibernation lands in the narrow gap before the user notices and resumes typing. Accepted
     * because the alternative (permanent exemption) costs more of hibernation's actual benefit
     * than this narrow window costs in risk.
     *
     * **Accepted over-triggering:** an IME session that is STARTED then cancelled (Escape, no text
     * committed) still marks the flag, since there is no `compositionend` handling to tell a
     * committed composition from an empty one. Same bias as above - a false positive keeps a
     * process alive an idle cycle longer, a false negative destroys work - left as-is rather than
     * adding complexity to distinguish "composition happened" from "composition produced text".
     */
    const val INSTALL_JS: String =
        "(function(){try{" +
            // The install guard asks whether OUR accessor is there - never whether the NAME is
            // taken. A page script runs before this does (install is on NavigationFinished), so a
            // guard on the property's value lets one line at document start suppress the install
            // entirely: no listeners, and BUSY_SCRIPT reading the page's own 0 for the document's
            // life. A name occupied by anything that is not ours falls through to the
            // defineProperty below and is RECLAIMED - a plain assignment is configurable, so
            // redefining over it succeeds.
            "var own = Object.getOwnPropertyDescriptor(window, '$DIRTY_FLAG_PROPERTY');" +
            "if (own && typeof own.get === 'function' && !own.configurable) return;" +
            // The flag lives in this closure, not on `window`; the property is a non-configurable
            // GETTER over it, so `window.$DIRTY_FLAG_PROPERTY = 0` from page script cannot switch
            // the guard off. See the class KDoc's tamper note. The reader (BUSY_SCRIPT) is
            // unaffected: it still reads the property.
            "var dirty = 0;" +
            "var lastForm = null;" +
            "Object.defineProperty(window, '$DIRTY_FLAG_PROPERTY', {" +
            "get: function(){ return dirty; }," +
            "configurable: false" +
            "});" +
            "var editable = function(t){" +
            "if (!t) return false;" +
            "if (t.readOnly) return false;" +
            "var tag = (t.tagName || '').toUpperCase();" +
            "return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || !!t.isContentEditable;" +
            "};" +
            "var mark = function(e){" +
            "if (!e.isTrusted) return;" +
            "if (!editable(e.target)) return;" +
            "if (e.type === 'keydown'){" +
            // A modifier held alongside a printable key is a shortcut (copy/cut/select-all/…),
            // never a character insertion - real typing never carries ctrl/meta/alt. Paste
            // already has its own dedicated, correct listener below; this exists so Cmd+A/C/X
            // alone (which insert nothing) cannot mark a page dirty for a shortcut, not a keystroke.
            "if (e.ctrlKey || e.metaKey || e.altKey) return;" +
            "var k = e.key || '';" +
            "if (k.length !== 1 && k !== 'Backspace' && k !== 'Delete') return;" +
            "}" +
            // Which form the typing went into, so `clear` can tell "the draft was submitted"
            // from "some other form on the page was submitted". Null for contenteditable and
            // for an input outside any form - see `clear` for what that case falls back to.
            "lastForm = (e.target && e.target.form) || null;" +
            "dirty = 1;" +
            "};" +
            "var clear = function(e){" +
            "if (!e.isTrusted) return;" +
            // A submit only speaks for the form the typing was actually in. Without this, a
            // query typed into a site's search box and submitted would clear the flag set by a
            // long unsubmitted comment in a different form on the same page - the exact loss
            // this feature exists to prevent, and likelier than any of the over-triggering
            // cases the KDoc accepts. `lastForm === null` keeps the old clear-on-any-submit
            // behaviour, which is what the SPA case this listener was added for needs.
            "if (lastForm && e.target !== lastForm) return;" +
            "lastForm = null;" +
            "dirty = 0;" +
            "};" +
            "window.addEventListener('keydown', mark, true);" +
            "window.addEventListener('paste', mark, true);" +
            "window.addEventListener('cut', mark, true);" +
            "window.addEventListener('compositionstart', mark, true);" +
            "window.addEventListener('submit', clear, true);" +
            "}catch(e){}})()"
}
