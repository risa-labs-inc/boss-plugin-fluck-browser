package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.SecretEntryData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        password: String = "unused",
        tags: List<String> = emptyList(),
    ) = SecretEntryData(
        id = website + "|" + username,
        website = website,
        username = username,
        password = password,
        tags = tags,
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

    // ---------------------------------------------------------- automaticFillCandidate

    @Test
    fun `one matching credential is safe to auto fill`() {
        val only = secret("example.com", "me@example.com")
        assertEquals(only, automaticFillCandidate("https://example.com/login", listOf(only)))
    }

    @Test
    fun `multiple matching accounts require a human choice`() {
        val secrets =
            listOf(
                secret("example.com", "personal@example.com"),
                secret("https://example.com/account", "work@example.com"),
            )
        assertNull(automaticFillCandidate("https://example.com/login", secrets))
    }

    @Test
    fun `a blank password is not auto filled`() {
        val unusable = secret("example.com", "me@example.com", password = "")
        assertNull(automaticFillCandidate("https://example.com/login", listOf(unusable)))
    }

    @Test
    fun `automatic fill never crosses to a sibling host`() {
        val account = secret("accounts.example.com", "me@example.com")
        assertNull(automaticFillCandidate("https://app.example.com/login", listOf(account)))
        // The broader manual matching policy is deliberately unchanged.
        assertEquals(account, matchSecretsForDomain("example.com", listOf(account)).single())
    }

    @Test
    fun `a captured subdomain origin can auto fill only that same subdomain`() {
        val capturedWebsite = assertNotNull(credentialPageOrigin("https://accounts.example.com/login"))
        val stored = secret(capturedWebsite, "me@example.com")

        assertEquals(
            stored,
            automaticFillCandidate("https://accounts.example.com/challenge", listOf(stored)),
        )
        assertNull(automaticFillCandidate("https://app.example.com/login", listOf(stored)))
    }

    @Test
    fun `ordinary http pages cannot receive an automatic credential`() {
        val account = secret("https://example.com", "me@example.com")
        assertNull(automaticFillCandidate("http://example.com/login", listOf(account)))
    }

    @Test
    fun `userinfo URLs are not automatic credential origins`() {
        val account = secret("https://user@example.com", "me@example.com")
        assertNull(automaticFillCandidate("https://example.com/login", listOf(account)))
        assertNull(automaticFillOrigin("https://user@example.com/login"))
    }

    @Test
    fun `an explicit secret origin respects scheme and effective port`() {
        val defaultHttps = secret("https://example.com/account", "default@example.com")
        val alternatePort = secret("https://example.com:8443/account", "alternate@example.com")

        assertEquals("https://example.com", automaticFillOrigin("https://example.com:443/login"))
        assertEquals("https://example.com:8443", automaticFillOrigin("https://example.com:8443/login"))
        assertEquals(
            defaultHttps,
            automaticFillCandidate("https://example.com:443/login", listOf(defaultHttps)),
        )
        assertNull(automaticFillCandidate("https://example.com:8443/login", listOf(defaultHttps)))
        assertEquals(
            alternatePort,
            automaticFillCandidate("https://example.com:8443/login", listOf(alternatePort)),
        )
        assertNull(
            automaticFillCandidate(
                "http://localhost:3000/login",
                listOf(secret("https://localhost:3000", "local@example.com")),
            ),
        )
    }

    @Test
    fun `explicit http is allowed only for the same loopback origin`() {
        val local = secret("http://127.0.0.1:3000/account", "local@example.com")
        assertEquals(
            local,
            automaticFillCandidate("http://127.0.0.1:3000/login", listOf(local)),
        )
        assertNull(automaticFillCandidate("http://127.0.0.1:4000/login", listOf(local)))
    }

    @Test
    fun `ineligible rows cannot hide a second usable account`() {
        val first = secret("example.com", "first@example.com")
        val blank = secret("example.com", "", password = "")
        val second = secret("example.com", "second@example.com")

        assertNull(
            automaticFillCandidate(
                "https://example.com/login",
                listOf(first, blank, second),
            ),
        )
    }

    @Test
    fun `api key and ai provider entries are never automatic login candidates`() {
        val apiKey = secret("example.com", "OPENAI_API_KEY", tags = listOf("api_key"))
        val provider = secret("example.com", "provider", tags = listOf("AI-PROVIDER"))

        assertNull(automaticFillCandidate("https://example.com/login", listOf(apiKey)))
        assertNull(automaticFillCandidate("https://example.com/login", listOf(provider)))
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

    // ---------------------------------------------------------- secretMatchesDomain

    @Test
    fun `a non-domain label never matches, which is what the dialog badge got wrong`() {
        // The "Select Secret to Fill" dialog hand-inlined the old substring rule with a `?: ""`
        // fallback, and `contains("")` is always true - so every secret whose website does not
        // parse as a domain was badged "matches current website". That dialog is where the
        // suggestion list's "Other logins..." row sends the user.
        assertFalse(secretMatchesDomain("google.com", secret("GOOGLE", "GEMINI_API_KEY")))
        assertFalse(secretMatchesDomain("google.com", secret("android")))
        assertFalse(secretMatchesDomain("google.com", secret("risa-labs-inc/BossConsole - Actions secret")))
    }

    @Test
    fun `the badge and the list cannot disagree`() {
        // One rule, three call sites. Anything matchSecretsForDomain returns must also badge.
        val secrets =
            listOf(secret("google.com", "me@gmail.com"), secret("GOOGLE", "KEY"), secret("github.com"))
        val listed = matchSecretsForDomain("google.com", secrets)
        secrets.forEach { s ->
            assertEquals(
                listed.contains(s),
                secretMatchesDomain("google.com", s),
                "disagreement on ${s.website}",
            )
        }
    }

    @Test
    fun `tenants of a shared host are not each other`() {
        // Without the suffix entries both sides reduce to "vercel.app", so a credential saved on
        // one person's deploy would be offered beside a login box on anyone else's.
        assertFalse(secretMatchesDomain("evil.vercel.app", secret("myapp.vercel.app")))
        assertFalse(secretMatchesDomain("attacker.github.io", secret("alice.github.io")))
        assertFalse(secretMatchesDomain("b.herokuapp.com", secret("a.herokuapp.com")))
        // The same tenant still matches itself.
        assertTrue(secretMatchesDomain("myapp.vercel.app", secret("myapp.vercel.app")))
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
