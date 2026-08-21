package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The pure half of the inline credential suggestion: reading the page probe's answer, and how fast
 * to ask again.
 *
 * The probe script itself is JavaScript and only means anything against a real document, so it is
 * verified by running it on real login pages. What is pinned here is everything around it -
 * particularly that an answer this code cannot understand costs the *slowest* poll rate rather
 * than the fastest, since each probe is a blocking round-trip into Chromium - dispatched off the
 * UI thread, but still a thread and an IPC per poll.
 */
class CredentialSuggestionsTest {
    private val focusedJson =
        """
        {"key":"0|identifier|identifierId|text","isPassword":false,
         "pageUrl":"https://accounts.google.com/v3/signin/identifier",
         "hasValue":false,"left":40.5,"top":220.0,"width":368.0,"height":48.0}
        """.trimIndent()

    @Test
    fun `a page with no login field is read as none`() {
        assertEquals(LoginFieldProbe.NoLoginField, parseLoginFieldProbe("NONE"))
    }

    @Test
    fun `a login field nobody is in is read as idle`() {
        assertEquals(LoginFieldProbe.Idle, parseLoginFieldProbe("IDLE"))
    }

    @Test
    fun `a focused field carries its geometry and identity`() {
        val probe = parseLoginFieldProbe(focusedJson)
        assertTrue(probe is LoginFieldProbe.Focused, "got $probe")
        assertEquals("0|identifier|identifierId|text", probe.field.key)
        assertEquals(false, probe.field.isPassword)
        assertEquals(false, probe.field.hasValue)
        assertEquals(40.5, probe.field.left)
        assertEquals(220.0, probe.field.top)
        assertEquals(368.0, probe.field.width)
        assertEquals(48.0, probe.field.height)
        assertTrue(probe.field.pageUrl.startsWith("https://accounts.google.com/"))
    }

    @Test
    fun `a dismissal is remembered against the page, not just the field`() {
        // "0|username||text" is the first login box on a great many sites, so a dismissal keyed on
        // the field alone would carry to the next site and hide the list there.
        val here = parseLoginFieldProbe(focusedJson) as LoginFieldProbe.Focused
        val elsewhere =
            parseLoginFieldProbe(
                focusedJson.replace("https://accounts.google.com/v3/signin/identifier", "https://github.com/login"),
            ) as LoginFieldProbe.Focused
        assertTrue(here.field.dismissId != elsewhere.field.dismissId, "same id across two sites")
        assertTrue(here.field.dismissId.contains(here.field.key), "the field is still part of it")
    }

    @Test
    fun `a field that already has something in it says so`() {
        val probe = parseLoginFieldProbe(focusedJson.replace("\"hasValue\":false", "\"hasValue\":true"))
        assertTrue(probe is LoginFieldProbe.Focused, "got $probe")
        assertTrue(probe.field.hasValue)
    }

    @Test
    fun `an unknown key does not throw away the answer`() {
        // The probe and this parser ship in one jar today, but a page could in principle answer a
        // longer object; losing the whole reading over a field nobody asked for would be worse
        // than ignoring it.
        val probe = parseLoginFieldProbe(focusedJson.dropLast(1) + ",\"somethingNew\":1}")
        assertTrue(probe is LoginFieldProbe.Focused, "got $probe")
    }

    @Test
    fun `no answer at all is treated as no login field`() {
        // executeJavaScript answers null when the frame has gone, and onBrowser answers null when
        // the call threw. Showing nothing is the right failure for both.
        assertEquals(LoginFieldProbe.NoLoginField, parseLoginFieldProbe(null))
        assertEquals(LoginFieldProbe.NoLoginField, parseLoginFieldProbe(""))
        assertEquals(LoginFieldProbe.NoLoginField, parseLoginFieldProbe("   "))
    }

    @Test
    fun `an answer this code cannot read is not an error to retry quickly`() {
        assertEquals(LoginFieldProbe.NoLoginField, parseLoginFieldProbe("{not json"))
        assertEquals(LoginFieldProbe.NoLoginField, parseLoginFieldProbe("""{"key":"a"}"""))
    }

