package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.SecretEntryData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Whether to put a save prompt in front of someone, and what it should say.
 *
 * Every branch here is a judgement about interrupting the user. The bias is toward silence, because
 * a prompt that fires when it should not is worse than one that never fires: a missed prompt costs
 * one trip to Secret Manager, while a wrong one teaches the user to dismiss the bar unread, which
 * costs every later prompt as well.
 */
class CredentialSavePolicyTest {
    private fun secret(
        website: String,
        username: String,
        password: String,
        id: String = "$website|$username",
    ) = SecretEntryData(
        id = id,
        website = website,
        username = username,
        password = password,
        createdAt = "",
        updatedAt = "",
    )

    private fun pending(
        domain: String = "github.com",
        username: String = "dev@example.com",
        password: String = "typed-password",
        wasFilledByBoss: Boolean = false,
        capturedAtMs: Long = 1_000L,
    ) = CredentialSavePolicy.Pending(domain, username, password, wasFilledByBoss, capturedAtMs)

    // ---------------------------------------------------------------------- outcome

    @Test
    fun `a login form still on the page has not resolved`() {
        // The wrong-password case. The form re-renders with the box still there, so anything other
        // than WAITING here would prompt to save a credential that just failed.
        assertEquals(
            CredentialSavePolicy.Outcome.WAITING,
            CredentialSavePolicy.outcome(pending(), LoginFieldProbe.Idle, nowMs = 2_000L),
        )
    }

    @Test
    fun `a focused login field has not resolved either`() {
        val field =
            FocusedLoginField(
                key = "0|password||password",
                isPassword = true,
                pageUrl = "https://github.com/login",
                hasValue = true,
                left = 0.0,
                top = 0.0,
                width = 100.0,
                height = 20.0,
            )
        assertEquals(
            CredentialSavePolicy.Outcome.WAITING,
            CredentialSavePolicy.outcome(pending(), LoginFieldProbe.Focused(field), nowMs = 2_000L),
        )
    }

    @Test
    fun `the login form being gone is what success looks like`() {
        // Deliberately NOT keyed on the URL changing. A URL change fires for a failed login too
        // (?error=1), and for the second screen of a two-step sign-in, which is another login form.
        // It also never fires at all for a single-page login, which this reading handles correctly.
        assertEquals(
            CredentialSavePolicy.Outcome.SUCCEEDED,
            CredentialSavePolicy.outcome(pending(), LoginFieldProbe.NoLoginField, nowMs = 2_000L),
        )
    }

