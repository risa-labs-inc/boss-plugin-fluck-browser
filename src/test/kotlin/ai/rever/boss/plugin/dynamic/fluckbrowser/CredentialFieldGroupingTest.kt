package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.PAGE_EVENT_BRIDGE
import ai.rever.boss.plugin.browser.PAGE_EVENT_EMIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executes the four credential scripts against pages that carry more than one login-shaped form,
 * and pins which boxes each of them pairs.
 *
 * Every one of them used to pair across the whole page. A login form and a signup form side by
 * side is an ordinary account page, and on it the fill wrote a username into one form and the
 * password into the other, the generated-password fill wrote its "confirm" copy into the login
 * form, the probe offered to generate a password for the login box, and the capture could save the
 * signup form's password against the login. Each of those reported success, so nothing in the
 * product surfaced them.
 *
 * [CredentialFillTest] and [CredentialCaptureTest] pin these scripts by substring and parse, which
 * proves what text they contain and nothing about which element they pick. The grouping rule is
 * the part that decides where a password is written, so it is run, not read.
 *
 * The page model is [LOGIN_PAGE_DOM], layered on [JsSandbox]. Like the sandbox, it is not a DOM: it
 * answers what these scripts ask for (form ownership including the `form` attribute, parent links,
 * document order, a native `value` setter, visibility) and nothing else. Layout is one row per
 * element in document order, so every visible field is inside the viewport.
 */
class CredentialFieldGroupingTest {
    // region fill - a saved credential into the page

    /** The control: one form, both boxes. Nothing about grouping may change this. */
    @Test
    fun `a single login form fills both of its boxes`() =
        page(LOGIN_FORM) { js ->
            js.focus("login-password")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(USER, js.value("login-email"))
            assertEquals(PASS, js.value("login-password"))
        }

    /**
     * The user right-clicks the signup form's email box. The username belongs there; the password
     * used to go to the first `current-password` box on the page, which is the login form's.
     */
    @Test
    fun `acting on a signup box never writes the password into the login form`() =
        page(LOGIN_FORM, SIGNUP_FORM) { js ->
            js.focus("signup-email")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(USER, js.value("signup-email"))
            assertEquals("", js.value("login-password"), "the password crossed into another form")
            assertEquals("", js.value("login-email"))
        }

    /**
     * The mirror case. The password box is the anchor, and the username used to be the first box on
     * the page with a `username` or `email` token, which with the signup form above is the signup
     * form's.
     */
    @Test
    fun `acting on a login password never writes the username into the signup form`() =
        page(SIGNUP_FORM, LOGIN_FORM) { js ->
            js.focus("login-password")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(PASS, js.value("login-password"))
            assertEquals(USER, js.value("login-email"), "the username belongs beside the password")
            assertEquals("", js.value("signup-email"), "the username crossed into another form")
        }

    /**
     * No anchor at all: `document.activeElement` is not a login field, which is what the right-click
     * menu sees when the page kept focus elsewhere. The two halves used to be chosen independently,
     * so they could land in different forms. The password is chosen first and the username is then
     * looked for beside it.
     */
    @Test
    fun `with no anchor, the username is taken from the form of the password it goes with`() =
        page(SIGNUP_FORM, LOGIN_FORM) { js ->
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(PASS, js.value("login-password"))
            assertEquals(USER, js.value("login-email"))
            assertEquals("", js.value("signup-email"))
        }

    /**
     * The first screen of a two-step sign-in, on a page that also offers registration. The login
     * form has no password box yet, so there is nothing to fill there - and the old rule found the
     * signup form's new-password box instead and wrote the saved password into it.
     */
    @Test
    fun `a two-step username screen does not borrow a password box from another form`() =
        page(TWO_STEP_USERNAME_FORM, SIGNUP_FORM) { js ->
            js.focus("step-email")
            val report = js.eval(CredentialFill.script(USER, PASS)) as String
            assertEquals(USER, js.value("step-email"))
            assertEquals("", js.value("signup-password"), "a saved password was written into a signup form")
            assertEquals("", js.value("signup-confirm"))
            assertTrue(report.contains("\"password\":\"absent\""), report)
        }

