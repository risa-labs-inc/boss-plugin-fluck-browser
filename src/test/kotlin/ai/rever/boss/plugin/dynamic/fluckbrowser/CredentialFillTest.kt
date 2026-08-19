package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.dynamic.fluckbrowser.CredentialFill.FieldOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
