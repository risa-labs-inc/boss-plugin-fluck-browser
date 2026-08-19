package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.SecretEntryData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which saved logins are offered for the page you are on.
 *
 * The rule used to be a two-way substring test, so every short website label acted as a wildcard
 * over every domain containing it. The list this produces is the credential picker in the
 * right-click menu and the inline suggestion beside a login box, so a wrong entry there is an
 * invitation to type an API key into a password field.
 */
class SecretDomainMatchTest {
    private fun secret(
        website: String,
        username: String = "someone@example.com",
    ) = SecretEntryData(
        id = website + "|" + username,
        website = website,
        username = username,
        password = "unused",
        createdAt = "",
        updatedAt = "",
    )

    // ------------------------------------------------------------- secretWebsiteDomain

    @Test
    fun `a bare authority resolves even though URI reports no host for it`() {
        // java.net.URI("google.com") parses that as a path, not a host, so the bare form - which
        // is how most of these are actually stored - has to be retried with a scheme.
        assertEquals("google.com", secretWebsiteDomain("google.com"))
        assertEquals("google.com", secretWebsiteDomain("accounts.google.com"))
    }

    @Test
    fun `a full url resolves to its registrable domain`() {
        assertEquals("google.com", secretWebsiteDomain("https://mail.google.com/mail/u/0/#inbox"))
        assertEquals("github.com", secretWebsiteDomain("https://github.com/risa-labs-inc"))
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertEquals("github.com", secretWebsiteDomain("  GitHub.COM  "))
    }

    @Test
    fun `a label with no dot in it is not a domain`() {
        // The entries this exists for: an API key filed under "GOOGLE", and the "android" ones.
        assertNull(secretWebsiteDomain("GOOGLE"))
        assertNull(secretWebsiteDomain("android"))
        assertNull(secretWebsiteDomain(""))
        assertNull(secretWebsiteDomain("   "))
    }

    @Test
    fun `localhost is the one dotless host that is still a host`() {
        // extractMainDomain already treats it as a host in its own right, so a secret saved for
        // localhost has to resolve the same way or it could never match the page it was saved on.
        assertEquals("localhost", secretWebsiteDomain("localhost"))
        assertEquals("localhost", secretWebsiteDomain("http://localhost:3000/login"))
    }

    @Test
    fun `free text that is not a website does not resolve to one`() {
        assertNull(secretWebsiteDomain("risa-labs-inc/BossConsole - GitHub Actions secret"))
    }

    // ---------------------------------------------------------- matchSecretsForDomain

    @Test
    fun `an api key filed under a bare product name is not offered as a login`() {
        // The regression. "google.com".contains("google") was true, so the Gemini API key showed
        // up in the credential list on Google's sign-in page.
        val secrets = listOf(secret("GOOGLE", "GEMINI_API_KEY"), secret("google.com", "me@gmail.com"))
        val matched = matchSecretsForDomain("google.com", secrets)
        assertEquals(listOf("me@gmail.com"), matched.map { it.username })
    }

    @Test
    fun `a secret saved for a subdomain is offered on the registrable domain`() {
        val matched = matchSecretsForDomain("google.com", listOf(secret("accounts.google.com")))
        assertEquals(1, matched.size)
    }

    @Test
    fun `a secret saved for the registrable domain is offered on a subdomain page`() {
        // The page URL is reduced by extractMainDomain before it gets here, so this is the
        // relationship that survives that reduction.
        val matched = matchSecretsForDomain("google.com", listOf(secret("google.com")))
        assertEquals(1, matched.size)
    }

    @Test
    fun `a domain that merely ends with the same letters is not a match`() {
        val secrets = listOf(secret("notgoogle.com"), secret("googlecom.net"))
        assertTrue(matchSecretsForDomain("google.com", secrets).isEmpty())
    }

    @Test
    fun `an unrelated site is not offered`() {
        val secrets = listOf(secret("github.com"), secret("linkedin.com"), secret("ac.in"))
        assertTrue(matchSecretsForDomain("google.com", secrets).isEmpty())
    }

    @Test
    fun `localhost matches localhost and nothing else`() {
        val secrets = listOf(secret("localhost"), secret("google.com"))
        assertEquals(1, matchSecretsForDomain("localhost", secrets).size)
        assertTrue(matchSecretsForDomain("localhost", listOf(secret("google.com"))).isEmpty())
    }

    @Test
    fun `an empty page domain matches nothing rather than everything`() {
        // "".endsWith(x) is false but x.endsWith("." + "") would have been a suffix test against
        // a bare dot, so this is worth pinning rather than assuming.
        assertTrue(matchSecretsForDomain("", listOf(secret("google.com"))).isEmpty())
        assertTrue(matchSecretsForDomain("  ", listOf(secret("google.com"))).isEmpty())
    }

    @Test
    fun `the list is capped so it cannot cover the page it is anchored to`() {
        val secrets = (1..9).map { secret("google.com", "user$it@gmail.com") }
        assertEquals(5, matchSecretsForDomain("google.com", secrets).size)
        assertEquals(2, matchSecretsForDomain("google.com", secrets, maxResults = 2).size)
    }

    @Test
    fun `order follows the secrets list so the cap is not arbitrary`() {
        val secrets = (1..7).map { secret("google.com", "user$it@gmail.com") }
        assertEquals(
            listOf("user1@gmail.com", "user2@gmail.com", "user3@gmail.com", "user4@gmail.com", "user5@gmail.com"),
            matchSecretsForDomain("google.com", secrets).map { it.username },
        )
    }
}