    /**
     * Grouping must not cost a page with no form element anything. Google's sign-in has none, which
     * is why the fill's rules were written without `form` in the first place.
     */
    @Test
    fun `a page with no form element still pairs its boxes`() =
        page(FORMLESS_LOGIN) { js ->
            js.focus("bare-password")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(USER, js.value("bare-email"))
            assertEquals(PASS, js.value("bare-password"))
        }

    /**
     * Markup that leaves the password outside the `<form>` the username is in. A different form is
     * never borrowed from, but a box that belongs to no form at all still is, so this page fills
     * exactly as it did before.
     */
    @Test
    fun `a password box left outside any form is still paired with the form beside it`() =
        page("h('FORM', { id: 'f' }, $EMAIL_IN_FORM)", "h('INPUT', { id: 'loose-password', type: 'password' })") { js ->
            js.focus("f-email")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(USER, js.value("f-email"))
            assertEquals(PASS, js.value("loose-password"))
        }

    /** The mirror case: a username box outside the form its password is in. */
    @Test
    fun `a username box left outside any form is still paired with the password form beside it`() =
        page(
            "h('INPUT', { id: 'loose-email', type: 'email', autocomplete: 'username' })",
            "h('FORM', { id: 'p' }, h('INPUT', { id: 'p-password', type: 'password', autocomplete: 'current-password' }))",
        ) { js ->
            js.focus("p-password")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(PASS, js.value("p-password"))
            assertEquals(USER, js.value("loose-email"))
        }

    /**
     * `form="id"` ownership is what `HTMLInputElement.form` reports, and grouping follows it rather
     * than where the box sits. The anchor is the attribute-owned box itself, placed after a signup
     * form, so reading ownership from nesting would group it with nothing and fall back to the page.
     */
    @Test
    fun `a box owned through the form attribute groups with its form, not with where it sits`() =
        page(
            SIGNUP_FORM,
            "h('FORM', { id: 'a' }, h('INPUT', { id: 'a-email', type: 'email', autocomplete: 'username' }))",
            "h('INPUT', { id: 'a-password', type: 'password', autocomplete: 'current-password', form: 'a' })",
        ) { js ->
            js.focus("a-password")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals(PASS, js.value("a-password"))
            assertEquals(USER, js.value("a-email"))
            assertEquals("", js.value("signup-email"), "ownership was read from nesting, not from the form attribute")
        }

    /** The decoy this feature was written against still loses, grouped or not. */
    @Test
    fun `a hidden password decoy inside the same form is still never filled`() =
        page(
            "h('FORM', { id: 'g' }, $EMAIL_IN_FORM, " +
                "h('INPUT', { id: 'decoy', type: 'password', name: 'hiddenPassword', hidden: true }), " +
                "h('INPUT', { id: 'real', type: 'password', autocomplete: 'current-password' }))",
        ) { js ->
            js.focus("f-email")
            js.eval(CredentialFill.script(USER, PASS))
            assertEquals("", js.value("decoy"))
            assertEquals(PASS, js.value("real"))
        }

    // endregion

    // region newPasswordScript - a generated password into a signup form

    /** The control: the target and its confirm twin, both in one signup form. */
    @Test
    fun `a generated password fills the signup form's own confirm box`() =
        page(SIGNUP_FORM) { js ->
            js.focus("signup-password")
            js.eval(CredentialFill.newPasswordScript(GENERATED))
            assertEquals(GENERATED, js.value("signup-password"))
            assertEquals(GENERATED, js.value("signup-confirm"))
        }