    @Test
    fun `the quick poll rate is reachable only while a login box is focused`() {
        val focused = parseLoginFieldProbe(focusedJson)
        val quickest = loginProbeDelayMs(focused)
        assertTrue(quickest < loginProbeDelayMs(LoginFieldProbe.Idle), "focused should poll fastest")
        assertTrue(
            loginProbeDelayMs(LoginFieldProbe.Idle) < loginProbeDelayMs(LoginFieldProbe.NoLoginField),
            "a page with no login box should poll slowest",
        )
    }

    @Test
    fun `even the quickest rate stays clear of a per-frame poll`() {
        // The lower bound. The call is dispatched to Dispatchers.IO so it no longer blocks the
        // EDT, but it still costs a thread and an IPC per poll, so the fastest rate has to stay
        // well clear of frame time.
        assertTrue(
            loginProbeDelayMs(parseLoginFieldProbe(focusedJson)) >= 200L,
            "the focused rate must not approach per-frame polling",
        )
    }

    // ------------------------------------------------------- shouldOfferSuggestions

    private fun field(hasValue: Boolean = false) =
        (parseLoginFieldProbe(if (hasValue) focusedJson.replace("\"hasValue\":false", "\"hasValue\":true") else focusedJson)
            as LoginFieldProbe.Focused).field

    @Test
    fun `the list is offered for an empty focused box with matches`() {
        assertTrue(shouldOfferSuggestions(field(), dismissedId = null, matchCount = 2))
    }

    @Test
    fun `nothing is offered when no box is focused`() {
        assertFalse(shouldOfferSuggestions(null, dismissedId = null, matchCount = 2))
    }

    @Test
    fun `a dismissal for this box on this page is respected`() {
        val f = field()
        assertFalse(shouldOfferSuggestions(f, dismissedId = f.dismissId, matchCount = 2))
        // ...but a dismissal made elsewhere is not this box.
        assertTrue(shouldOfferSuggestions(f, dismissedId = "https://other.example/#0|u||text", matchCount = 2))
    }

    @Test
    fun `a box with something already typed is left alone`() {
        // Also what closes the list after a successful fill.
        assertFalse(shouldOfferSuggestions(field(hasValue = true), dismissedId = null, matchCount = 2))
    }

    @Test
    fun `no matching credential means no list`() {
        assertFalse(shouldOfferSuggestions(field(), dismissedId = null, matchCount = 0))
    }

    // ------------------------------------------------------------ result normalisation

    @Test
    fun `a non-String result is normalised rather than dropped`() {
        // executeJavaScript returns Any?. A wrapper type or a quoted string used to collapse to
        // NoLoginField, so the feature would silently never appear.
        assertEquals(LoginFieldProbe.Idle, parseLoginFieldProbe(StringBuilder("IDLE")))
        assertEquals(LoginFieldProbe.Idle, parseLoginFieldProbe("\"IDLE\""))
        assertEquals(LoginFieldProbe.NoLoginField, parseLoginFieldProbe(StringBuilder("NONE")))
    }

    @Test
    fun `an unreadable page costs the slowest rate, not the fastest`() {
        // The pairing that matters: a page answering garbage forever must not be polled at the
        // focused rate. Reading "unparseable" as anything but NoLoginField would do exactly that.
        assertEquals(
            loginProbeDelayMs(LoginFieldProbe.NoLoginField),
            loginProbeDelayMs(parseLoginFieldProbe("{not json")),
        )
    }

    // -------------------------------------------------- the generated-password offer

    /**
     * A probe answer from BEFORE isNewPassword / maxLength / pattern existed.
     *
     * The plugin and the script always ship together, so this is not about a version skew - it is
     * about the decoder. These fields are defaulted, and a strict decode would make one older-shaped
     * answer collapse the whole thing to NoLoginField, which is a feature that silently never
     * appears rather than an error anyone sees.
     */
    @Test
    fun `a probe answer without the newer fields still parses`() {
        val probe = parseLoginFieldProbe(focusedJson)
        val field = assertIs<LoginFieldProbe.Focused>(probe).field
        assertFalse(field.isNewPassword)
        assertEquals(-1, field.maxLength)
        assertNull(field.pattern)
    }

