package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.dynamic.fluckbrowser.CredentialFill.FieldOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the plugin-side credential fill: reading the script's report, deciding what the
 * user is told, and embedding the credential safely in the script.
 *
 * The resolution and filling are JavaScript against a live document, so they are verified by
 * running them on real login pages. What is pinned here is the part that decides whether the user
 * sees a warning - which used to be nothing at all, and is the reason a fill landing in a hidden
 * field went unnoticed.
 */
class CredentialFillTest {
    private fun report(
        username: String,
        password: String,
    ) = """{"username":"$username","password":"$password","usernameField":"identifier","passwordField":null}"""

    // ------------------------------------------------------------------ parseResult

    @Test
    fun `a report of both fields filled reads as filled`() {
        val result = CredentialFill.parseResult(report("filled", "filled"))
        assertEquals(FieldOutcome.FILLED, result.username)
        assertEquals(FieldOutcome.FILLED, result.password)
        assertTrue(result.filledSomething)
    }

    @Test
    fun `an absent password is absent, not failed`() {
        val result = CredentialFill.parseResult(report("filled", "absent"))
        assertEquals(FieldOutcome.FILLED, result.username)
        assertEquals(FieldOutcome.ABSENT, result.password)
    }

    @Test
    fun `no answer from the page is unknown on both halves`() {
        // The script reports its own failures explicitly, so silence means it never ran - which is
        // what the host fallback is keyed on.
        for (raw in listOf(null, "", "   ", "not json", """{"username":}""")) {
            val result = CredentialFill.parseResult(raw)
            assertEquals(FieldOutcome.UNKNOWN, result.username, "for ${raw ?: "null"}")
            assertEquals(FieldOutcome.UNKNOWN, result.password, "for ${raw ?: "null"}")
        }
    }

    @Test
    fun `a token this code does not know is unknown rather than a silent success`() {
        val result = CredentialFill.parseResult(report("something-new", "filled"))
        assertEquals(FieldOutcome.UNKNOWN, result.username)
        assertEquals(FieldOutcome.FILLED, result.password)
    }

    @Test
    fun `extra fields in the report do not throw the whole reading away`() {
        val result =
            CredentialFill.parseResult(
                """{"username":"filled","password":"absent","note":"added later"}""",
            )
        assertEquals(FieldOutcome.FILLED, result.username)
    }

    // ---------------------------------------------------------------------- notice

    @Test
    fun `a two-step sign-in says nothing at all`() {
        // The case this exists for. accounts.google.com asks for the identifier first and shows the
        // password box only on the next screen, so "username filled, no password box" is a complete
        // success. Warning here would fire on the commonest login flow on the web.
        assertNull(CredentialFill.notice(CredentialFill.Result(FieldOutcome.FILLED, FieldOutcome.ABSENT)))
    }

    @Test
    fun `a full login form says nothing`() {
        assertNull(CredentialFill.notice(CredentialFill.Result(FieldOutcome.FILLED, FieldOutcome.FILLED)))
    }

    @Test
    fun `a password-only screen says nothing`() {
        assertNull(CredentialFill.notice(CredentialFill.Result(FieldOutcome.ABSENT, FieldOutcome.FILLED)))
    }

    @Test
    fun `a page with no login box at all is reported`() {
        assertEquals(
            CredentialFill.NO_FIELD_NOTICE,
            CredentialFill.notice(CredentialFill.Result(FieldOutcome.ABSENT, FieldOutcome.ABSENT)),
        )
    }

    @Test
    fun `a box that exists and refused the value is reported`() {
        assertEquals(
            CredentialFill.FAILED_NOTICE,
            CredentialFill.notice(CredentialFill.Result(FieldOutcome.FAILED, FieldOutcome.ABSENT)),
        )
    }

    @Test
    fun `landing one of two is reported as partial, not as total failure`() {
        assertEquals(
            CredentialFill.PARTIAL_NOTICE,
            CredentialFill.notice(CredentialFill.Result(FieldOutcome.FILLED, FieldOutcome.FAILED)),
        )
    }

    @Test
    fun `a page that could not be scripted is reported rather than passing silently`() {
        // The both-UNKNOWN case reaches notice() only after the host fallback also failed, and the
        // user has to be told - this is precisely the silence that hid the original bug.
        assertEquals(
            CredentialFill.FAILED_NOTICE,
            CredentialFill.notice(CredentialFill.Result(FieldOutcome.UNKNOWN, FieldOutcome.UNKNOWN)),
        )
    }

    @Test
    fun `a half-known outcome still says nothing when something landed`() {
        // UNKNOWN is not evidence of failure. Something demonstrably went in, and warning on the
        // strength of the half we cannot read would fire on a fill the user can see worked.
        assertNull(CredentialFill.notice(CredentialFill.Result(FieldOutcome.FILLED, FieldOutcome.UNKNOWN)))
    }

    // ------------------------------------------------------------------- jsLiteral
    //
    // Escaped string literals, not raw ones: every expectation both starts and ends with a double
    // quote, and a raw string wrapping one is ambiguous about where it terminates.

    @Test
    fun `a quote in a credential does not close the literal`() {
        assertEquals("\"it\\\"s\"", CredentialFill.jsLiteral("it\"s"))
    }

    @Test
    fun `a backslash is escaped once, not doubled away`() {
        assertEquals("\"a\\\\b\"", CredentialFill.jsLiteral("a\\b"))
    }

    @Test
    fun `a carriage return is escaped`() {
        // A raw CR inside a JS string literal is a SyntaxError, which would take the whole script
        // down and surface as "no login box could be filled" - a fault indistinguishable from a
        // page that genuinely has no form.
        assertEquals("\"a\\rb\"", CredentialFill.jsLiteral("a\rb"))
    }

