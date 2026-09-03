package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.PAGE_EVENT_BRIDGE
import ai.rever.boss.plugin.browser.PAGE_EVENT_EMIT
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One posted event, as it crosses from the JxBrowser thread into composition.
 *
 * [url] is the host's reading of the document that posted, not anything the page supplied. It is the
 * only trustworthy attribution available here, which is why it travels with the payload rather than
 * being resolved later from the handle.
 */
internal data class CapturedEvent(
    val url: String,
    val json: String,
)

/**
 * Noticing that the user just submitted a credential, so it can be offered to Secret Manager.
 *
 * **Why this is a pushed event and not a poll.** The credential exists in the page for one moment.
 * A submit is followed by a navigation that destroys the JS context, so anything latched in the page
 * for the next poll to collect is racing its own teardown - and loses whenever the login is fast.
 * `BrowserHandle.setPageEventScript` (api 1.0.83) exists for exactly this: the script below is
 * installed at document start and calls back while the document that produced the event is alive.
 *
 * **What crosses the boundary, and when.** A password. Once, on a submit the user performed.
 * Deliberately contrast this with [LOGIN_FIELD_PROBE_JS], which runs several times a second and
 * returns `hasValue: Boolean` and never a value - a probe that returned page text at that rate would
 * be a keylogger. The rule that keeps both honest: a *periodic* read never carries a value, and a
 * value only ever moves on something the user did.
 *
 * **The bridge is a scoped parameter, so a page cannot reach this channel.** It is not a `window`
 * property: the host passes it into the script, which is what stops a page replacing it to receive
 * the credential, forging a submission into the plugin's sink, or detecting BOSS by probing for a
 * name. An earlier draft of this KDoc described the window-property design and the threat model that
 * came with it; both are gone.
 *
 * Two things are still not the page's to be trusted about, so the parse stays defensive: field
 * lengths are bounded, and the site an event is attributed to comes from the URL the HOST read off
 * the posting document, never from the payload - which is why the payload carries no URL at all.
 */
internal object CredentialCapture {
    /** What one submit looked like. */
    @Serializable
    data class CapturedCredential(
        val username: String = "",
        val password: String = "",
        /**
         * True when the password field still carries the `data-boss-filled` marker
         * [CredentialFill] sets, meaning this value came from a saved secret rather than the
         * keyboard. Nothing to save - it is already stored, unchanged.
         */
        @SerialName("filled") val wasFilledByBoss: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read one pushed event, or null for anything that is not a usable credential submission.
     *
     * Tolerant in the same way [parseLoginFieldProbe] is, and for a stronger reason: this input
     * arrives from a channel any page script can call, so malformed and hostile look identical
     * here and both must come back as "nothing happened".
     */
    fun parse(raw: String?): CapturedCredential? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { json.decodeFromString<CapturedCredential>(text) }.getOrNull() ?: return null
        // A submission with no password is not one. The first screen of a two-step sign-in reaches
        // here with an identifier and nothing else, and it is not something to offer to save.
        if (parsed.password.isEmpty()) return null
        // Bound what a page can push in one string. A credential longer than this is not one, and
        // the pending capture is held in memory until the user answers the bar.
        if (parsed.password.length > MAX_FIELD_LENGTH || parsed.username.length > MAX_FIELD_LENGTH) return null
        return parsed
    }

    /** Longer than any real credential, short enough that a hostile page cannot push a payload. */
    internal const val MAX_FIELD_LENGTH = 512