    /**
     * The worst of these. The twin used to be the first other password box on the page that is not
     * `current-password`, and a login form with no autocomplete tokens - most of them - qualifies.
     * So the generated password went into the login form, the real confirm box stayed empty, and the
     * report said `confirm: filled`, which is what the caller saves on.
     */
    @Test
    fun `a generated password never lands in the login form above the signup form`() =
        page(UNTOKENED_LOGIN_FORM, SIGNUP_FORM) { js ->
            js.focus("signup-password")
            val report = js.eval(CredentialFill.newPasswordScript(GENERATED)) as String
            assertEquals("", js.value("plain-password"), "the generated password was written into the login form")
            assertEquals(GENERATED, js.value("signup-confirm"), "the signup form's own confirm box was skipped")
            assertTrue(report.contains("\"confirm\":\"filled\""), report)
        }

    /**
     * What is saved to Secret Manager next to the generated password. A login email the user had
     * typed above used to win, because a `username`-token box wins wherever it is on the page.
     * Blank is the safe answer here - the save bar asks for a missing username - and a wrong account
     * name is not.
     */
    @Test
    fun `the username recorded with a generated password is never another form's`() =
        page(LOGIN_FORM, SIGNUP_FORM) { js ->
            js.eval("node('login-email')._value = 'someone-else@example.test';")
            js.focus("signup-password")
            val report = js.eval(CredentialFill.newPasswordScript(GENERATED)) as String
            assertFalse(report.contains("someone-else"), "another form's account name was recorded: $report")
        }

    // endregion

    // region LOGIN_FIELD_PROBE_JS - whether to offer a generated password

    /**
     * "More than one password box" is the probe's second signal for a password being chosen, and
     * it counted the whole page. So a plain login password box on a page with a signup form beside
     * it was offered a generated password - accepting that types a new password into a sign-in.
     */
    @Test
    fun `a login password box is not a new password because a signup form shares the page`() =
        page(UNTOKENED_LOGIN_FORM, UNTOKENED_SIGNUP_FORM) { js ->
            js.focus("plain-password")
            val probe = js.eval(LOGIN_FIELD_PROBE_JS) as String
            assertTrue(probe.contains("\"isNewPassword\":false"), probe)
        }

    /** The signal still works where it is true: two password boxes in one form. */
    @Test
    fun `a signup form with two password boxes is still a new password without any tokens`() =
        page(UNTOKENED_LOGIN_FORM, UNTOKENED_SIGNUP_FORM) { js ->
            js.focus("plain-new")
            val probe = js.eval(LOGIN_FIELD_PROBE_JS) as String
            assertTrue(probe.contains("\"isNewPassword\":true"), probe)
        }

    // endregion

    // region CredentialCapture.INSTALL_JS - what is offered for saving

    /**
     * The capture took the first password box on the page with anything in it. A submit event
     * carries the form that was submitted, and that is the only form whose password this is.
     */
    @Test
    fun `submitting the login form captures its password, not one typed into another form`() =
        capturePage(SIGNUP_FORM, LOGIN_FORM) { js ->
            js.eval("node('signup-password')._value = 'half-typed-signup';")
            js.eval("node('login-email')._value = '$USER'; node('login-password')._value = '$PASS';")
            js.eval("fire('submit', node('login'))")
            val posted = js.posted()
            assertTrue(posted.contains("\"password\":\"$PASS\""), posted)
            assertFalse(posted.contains("half-typed-signup"), posted)
        }

    /**
     * `usernameFor` let any box with a `username` or `email` token overwrite the username, wherever
     * it was on the page, including after the password. A signup email further down won.
     */
    @Test
    fun `the username captured with a login comes from the login form`() =
        capturePage(LOGIN_FORM, SIGNUP_FORM) { js ->
            js.eval("node('login-email')._value = '$USER'; node('login-password')._value = '$PASS';")
            js.eval("node('signup-email')._value = 'someone-else@example.test';")
            js.eval("fire('submit', node('login'))")
            val posted = js.posted()
            assertTrue(posted.contains("\"username\":\"$USER\""), posted)
        }

