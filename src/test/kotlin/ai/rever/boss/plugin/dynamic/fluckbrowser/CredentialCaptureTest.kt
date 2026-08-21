package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page-to-plugin boundary for a submitted credential.
 *
 * The bridge is a property on `window`, so any script on the page can call it with anything it
 * likes. These treat the input as hostile in the same way `BrowserInteractionBridgeTest` does in the
 * host: the answer to a malformed or absurd payload must always be "nothing happened", never an
 * exception and never a partially-trusted credential.
 *
 * The script itself is JavaScript and only means anything against a real document, so it is verified
 * by running it on real login pages. What is checked here is the parse, plus the structural claims
 * the script's own comments make about it - those are the parts that rot silently.
 */
class CredentialCaptureTest {
    @Test
    fun `a well-formed submission parses`() {
        val captured =
            CredentialCapture.parse("""{"username":"dev@example.com","password":"hunter2","filled":false}""")
        assertNotNull(captured)
        assertEquals("dev@example.com", captured.username)
        assertEquals("hunter2", captured.password)
        assertFalse(captured.wasFilledByBoss)
    }

    @Test
    fun `the filled marker is read`() {
        val captured = CredentialCapture.parse("""{"username":"a","password":"b","filled":true}""")
        assertTrue(assertNotNull(captured).wasFilledByBoss)
    }

    @Test
    fun `a password-only submission parses, because a two-step sign-in has no identifier`() {
        val captured = CredentialCapture.parse("""{"password":"hunter2"}""")
        assertEquals("", assertNotNull(captured).username)
    }

    @Test
    fun `nothing is read from an empty or absent payload`() {
        assertNull(CredentialCapture.parse(null))
        assertNull(CredentialCapture.parse(""))
        assertNull(CredentialCapture.parse("   "))
    }

    @Test
    fun `garbage is nothing, not an exception`() {
        assertNull(CredentialCapture.parse("not json"))
        assertNull(CredentialCapture.parse("{"))
        assertNull(CredentialCapture.parse("[]"))
        assertNull(CredentialCapture.parse("null"))
    }

    @Test
    fun `a submission with no password is not a submission`() {
        // The first screen of a two-step sign-in reaches here with an identifier and nothing else,
        // and there is nothing to offer to save.
        assertNull(CredentialCapture.parse("""{"username":"dev@example.com","password":""}"""))
        assertNull(CredentialCapture.parse("""{"username":"dev@example.com"}"""))
    }

    @Test
    fun `an absurdly long field is refused`() {
        // A pending capture is held in memory until the user answers the bar, so the size of what a
        // page can push into it is worth bounding.
        val huge = "x".repeat(CredentialCapture.MAX_FIELD_LENGTH + 1)
        assertNull(CredentialCapture.parse("""{"username":"a","password":"$huge"}"""))
        assertNull(CredentialCapture.parse("""{"username":"$huge","password":"b"}"""))
    }

    @Test
    fun `unknown keys are tolerated, so the script can grow without a coordinated release`() {
        val captured = CredentialCapture.parse("""{"password":"hunter2","somethingNew":42}""")
        assertEquals("hunter2", assertNotNull(captured).password)
    }

    @Test
    fun `a page-supplied origin is not accepted even when offered`() {
        // The payload carries no URL by design: the plugin stamps the domain from the engine's own
        // committed URL. If a `pageUrl` field ever appears in the payload shape, it means someone
        // has re-introduced a way for a page to claim a credential belongs to a domain it does not
        // own, so this fails on the SHAPE rather than on behaviour.
        val fields = CredentialCapture.CapturedCredential::class.members.map { it.name }
        assertFalse(fields.any { it.contains("url", ignoreCase = true) }, "payload gained a URL field: $fields")
        assertFalse(fields.any { it.contains("domain", ignoreCase = true) }, "payload gained a domain field: $fields")
    }

    // ------------------------------------------------------------------ the script