    @Test
    fun `a capture nobody resolved expires instead of waiting forever`() {
        assertEquals(
            CredentialSavePolicy.Outcome.EXPIRED,
            CredentialSavePolicy.outcome(
                pending(capturedAtMs = 0L),
                LoginFieldProbe.Idle,
                nowMs = CredentialSavePolicy.PENDING_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `expiry beats success, so a prompt can never arrive long after the fact`() {
        assertEquals(
            CredentialSavePolicy.Outcome.EXPIRED,
            CredentialSavePolicy.outcome(
                pending(capturedAtMs = 0L),
                LoginFieldProbe.NoLoginField,
                nowMs = CredentialSavePolicy.PENDING_WINDOW_MS + 1,
            ),
        )
    }

    // ----------------------------------------------------------------------- decide

    @Test
    fun `a credential for a site with nothing saved is offered as a new secret`() {
        val decision = CredentialSavePolicy.decide(pending(), emptyList())
        val save = assertIs<CredentialSavePolicy.Decision.Save>(decision)
        assertEquals("github.com", save.domain)
        assertEquals("dev@example.com", save.username)
        assertEquals("typed-password", save.password)
    }

    @Test
    fun `the same credential already stored says nothing at all`() {
        // The common case for a site the user signs into daily. Getting this wrong means a prompt
        // on every single login, which is how a feature gets switched off.
        val stored = listOf(secret("github.com", "dev@example.com", "typed-password"))
        assertEquals(CredentialSavePolicy.Decision.Ignore, CredentialSavePolicy.decide(pending(), stored))
    }

    @Test
    fun `a changed password on a known account is an update, not a duplicate`() {
        val stored = listOf(secret("github.com", "dev@example.com", "the-old-one"))
        val update = assertIs<CredentialSavePolicy.Decision.Update>(CredentialSavePolicy.decide(pending(), stored))
        assertEquals("github.com|dev@example.com", update.secret.id)
        assertEquals("typed-password", update.password)
    }

    @Test
    fun `a second account on a known site is a new secret`() {
        val stored = listOf(secret("github.com", "someone-else@example.com", "their-password"))
        val save = assertIs<CredentialSavePolicy.Decision.Save>(CredentialSavePolicy.decide(pending(), stored))
        assertEquals("dev@example.com", save.username)
    }

    @Test
    fun `usernames match case-insensitively`() {
        // Every provider anyone uses treats the local part case-insensitively, whatever the RFC
        // says. Treating these as two accounts would offer to Save a duplicate of a row that is
        // already there.
        val stored = listOf(secret("github.com", "Dev@Example.com", "the-old-one"))
        assertIs<CredentialSavePolicy.Decision.Update>(CredentialSavePolicy.decide(pending(), stored))
    }

    @Test
    fun `a blank password is never offered`() {
        assertEquals(
            CredentialSavePolicy.Decision.Ignore,
            CredentialSavePolicy.decide(pending(password = ""), emptyList()),
        )
    }

    // --------------------------------------------------- the filled-by-us marker

    @Test
    fun `a credential this plugin filled and nobody changed says nothing`() {
        val stored = listOf(secret("github.com", "reformatted@example.com", "typed-password"))
        assertEquals(
            CredentialSavePolicy.Decision.Ignore,
            CredentialSavePolicy.decide(pending(wasFilledByBoss = true), stored),
        )
    }

    @Test
    fun `a filled credential the user then EDITED is still offered`() {
        // The trap the marker creates. The fill sets data-boss-filled and nothing ever clears it,
        // so a field the user filled from a secret and then corrected by hand still carries the
        // flag. Trusting the flag alone would silently refuse to save exactly the change the user
        // just made - which is the single most valuable thing this feature can catch.
        val stored = listOf(secret("github.com", "dev@example.com", "the-old-one"))
        val decision = CredentialSavePolicy.decide(pending(wasFilledByBoss = true), stored)
        val update = assertIs<CredentialSavePolicy.Decision.Update>(decision)
        assertEquals("typed-password", update.password)
    }

    // ------------------------------------------------- two-step sign-in, no username

    @Test
    fun `a password-only submission with one saved login updates that login`() {
        // The second screen of a two-step sign-in: the identifier was on the previous page and is
        // not in this document at all. One saved login for the site is unambiguous.
        val stored = listOf(secret("google.com", "dev@example.com", "the-old-one"))
        val decision = CredentialSavePolicy.decide(pending(domain = "google.com", username = ""), stored)
        val update = assertIs<CredentialSavePolicy.Decision.Update>(decision)
        assertEquals("dev@example.com", update.secret.username)
    }

    @Test
    fun `a password-only submission with several saved logins asks rather than guessing`() {
        // Picking one would overwrite the wrong account's password. Save with a blank username is
        // what makes the bar ask for it.
        val stored =
            listOf(
                secret("google.com", "one@example.com", "first"),
                secret("google.com", "two@example.com", "second"),
            )
        val decision = CredentialSavePolicy.decide(pending(domain = "google.com", username = ""), stored)
        val save = assertIs<CredentialSavePolicy.Decision.Save>(decision)
        assertEquals("", save.username)
    }

    @Test
    fun `a password-only submission on a site with nothing saved asks for the username`() {
        val decision = CredentialSavePolicy.decide(pending(username = ""), emptyList())
        val save = assertIs<CredentialSavePolicy.Decision.Save>(decision)
        assertEquals("", save.username)
        assertEquals("typed-password", save.password)
    }

    @Test
    fun `a password-only submission matching a stored password says nothing`() {
        // Filled by us on a two-step flow, or simply signed in again with the same password.
        val stored = listOf(secret("google.com", "dev@example.com", "typed-password"))
        assertEquals(
            CredentialSavePolicy.Decision.Ignore,
            CredentialSavePolicy.decide(
                pending(domain = "google.com", username = "", wasFilledByBoss = true),
                stored,
            ),
        )
    }

    // --------------------------------------------------------------- domain scoping

    @Test
    fun `a secret for another site is not treated as a match`() {
        // The bug matchSecretsForDomain was fixed for once already: a two-way `contains` made a
        // secret named GOOGLE (an API key) an offer on google.com.
        val stored = listOf(secret("gitlab.com", "dev@example.com", "the-old-one"))
        assertIs<CredentialSavePolicy.Decision.Save>(CredentialSavePolicy.decide(pending(), stored))
    }
}