    /** Enter in a box is the same question, anchored on the box rather than the form. */
    @Test
    fun `pressing Enter in the login password captures the login form`() =
        capturePage(SIGNUP_FORM, LOGIN_FORM) { js ->
            js.eval("node('signup-password')._value = 'half-typed-signup';")
            js.eval("node('login-email')._value = '$USER'; node('login-password')._value = '$PASS';")
            js.eval("fire('keydown', node('login-password'), { key: 'Enter' })")
            val posted = js.posted()
            assertTrue(posted.contains("\"password\":\"$PASS\""), posted)
        }

    /** A click on the login form's own submit button, found through the button's form. */
    @Test
    fun `clicking the login form's submit button captures the login form`() =
        capturePage(SIGNUP_FORM, "h('FORM', { id: 'login' }, $LOGIN_FIELDS, h('BUTTON', { id: 'go', type: 'submit' }))") { js ->
            js.eval("node('signup-password')._value = 'half-typed-signup';")
            js.eval("node('login-email')._value = '$USER'; node('login-password')._value = '$PASS';")
            js.eval("fire('pointerdown', node('go'))")
            val posted = js.posted()
            assertTrue(posted.contains("\"password\":\"$PASS\""), posted)
        }

    /**
     * Submitting a form that holds no password is not a credential submission, whatever else on the
     * page holds one. A site search sent while a half-typed login password sits in the header would
     * otherwise be captured, and if the search navigates away the login box is gone - which is the
     * signal the save policy reads as a successful sign-in.
     */
    @Test
    fun `submitting a form with no password box captures nothing`() =
        capturePage(
            LOGIN_FORM,
            "h('FORM', { id: 'search' }, h('INPUT', { id: 'q', name: 'q', type: 'text', autocomplete: 'username' }))",
        ) { js ->
            js.eval("node('login-password')._value = 'half-typed';")
            js.eval("fire('submit', node('search'))")
            assertEquals("", js.posted(), "a search submit was captured as a login")
        }

    /** The control: a page with no form element still captures on Enter. */
    @Test
    fun `a form-less login still captures`() =
        capturePage(FORMLESS_LOGIN) { js ->
            js.eval("node('bare-email')._value = '$USER'; node('bare-password')._value = '$PASS';")
            js.eval("fire('keydown', node('bare-password'), { key: 'Enter' })")
            val posted = js.posted()
            assertTrue(posted.contains("\"username\":\"$USER\""), posted)
            assertTrue(posted.contains("\"password\":\"$PASS\""), posted)
        }

    // endregion

    /**
     * All four scripts get the rule from [FIELD_ELIGIBILITY_JS], the same way they get eligibility.
     * A copy in one of them is how the probe offering for one box and the fill writing another comes
     * back.
     */
    @Test
    fun `every credential script takes its grouping from the shared helpers`() {
        for ((name, script) in
            listOf(
                "fill" to CredentialFill.script(USER, PASS),
                "newPassword" to CredentialFill.newPasswordScript(GENERATED),
                "probe" to LOGIN_FIELD_PROBE_JS,
                "capture" to CredentialCapture.INSTALL_JS,
            )) {
            assertEquals(1, Regex("""function groupOf\(""").findAll(script).count(), "$name must carry exactly one groupOf")
            assertTrue(Regex("""groupOf\([^)]""").findAll(script).count() > 1, "$name defines groupOf but never calls it")
        }
    }

    // region harness

    private fun page(
        vararg body: String,
        test: (JsSandbox) -> Unit,
    ) = JsSandbox().use { js ->
        js.eval(LOGIN_PAGE_DOM, "LOGIN_PAGE_DOM")
        js.eval("mount(${body.joinToString(", ")})", "mount")
        test(js)
    }