    @Test
    fun `a new-password field carries what the generator needs`() {
        val json =
            """
            {"key":"1|new_password|pw|password","isPassword":true,"isNewPassword":true,
             "maxLength":16,"pattern":"[A-Za-z0-9]+",
             "pageUrl":"https://example.com/signup","hasValue":false,
             "left":10.0,"top":20.0,"width":200.0,"height":30.0}
            """.trimIndent()
        val field = assertIs<LoginFieldProbe.Focused>(parseLoginFieldProbe(json)).field
        assertTrue(field.isNewPassword)
        assertEquals(16, field.maxLength)
        assertEquals("[A-Za-z0-9]+", field.pattern)
    }

    private fun newPasswordField(
        maxLength: Int = -1,
        hasValue: Boolean = false,
        isNewPassword: Boolean = true,
        isPassword: Boolean = true,
    ) = FocusedLoginField(
        key = "1|password|pw|password",
        isPassword = isPassword,
        isNewPassword = isNewPassword,
        maxLength = maxLength,
        pageUrl = "https://example.com/signup",
        hasValue = hasValue,
        left = 0.0,
        top = 0.0,
        width = 200.0,
        height = 30.0,
    )

    @Test
    fun `a generated password is offered on an empty new-password box`() {
        assertTrue(shouldOfferGeneratedPassword(newPasswordField(), dismissedId = null, enabled = true))
    }

    @Test
    fun `nothing is offered when the setting is off`() {
        assertFalse(shouldOfferGeneratedPassword(newPasswordField(), dismissedId = null, enabled = false))
    }

    @Test
    fun `an ordinary sign-in password box gets no suggestion`() {
        // Offering to replace the password of an account that already exists is the one thing this
        // must never do on a login form.
        assertFalse(
            shouldOfferGeneratedPassword(
                newPasswordField(isNewPassword = false),
                dismissedId = null,
                enabled = true,
            ),
        )
    }

    @Test
    fun `a box the user has started typing in is left alone`() {
        assertFalse(
            shouldOfferGeneratedPassword(newPasswordField(hasValue = true), dismissedId = null, enabled = true),
        )
    }

    @Test
    fun `a field too short for a decent password gets no offer`() {
        assertFalse(
            shouldOfferGeneratedPassword(newPasswordField(maxLength = 8), dismissedId = null, enabled = true),
        )
    }

    @Test
    fun `a dismissal is respected, and the menu is the way back`() {
        val f = newPasswordField()
        assertFalse(shouldOfferGeneratedPassword(f, dismissedId = f.dismissId, enabled = true))
        // Without the forced path, waving the card away makes that field a dead end: the automatic
        // offer stays suppressed for exactly the box the user then wants help with.
        assertTrue(shouldOfferGeneratedPassword(f, dismissedId = f.dismissId, enabled = true, forced = true))
    }

    @Test
    fun `an explicit request needs no new-password heuristic, but still needs a password box`() {
        assertTrue(
            shouldOfferGeneratedPassword(
                newPasswordField(isNewPassword = false),
                dismissedId = null,
                enabled = true,
                forced = true,
            ),
        )
        // The menu item can never put a generated password into a username field.
        assertFalse(
            shouldOfferGeneratedPassword(
                newPasswordField(isPassword = false, isNewPassword = false),
                dismissedId = null,
                enabled = true,
                forced = true,
            ),
        )
    }

    @Test
    fun `an explicit request still respects the setting and a filled box`() {
        assertFalse(shouldOfferGeneratedPassword(newPasswordField(), null, enabled = false, forced = true))
        assertFalse(shouldOfferGeneratedPassword(newPasswordField(hasValue = true), null, true, forced = true))
    }
}