    /** The script with its comments removed, so structural checks read code and not prose. */
    private fun code(): String =
        CredentialCapture.INSTALL_JS
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the script reuses the shared field-eligibility rules`() {
        // Not a copy. The probe, the fill and this must agree on what a login field is; a page with
        // a display-none password decoy (accounts.google.com ships one) is why. Two copies of these
        // rules is how "offer for one box, read another" comes back.
        assertTrue(code().contains("function isLoginField"), "eligibility rules missing from the script")
        assertTrue(code().contains("getClientRects"), "the layout-box test is not in the script")
    }

    @Test
    fun `all three listeners are registered in the capture phase`() {
        // The third argument being true is what stops a page hiding its own submit by calling
        // stopPropagation. A listener registered without it looks identical and never fires on
        // those pages.
        val text = code().replace(Regex("\\s+"), " ")
        listOf("'submit'", "'keydown'", "'pointerdown'").forEach { event ->
            assertTrue(text.contains("addEventListener($event"), "no listener for $event")
        }
        assertEquals(
            3,
            Regex("""addEventListener\('[a-z]+', function.*?\}, true\)""").findAll(text).count(),
            "not all three listeners are in the capture phase: $text",
        )
    }

    @Test
    fun `the script emits through the bridge name the host installs`() {
        // The constant is `const val` in the api, so both sides compile the literal in. A mismatch
        // here would be a feature that silently never fires.
        assertTrue(code().contains("__bossPageEvent"), "the script does not name the bridge")
        assertTrue(code().contains(".emit("), "the script does not call emit on it")
    }

    @Test
    fun `the bridge is captured at document start, never read at post time`() {
        // The exfiltration hole this closes: window.__bossPageEvent is a public property, so a page
        // that replaces it between document start and the submit receives the credential itself.
        // Reading it once, before any page script runs, is the only version of this that is safe -
        // and the document-start guarantee is precisely what makes that possible.
        val text = code().replace(Regex("\\s+"), " ")
        assertTrue(
            text.contains("var post = window.__bossPageEvent"),
            "the bridge is not captured into a local at document start",
        )
        // The post must go through the captured local. Any `window.__bossPageEvent` inside a
        // listener is the bug coming back.
        val postSites = Regex("""(\w+)\.emit\(""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("post"),
            postSites,
            "something other than the captured reference posts to the bridge: $postSites",
        )
    }

    @Test
    fun `the property is removed from window, and before the install guard`() {
        // Deleting it closes forgery (no page script can post) and fingerprinting (no page can
        // detect BOSS by probing for it).
        //
        // Before the guard, not after, and that ordering is the load-bearing part: the host injects
        // at document start AND into the already-loaded document when the script is first installed,
        // so one document can see this script twice. The second pass returns early at the guard - if
        // the delete sat after it, that pass would leave a freshly re-added property on window for
        // the page to find.
        val text = code().replace(Regex("\\s+"), " ")
        val deleteAt = text.indexOf("delete window.__bossPageEvent")
        val guardAt = text.indexOf("if (window.__bossCredCaptureInstalled) return")
        assertTrue(deleteAt >= 0, "the script never removes the bridge property")
        assertTrue(guardAt >= 0, "the install guard is gone")
        assertTrue(
            deleteAt < guardAt,
            "the delete happens after the install guard, so a second injection leaves the property behind",
        )
    }

    @Test
    fun `installation is idempotent`() {
        // The host injects at document start and also into the already-loaded page when the script
        // is first installed, so a document can legitimately see it twice. Without the guard that
        // means two sets of listeners and two emits per submit.
        assertTrue(code().contains("__bossCredCaptureInstalled"), "no install guard")
    }

    @Test
    fun `the same credential is not emitted twice for one keystroke`() {
        // Enter fires keydown AND submit, and a form that posts via fetch can fire pointerdown too.
        assertTrue(code().contains("lastKey"), "no de-duplication between the three listeners")
    }
}