    /**
     * The document-start script.
     *
     * Field eligibility comes from [FIELD_ELIGIBILITY_JS], shared with the probe and the fill, so
     * all three agree on what a login field is. The specific trap it encodes: a `display: none`
     * password input (`accounts.google.com` ships one) must not be read as the field the user
     * typed into, or the "saved" password is whatever a decoy held.
     *
     * The bridge arrives as a parameter named [PAGE_EVENT_BRIDGE], not as a `window` property, so
     * the script never touches `window` to post. That is the api's shape and the reason for it is
     * this script's payload: a documented global would let any page script replace it and receive
     * the credential, forge a submission, or fingerprint BOSS.
     *
     * **There is no install guard, and a duplicate evaluation is tolerated rather than prevented.**
     *
     * What the guard was, since it looked harmless: a `window.__bossCredCaptureInstalled` flag -
     * which any page could read to identify BOSS, and any page could PRE-SET to switch credential
     * capture off for itself. It could not move somewhere quieter either, because a guard in this
     * script's own scope is invisible to a second evaluation (the host wraps the script, so each run
     * gets a fresh scope) - the choice was a window property or nothing.
     *
     * Nothing wins because a duplicate costs nothing HERE, which is worth spelling out rather than
     * assuming. Reinstalling while a login page is open evaluates this twice, so a submit fires two
     * sets of listeners and posts twice. Both posts carry identical values, the channel that
     * receives them is CONFLATED, and the policy holds one pending capture - so two identical posts
     * and one produce the same single prompt. The host was briefly documented as deduping for us;
     * that guarantee turned out to be unimplementable on its side, and this consumer never needed
     * it.
     *
     * Three listeners rather than one, all in the capture phase so a page that calls
     * `stopPropagation` on its own form cannot hide the submit:
     *
     * - `submit` - the normal case, and the only one that fires for a form submitted by script.
     * - `keydown` Enter - a login form with no submit button, or one that submits via fetch.
     * - `pointerdown` on something that looks like a submit control - the same, for a mouse.
     *
     * `pointerdown` rather than `click`: a page that re-renders on click can detach the field
     * before a click listener runs, and the values are gone by then.
     */
    val INSTALL_JS: String =
        """
        (function() {
        // Nothing on window at all, in either direction.
        //
        // $PAGE_EVENT_BRIDGE is a PARAMETER the host passes in, because a documented global would
        // let any page script replace it and receive the credential, forge a submission, or detect
        // BOSS by probing for the name.
        //
        // And there is no install guard here any more. It used to be
        // `window.__bossCredCaptureInstalled`, which was the same mistake in the other direction: a
        // marker any page could read to identify BOSS, and worse, one any page could PRE-SET to
        // suppress credential capture on itself entirely.
        //
        // Nothing replaced it, because a duplicate evaluation costs nothing here: both posts carry
        // identical values, the channel is CONFLATED and the policy holds one pending capture, so
        // two posts and one produce the same single prompt. Do not reintroduce a dedupe here on
        // the strength of a host-side one: the host's was withdrawn as unimplementable on its
        // side, so this script cannot assume any.
        $FIELD_ELIGIBILITY_JS

        function isPassword(el) {
            return (el.type || '').toLowerCase() === 'password';
        }

        // Emitted at most once per distinct credential per document. Enter and submit both fire for
        // the same keystroke, and a login form that submits via fetch can fire pointerdown too.
        var lastKey = null;

        function eligibleFields() {
            var all = document.querySelectorAll('input');
            var out = [];
            for (var i = 0; i < all.length; i++) {
                if (isLoginField(all[i])) out.push(all[i]);
            }
            return out;
        }

        // The username that goes with a password box: the last eligible non-password field BEFORE
        // it, which is where a login form puts it. Same rule as the fill's pickUsername, and for
        // the same reason - "first text input in the enclosing form" cannot work on a page with no
        // form element, which is most of the large sign-in pages.
        function usernameFor(fields, pw) {
            var best = '';
            for (var i = 0; i < fields.length; i++) {
                var el = fields[i];
                if (isPassword(el)) continue;
                if (hasToken(el, 'username') || hasToken(el, 'email')) {
                    if (el.value) best = el.value;
                    continue;
                }
                var precedes = el.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING;
                if (precedes && el.value) best = el.value;
            }
            return best;
        }

        function capture() {
            try {
                var fields = eligibleFields();
                var pw = null;
                for (var i = 0; i < fields.length; i++) {
                    // The FIRST password box with something in it. On a change-password form that
                    // is the current-password box, which is the one worth saving; the new one is
                    // handled by the suggestion path, which saves what it generated.
                    if (isPassword(fields[i]) && fields[i].value) { pw = fields[i]; break; }
                }
                if (!pw) return;
                var payload = {
                    username: usernameFor(fields, pw),
                    password: pw.value,
                    filled: pw.getAttribute('data-boss-filled') === 'true'
                };
                var key = payload.username + ' ' + payload.password;
                if (key === lastKey) return;
                lastKey = key;
                // The host-supplied bridge, straight from this script's scope.
                if (typeof $PAGE_EVENT_BRIDGE !== 'undefined' && $PAGE_EVENT_BRIDGE) {
                    // Both names come from api constants, so a rename on either side is one edit
                    // rather than a channel that silently posts into nothing.
                    $PAGE_EVENT_BRIDGE.$PAGE_EVENT_EMIT(JSON.stringify(payload));
                }
            } catch (e) {
                // A page that throws from a getter must not break its own submit.
            }
        }

        function looksLikeSubmit(el) {
            var node = el;
            for (var depth = 0; node && depth < 4; depth++) {
                var tag = (node.tagName || '').toLowerCase();
                if (tag === 'button' || tag === 'a') {
                    var type = (node.getAttribute('type') || '').toLowerCase();
                    if (tag === 'button' && type !== 'button' && type !== 'reset') return true;
                    var text = (node.innerText || node.textContent || '').trim().toLowerCase();
                    if (text.length <= 40 && /sign ?in|log ?in|continue|next|submit|sign ?up|register|create account/.test(text)) {
                        return true;
                    }
                }
                if (tag === 'input') {
                    var it = (node.type || '').toLowerCase();
                    if (it === 'submit' || it === 'image') return true;
                }
                node = node.parentElement;
            }
            return false;
        }

        document.addEventListener('submit', function() { capture(); }, true);
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') capture();
        }, true);
        document.addEventListener('pointerdown', function(e) {
            if (looksLikeSubmit(e.target)) capture();
        }, true);
        })();
        """.trimIndent()
}