    private fun capturePage(
        vararg body: String,
        test: (JsSandbox) -> Unit,
    ) = page(*body) { js ->
        // The host passes the bridge in as a parameter; at the top level of the sandbox a global of
        // the same name is what the script's free reference resolves to.
        js.eval("var posted = []; var $PAGE_EVENT_BRIDGE = { $PAGE_EVENT_EMIT: function (s) { posted.push(s); } };")
        js.eval(CredentialCapture.INSTALL_JS, "CredentialCapture.INSTALL_JS")
        test(js)
    }

    private fun JsSandbox.focus(id: String) {
        eval("node('$id').focus()")
    }

    private fun JsSandbox.value(id: String): String = eval("node('$id').value") as String

    private fun JsSandbox.posted(): String = eval("posted.join('\\n')") as String

    private companion object {
        const val USER = "me@example.test"
        const val PASS = "S3cret-saved"
        const val GENERATED = "Gen-9f2-x7Q"

        const val LOGIN_FIELDS =
            "h('INPUT', { id: 'login-email', name: 'email', type: 'email', autocomplete: 'username' }), " +
                "h('INPUT', { id: 'login-password', name: 'password', type: 'password', autocomplete: 'current-password' })"
        const val LOGIN_FORM = "h('FORM', { id: 'login' }, $LOGIN_FIELDS)"

        const val SIGNUP_FORM =
            "h('FORM', { id: 'signup' }, " +
                "h('INPUT', { id: 'signup-email', name: 'email', type: 'email', autocomplete: 'email' }), " +
                "h('INPUT', { id: 'signup-password', name: 'new', type: 'password', autocomplete: 'new-password' }), " +
                "h('INPUT', { id: 'signup-confirm', name: 'confirm', type: 'password', autocomplete: 'new-password' }))"

        /** No autocomplete tokens anywhere, which is most login forms. */
        const val UNTOKENED_LOGIN_FORM =
            "h('FORM', { id: 'plain' }, " +
                "h('INPUT', { id: 'plain-user', name: 'user', type: 'text' }), " +
                "h('INPUT', { id: 'plain-password', name: 'pass', type: 'password' }))"

        const val UNTOKENED_SIGNUP_FORM =
            "h('FORM', { id: 'join' }, " +
                "h('INPUT', { id: 'join-user', name: 'login', type: 'text' }), " +
                "h('INPUT', { id: 'plain-new', name: 'pw1', type: 'password' }), " +
                "h('INPUT', { id: 'plain-again', name: 'pw2', type: 'password' }))"

        const val TWO_STEP_USERNAME_FORM =
            "h('FORM', { id: 'step' }, h('INPUT', { id: 'step-email', type: 'email', autocomplete: 'username' }))"

        const val EMAIL_IN_FORM = "h('INPUT', { id: 'f-email', type: 'email', autocomplete: 'username' })"

        const val FORMLESS_LOGIN =
            "h('DIV', { id: 'panel' }, " +
                "h('INPUT', { id: 'bare-email', type: 'email', autocomplete: 'username' }), " +
                "h('INPUT', { id: 'bare-password', type: 'password', autocomplete: 'current-password' }))"

        /**
         * A login page, built with `mount(h(tag, attrs, children...), ...)` and read back with
         * `node(id)`.
         *
         * `form` is set only on INPUT and BUTTON, as on the real form-associated elements, and is
         * resolved the way `HTMLInputElement.form` is: the `form` attribute's target when present,
         * otherwise the nearest `<form>` ancestor, otherwise null. `hidden: true` models a
         * `display: none` box, which has no client rects. Document listeners are recorded for
         * `fire`, which the capture script listens through.
         */
        const val LOGIN_PAGE_DOM: String = """
            var docListeners = {};
            document.addEventListener = function (type, fn) {
                (docListeners[type] = docListeners[type] || []).push(fn);
            };
            window.fire = function (type, target, props) {
                var e = { type: type, target: target, isTrusted: true };
                if (props) { for (var k in props) e[k] = props[k]; }
                var l = docListeners[type] || [];
                for (var i = 0; i < l.length; i++) l[i](e);
            };
            function HTMLInputElement() {}
            Object.defineProperty(HTMLInputElement.prototype, 'value', {
                get: function () { return this._value; },
                set: function (v) { this._value = String(v); },
                configurable: true
            });
            window.HTMLInputElement = HTMLInputElement;
            window.FocusEvent = function (type) { this.type = type; };
            window.KeyboardEvent = function (type) { this.type = type; };
            window.Event = function (type) { this.type = type; };
            window.Node = { DOCUMENT_POSITION_PRECEDING: 2, DOCUMENT_POSITION_FOLLOWING: 4 };
            window.innerWidth = 1200;
            window.innerHeight = 4000;
            window.getComputedStyle = function (el) { return el._style; };
            var location = { host: 'example.test', href: 'https://example.test/account' };
            document.readyState = 'complete';
            document.hasFocus = function () { return true; };

            var nodes = [];
            var byId = {};
            window.h = function (tag, attrs) {
                var kids = Array.prototype.slice.call(arguments, 2);
                var a = attrs || {};
                var n = tag === 'INPUT' ? Object.create(HTMLInputElement.prototype) : {};
                n.tagName = tag;
                n.children = kids;
                n.parentElement = null;
                n._attrs = {};
                for (var k in a) n._attrs[k] = a[k];
                n.id = a.id || '';
                n.name = a.name || '';
                n.type = a.type || (tag === 'INPUT' ? 'text' : '');
                n.placeholder = '';
                n.textContent = a.text || '';
                n.disabled = false;
                n.readOnly = false;
                n.maxLength = -1;
                n._value = a.value || '';
                n._style = { display: a.hidden ? 'none' : 'block', visibility: 'visible', opacity: '1' };
                n.getAttribute = function (x) {
                    return Object.prototype.hasOwnProperty.call(n._attrs, x) ? String(n._attrs[x]) : null;
                };
                n.setAttribute = function (x, v) { n._attrs[x] = v; };
                n.getClientRects = function () { return a.hidden ? [] : [1]; };
                n.getBoundingClientRect = function () {
                    var top = 10 + n._order * 40;
                    return { left: 10, top: top, right: 210, bottom: top + 30, width: 200, height: 30 };
                };
                n.focus = function () { document.activeElement = n; };
                n.dispatchEvent = function () { return true; };
                n.compareDocumentPosition = function (o) { return o._order > n._order ? 4 : 2; };
                n.contains = function (o) {
                    for (var p = o; p; p = p.parentElement) { if (p === n) return true; }
                    return false;
                };
                for (var c = 0; c < kids.length; c++) kids[c].parentElement = n;
                return n;
            };
            window.mount = function () {
                var body = window.h.apply(null, ['BODY', {}].concat(Array.prototype.slice.call(arguments)));
                nodes = [];
                byId = {};
                var walk = function (n) {
                    n._order = nodes.length;
                    nodes.push(n);
                    if (n.id) byId[n.id] = n;
                    for (var i = 0; i < n.children.length; i++) walk(n.children[i]);
                };
                walk(body);
                for (var i = 0; i < nodes.length; i++) {
                    var n = nodes[i];
                    if (n.tagName !== 'INPUT' && n.tagName !== 'BUTTON') continue;
                    var owner = null;
                    if (n._attrs.form) {
                        owner = byId[n._attrs.form] || null;
                    } else {
                        for (var p = n.parentElement; p; p = p.parentElement) {
                            if (p.tagName === 'FORM') { owner = p; break; }
                        }
                    }
                    n.form = owner;
                }
                document.body = body;
                document.activeElement = body;
            };
            window.node = function (id) { return byId[id]; };
            document.querySelectorAll = function (selector) {
                var want = selector.toUpperCase();
                return nodes.filter(function (n) { return n.tagName === want; });
            };
        """
    }

    // endregion
}