    @Test
    fun `javascript line separators are escaped`() {
        assertEquals("\"a\\u2028b\"", CredentialFill.jsLiteral("a\u2028b"))
        assertEquals("\"a\\u2029b\"", CredentialFill.jsLiteral("a\u2029b"))
    }

    @Test
    fun `an ordinary credential is quoted and otherwise untouched`() {
        assertEquals("\"p4ssw0rd!#-_\"", CredentialFill.jsLiteral("p4ssw0rd!#-_"))
    }

    // ---------------------------------------------------------------------- script

    @Test
    fun `the script carries the credential and the target, and shares the eligibility rules`() {
        val script = CredentialFill.script("me@example.com", "s3cret", targetIndex = 2)
        assertTrue(script.contains("var USERNAME = \"me@example.com\";"), script.take(400))
        assertTrue(script.contains("var PASSWORD = \"s3cret\";"))
        assertTrue(script.contains("var TARGET = 2;"))
        // One copy of the eligibility rules, shared with the focus probe: offering a credential for
        // one box and filling a different one is the whole bug being fixed.
        assertTrue(script.contains("function isLoginField(el)"), "eligibility helpers not inlined")
        assertTrue(script.contains("getClientRects"), "visibility test not inlined")
    }

    @Test
    fun `no target index becomes a sentinel the script treats as unset`() {
        val script = CredentialFill.script("u", "p", targetIndex = null)
        assertTrue(script.contains("var TARGET = -1;"), "null target must not become field 0")
    }

    @Test
    fun `a credential with a quote in it cannot break the assignment`() {
        val script = CredentialFill.script("a\"b", "c\\d", targetIndex = null)
        assertTrue(script.contains("var USERNAME = \"a\\\"b\";"))
        assertTrue(script.contains("var PASSWORD = \"c\\\\d\";"))
    }

    // ---------------------------------------------------- the generated-password fill

    @Test
    fun `the new-password result reports what the field ended up holding`() {
        val result =
            CredentialFill.parseNewPasswordResult(
                """{"target":"filled","confirm":"filled","landed":"abc123","username":"dev@example.com"}""",
            )
        assertEquals(CredentialFill.FieldOutcome.FILLED, result.target)
        assertEquals(CredentialFill.FieldOutcome.FILLED, result.confirm)
        assertEquals("abc123", result.landed)
        assertEquals("dev@example.com", result.username)
        assertTrue(result.filled)
    }

    @Test
    fun `a truncated value is reported as landed, not as the value that was sent`() {
        // The whole reason this result carries `landed`. A field with maxlength=6 silently keeps the
        // first six characters, and saving the original would put a password in Secret Manager that
        // has never worked on the account.
        val result = CredentialFill.parseNewPasswordResult("""{"target":"filled","landed":"abc123"}""")
        assertEquals("abc123", result.landed)
    }

    @Test
    fun `a form with no confirm box is not a failure`() {
        val result =
            CredentialFill.parseNewPasswordResult("""{"target":"filled","confirm":"absent","landed":"x"}""")
        assertEquals(CredentialFill.FieldOutcome.ABSENT, result.confirm)
        assertTrue(result.filled)
    }

    @Test
    fun `a fill reporting success with an empty field is not a success`() {
        // A site that rejects the value outright can leave the box empty while the assignment threw
        // nothing. Saving that would store an empty password.
        val result = CredentialFill.parseNewPasswordResult("""{"target":"filled","landed":""}""")
        assertFalse(result.filled)
    }

    @Test
    fun `a page that could not be asked is unknown, not filled`() {
        val result = CredentialFill.parseNewPasswordResult(null)
        assertEquals(CredentialFill.FieldOutcome.UNKNOWN, result.target)
        assertFalse(result.filled)
        assertNull(result.landed)
    }

    @Test
    fun `an unreadable answer is unknown rather than an exception`() {
        assertEquals(
            CredentialFill.FieldOutcome.UNKNOWN,
            CredentialFill.parseNewPasswordResult("{not json").target,
        )
        assertEquals(CredentialFill.FieldOutcome.UNKNOWN, CredentialFill.parseNewPasswordResult("").target)
    }

    @Test
    fun `a generated password is interpolated as an escaped literal`() {
        // Same requirement as the credential fill: this is spliced into JavaScript source, and an
        // unescaped quote or line separator is a SyntaxError that reads as "the page has no form".
        val script = CredentialFill.newPasswordScript("a\"b\\c\u2028d")
        assertFalse(script.contains("\"a\"b"), "raw quote survived into the script")
        assertTrue(script.contains("\\u2028"), "U+2028 was not escaped")
    }

    /**
     * The script with its `//` comments removed.
     *
     * Needed because the generated fill *explains* in a comment that it deliberately does not set
     * the marker, so a raw text search finds the explanation and reads it as the offence. The same
     * trap caught the host's InjectJsCallback guard.
     */
    private fun code(script: String): String =
        script.lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the generated fill does not mark the field as coming from a saved secret`() {
        // data-boss-filled means "this came from Secret Manager", and the save policy uses it to
        // stay quiet. A generated password is the opposite case: it is being stored right now, and
        // marking it would make the very first save look like a no-op.
        assertFalse(
            code(CredentialFill.newPasswordScript("x")).contains("data-boss-filled"),
            "the generated fill sets the saved-secret marker",
        )
        assertTrue(
            code(CredentialFill.script("u", "p")).contains("data-boss-filled"),
            "the credential fill stopped setting the marker the policy depends on",
        )
    }
}
