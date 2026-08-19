package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Filling a chosen credential into the page, from the plugin rather than through the host.
 *
 * `BrowserHandle.fillCredentials` exists and is where this used to go, but it takes only
 * `(username, password, fillBoth)`. It has **no parameter for which field the user acted on**, so
 * it can only guess - and its guess picked Google's `display: none` `hiddenPassword` decoy while
 * leaving the visible email box empty.
 *
 * Fixing that guess was the first attempt. It is being deleted instead, because the guess is the
 * design error: this plugin knows something the API cannot express. The right-click menu was raised
 * on a specific field and the suggestion list is anchored to a specific box, so filling *that* box
 * is better information than any heuristic can reconstruct - which puts credential filling on the
 * caller's side of the boundary, not the host's.
 *
 * There is no fallback to it. Both paths go through `mainFrame().executeJavaScript`, so every
 * condition that stops this one - a torn-down handle, no main frame, a throwing call - stops the
 * host's injector for the same reason. A fallback that cannot succeed where the primary failed is
 * not a safety net, just a second way to write a password somewhere nobody asked for. The host
 * implementation and the API method itself are being removed alongside this
 * (BossConsole#215, boss-plugin-api).
 */
internal object CredentialFill {
    /** What happened to one of the two fields. */
    enum class FieldOutcome {
        /** Filled. */
        FILLED,

        /** No such field is on this page. Not a failure - a two-step sign-in has no password box. */
        ABSENT,

        /** The field is there and would not take the value. */
        FAILED,

        /** The page could not be asked. */
        UNKNOWN,
    }

    @Serializable
    private data class RawResult(
        val username: String = "unknown",
        val password: String = "unknown",
        val usernameField: String? = null,
        val passwordField: String? = null,
    )

    data class Result(
        val username: FieldOutcome,
        val password: FieldOutcome,
    ) {
        /** Anything landed at all. */
        val filledSomething: Boolean
            get() = username == FieldOutcome.FILLED || password == FieldOutcome.FILLED
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read the fill script's answer. A null or unreadable answer is [FieldOutcome.UNKNOWN] for both
     * halves, which the caller treats as "could not fill" - the honest reading, since the script
     * reports its own failures explicitly and silence means it never ran.
     */
    fun parseResult(raw: String?): Result {
        val parsed =
            raw?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { json.decodeFromString<RawResult>(text) }.getOrNull()
            } ?: return Result(FieldOutcome.UNKNOWN, FieldOutcome.UNKNOWN)
        return Result(outcome(parsed.username), outcome(parsed.password))
    }

    private fun outcome(token: String): FieldOutcome =
        when (token) {
            "filled" -> FieldOutcome.FILLED
            "absent" -> FieldOutcome.ABSENT
            "failed" -> FieldOutcome.FAILED
            else -> FieldOutcome.UNKNOWN
        }

    /**
     * What to tell the user, or null when there is nothing worth saying.
     *
     * Silence is the answer for every outcome the page itself makes obvious. In particular
     * "username filled, no password box here" is the *first screen of a Google sign-in* and is a
     * complete success - warning about it would fire on the commonest login flow on the web.
     */
    fun notice(result: Result): String? =
        when {
            result.username == FieldOutcome.FAILED || result.password == FieldOutcome.FAILED ->
                if (result.filledSomething) PARTIAL_NOTICE else FAILED_NOTICE

            result.filledSomething -> null

            result.username == FieldOutcome.ABSENT && result.password == FieldOutcome.ABSENT ->
                NO_FIELD_NOTICE

            else -> FAILED_NOTICE
        }

    /**
     * Embed [value] as a JavaScript string literal, quotes included.
     *
     * A credential is arbitrary text and this is being spliced into source. A carriage return or
     * U+2028 inside a literal is a SyntaxError in JavaScript, which would take down the whole
     * script and surface as "no login box could be filled" - a fault indistinguishable from a page
     * that genuinely has no form.
     */
    fun jsLiteral(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        value.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                // Not a JavaScript concern but a cheap one: a value that cannot open a tag cannot
                // end up in an injected one either.
                c == '<' -> sb.append("\\u003c")
                c.code < 0x20 || c.code == 0x2028 || c.code == 0x2029 ->
                    sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * The script that resolves and fills.
     *
     * [targetIndex] is the field's position in the eligible-login-field list, as reported by
     * [LOGIN_FIELD_PROBE_JS]. Passed when the suggestion list is anchored to a known box, omitted
     * for the right-click menu, where `document.activeElement` is the anchor instead. Either way
     * the anchor decides which half is filled first, and its counterpart is looked up around it.
     */
    fun script(
        username: String,
        password: String,
        targetIndex: Int? = null,
    ): String =
        """
        (function() {
        $FIELD_ELIGIBILITY_JS
        var USERNAME = ${jsLiteral(username)};
        var PASSWORD = ${jsLiteral(password)};
        var TARGET = ${targetIndex ?: -1};

        // React 16+ compares against its own value tracker and ignores a plain assignment, so the
        // value goes in through the prototype's native setter with `input` dispatched after it.
        function fill(el, value) {
            try {
                if (document.activeElement !== el) el.focus();
                el.dispatchEvent(new FocusEvent('focus', { bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keydown', {
                    bubbles: true, cancelable: true, key: 'Unidentified', code: 'Unidentified'
                }));
                var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ).set;
                setter.call(el, value);
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keyup', {
                    bubbles: true, cancelable: true, key: 'Unidentified', code: 'Unidentified'
                }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                el.setAttribute('data-boss-filled', 'true');
                // Deliberately NOT compared against the value we set: a site that reformats or
                // masks on input has still accepted it, and calling that a failure would warn the
                // user about a fill that worked.
                return el.value.length > 0;
            } catch (e) {
                return false;
            }
        }
        function label(el) {
            return el.name || el.id || el.getAttribute('aria-label') || 'unnamed';
        }
        function isPassword(el) {
            return (el.type || '').toLowerCase() === 'password';
        }

        var all = document.querySelectorAll('input');
        var eligible = [];
        var i;
        for (i = 0; i < all.length; i++) {
            if (isLoginField(all[i])) eligible.push(all[i]);
        }

        // The box the user acted on. TARGET is authoritative when the caller knew it; the DOM's
        // own activeElement is the fallback, and it survives the suggestion popup opening because
        // that popup is non-focusable and never takes focus off the browser view.
        var anchor = null;
        if (TARGET >= 0 && TARGET < eligible.length) {
            anchor = eligible[TARGET];
        } else {
            var active = document.activeElement;
            for (i = 0; i < eligible.length; i++) { if (eligible[i] === active) anchor = eligible[i]; }
        }

        var passwords = [];
        for (i = 0; i < eligible.length; i++) {
            if (isPassword(eligible[i])) passwords.push(eligible[i]);
        }
        var usernames = [];
        for (i = 0; i < eligible.length; i++) {
            if (!isPassword(eligible[i])) usernames.push(eligible[i]);
        }

        function pickUsername() {
            if (anchor && !isPassword(anchor)) return anchor;
            if (usernames.length === 0) return null;
            for (i = 0; i < usernames.length; i++) {
                if (hasToken(usernames[i], 'username') || hasToken(usernames[i], 'email')) {
                    return usernames[i];
                }
            }
            for (i = 0; i < usernames.length; i++) {
                if ((usernames[i].type || '').toLowerCase() === 'email') return usernames[i];
            }
            // The last username box BEFORE the password box, which is where a login form puts it.
            // Replaces "first text input in the form containing a password" - Google's sign-in
            // page has no form element at all, so that rule could never fire there.
            var pw = passwords[0];
            if (pw) {
                var best = null;
                for (i = 0; i < usernames.length; i++) {
                    var following = usernames[i].compareDocumentPosition(pw) &
                        Node.DOCUMENT_POSITION_FOLLOWING;
                    if (following) best = usernames[i];
                }
                if (best) return best;
            }
            return usernames[0];
        }

        function pickPassword() {
            if (anchor && isPassword(anchor)) return anchor;
            if (passwords.length === 0) return null;
            // current-password before a bare password box, so a change-password form gets the old
            // value rather than being handed it as the new one.
            for (i = 0; i < passwords.length; i++) {
                if (hasToken(passwords[i], 'current-password')) return passwords[i];
            }
            for (i = 0; i < passwords.length; i++) {
                if (!hasToken(passwords[i], 'new-password')) return passwords[i];
            }
            return passwords[0];
        }

        var uField = pickUsername();
        var pField = pickPassword();
        var report = {
            username: uField ? (fill(uField, USERNAME) ? 'filled' : 'failed') : 'absent',
            password: pField ? (fill(pField, PASSWORD) ? 'filled' : 'failed') : 'absent',
            usernameField: uField ? label(uField) : null,
            passwordField: pField ? label(pField) : null
        };
        // Focus is left on the anchor rather than wherever the last fill put it, so the user can
        // carry on typing or press Enter in the box they were already in.
        try { if (anchor) anchor.focus(); } catch (e) { /* detached node */ }
        return JSON.stringify(report);
        })();
        """.trimIndent()

    internal const val NO_FIELD_NOTICE = "No login box on this page could be filled"
    internal const val FAILED_NOTICE = "Could not fill the login box on this page"
    internal const val PARTIAL_NOTICE = "Only one of the two login boxes could be filled"
}
