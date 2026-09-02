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
 * A keystroke basis also covers `contenteditable`, which the DOM approach never could - a rich
 *-text editor has no default to diff against, but typing into one is still typing.
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
 * same conclusion CredentialCapture's install-guard note reaches). The costs are accepted with
 * eyes open: a page that PRE-SETS `__fluckDirty = 1` exempts itself from hibernation - a
 * self-inflicted process-tree leak, bounded by the next visit, not a data risk; a page that
 * reads it can fingerprint BOSS, which `window.__bossCoBrowse` already concedes elsewhere.
 * Contrast with CredentialCapture, where a reachable global would have exposed *credentials*:
 * same reasoning, different stakes, opposite conclusion.
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
 * ## What it deliberately misses, so the next reader does not "fix" it
 *
 * - Mouse-only edits (a checkbox toggled, a select chosen by mouse, drag-and-drop text).
 *   Trusted `change`/`click` could catch them, but Chromium fires trusted change events for
 *   autofill too, which reopens the 20-hour login-page exemption. Redoing a click is cheap;
 *   retyping a paragraph is not. The guard protects typing.
 * - Typing inside frames. The listener runs in the main frame only; a cross-origin frame was
 *   never reachable, and a same-origin frame is deferred until this is worth per-frame plumbing.
 * - An SPA submit that never navigates leaves the flag set, so the tab is "left alone" at the
 *   recheck limit - the same bounded trade SHOWN_IN_POP_OUT makes for calls, and the safe
 *   direction: a leaked process tree costs memory until the next visit; a discarded draft costs
 *   the user's work.
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
     * Idempotent: the property doubles as the install guard, so re-running on the same document
     * neither re-arms listeners nor clears a flag already set. States: `undefined` = not
     * installed, `0` = installed and clean, `1` = the user has typed.
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
     * Listeners remove themselves once the flag is set: after the first real keystroke there is
     * nothing left to learn.
     *
     * **Accepted over-triggering, a codex red-team finding on an earlier revision:** an IME
     * session that is STARTED then cancelled (Escape, no text committed) still marks the flag,
     * permanently, since there is no `compositionend` handling to tell a committed composition
     * from an empty one. This is the same deliberate bias every over-triggering case in this file
     * already carries - a false positive keeps a process alive an idle cycle longer; a false
     * negative destroys work - so it is left as-is rather than added complexity to distinguish
     * "composition happened" from "composition produced text". The install-timing race this
     * shares with the keydown listener (a marker installed after the user's first input event
     * cannot retroactively see it) is likewise pre-existing, not new here - see "Install timing"
     * above.
     */
    val INSTALL_JS: String =
        "(function(){try{" +
            "if (typeof window.$DIRTY_FLAG_PROPERTY !== 'undefined') return;" +
            "window.$DIRTY_FLAG_PROPERTY = 0;" +
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
            "window.$DIRTY_FLAG_PROPERTY = 1;" +
            "window.removeEventListener('keydown', mark, true);" +
            "window.removeEventListener('paste', mark, true);" +
            "window.removeEventListener('cut', mark, true);" +
            "window.removeEventListener('compositionstart', mark, true);" +
            "};" +
            "window.addEventListener('keydown', mark, true);" +
            "window.addEventListener('paste', mark, true);" +
            "window.addEventListener('cut', mark, true);" +
            "window.addEventListener('compositionstart', mark, true);" +
            "}catch(e){}})()"
}
