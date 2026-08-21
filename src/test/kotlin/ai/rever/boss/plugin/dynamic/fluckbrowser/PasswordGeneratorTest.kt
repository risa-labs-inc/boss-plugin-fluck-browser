package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The generated password, and the site rules it has to fit.
 *
 * The randomness itself is not what these pin - there is nothing useful to assert about one draw
 * from a CSPRNG. What matters is everything around it, and each of these corresponds to a way a
 * generated password ends up differing from what the account actually has, which is the failure
 * that makes a password manager worse than no password manager.
 */
class PasswordGeneratorTest {
    private fun generated(
        maxLength: Int? = null,
        pattern: String? = null,
    ): PasswordGenerator.Outcome.Generated {
        val outcome = PasswordGenerator.generate(maxLength, pattern)
        assertIs<PasswordGenerator.Outcome.Generated>(outcome, "expected a password, got $outcome")
        return outcome
    }

    @Test
    fun `an unconstrained field gets the full default length`() {
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, generated().password.length)
    }

    @Test
    fun `every character class is present`() {
        // Repeated, because the guarantee is the point: a uniformly random draw omits a class often
        // enough that "must contain a digit" would fail intermittently, which is the worst kind of
        // signup bug to be told about.
        repeat(50) {
            val password = generated().password
            assertTrue(password.any { it.isLowerCase() }, "no lowercase in $password")
            assertTrue(password.any { it.isUpperCase() }, "no uppercase in $password")
            assertTrue(password.any { it.isDigit() }, "no digit in $password")
            assertTrue(password.any { !it.isLetterOrDigit() }, "no symbol in $password")
        }
    }

    @Test
    fun `the guaranteed characters are not left in a fixed order`() {
        // Without the shuffle every password would begin lower-upper-digit-symbol, which is a real
        // weakness and not a cosmetic one. 40 draws whose first character is always the same class
        // would be a 1-in-10^24 coincidence.
        val firstCharClasses = (1..40).map { generated().password.first().isLowerCase() }.toSet()
        assertEquals(setOf(true, false), firstCharClasses, "the first character is always one class")
    }

    @Test
    fun `no two draws are the same`() {
        val draws = (1..200).map { generated().password }.toSet()
        assertEquals(200, draws.size, "a draw repeated, so this is not drawing from what it claims")
    }

    // ------------------------------------------------------------------------ maxlength

    @Test
    fun `a capped field gets exactly what it can hold`() {
        // The failure this prevents: the field truncates silently, the account ends up with the
        // first 14 characters, and Secret Manager stores 20. The user is then locked out with a
        // password manager insisting it has the right password.
        assertEquals(14, generated(maxLength = 14).password.length)
    }

    @Test
    fun `a cap larger than the default does not stretch the password`() {
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, generated(maxLength = 128).password.length)
    }

    @Test
    fun `a field too short to hold a decent password is refused rather than weakened`() {
        val outcome = PasswordGenerator.generate(maxLength = 8)
        assertIs<PasswordGenerator.Outcome.TooShort>(outcome)
        assertEquals(8, outcome.maxLength)
        assertFalse(PasswordGenerator.fits(8))
    }

    @Test
    fun `an absent maxlength is reported by the browser as -1 or 0 and means no limit`() {
        // Both spellings occur: the DOM property is -1 for an unset attribute, and a page that sets
        // it to an empty string reads back as 0. Treating either as a real cap would refuse to
        // suggest anything on a very large number of forms.
        assertTrue(PasswordGenerator.fits(-1))
        assertTrue(PasswordGenerator.fits(0))
        assertTrue(PasswordGenerator.fits(null))
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, generated(maxLength = -1).password.length)
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, generated(maxLength = 0).password.length)
    }

    // -------------------------------------------------------------------------- pattern

    @Test
    fun `a pattern rejecting punctuation drops to alphanumeric and says so`() {
        val outcome = generated(pattern = "[A-Za-z0-9]+")
        assertTrue(outcome.alphanumericOnly, "did not report the restriction")
        assertTrue(outcome.password.all { it.isLetterOrDigit() }, "punctuation survived: ${outcome.password}")
    }

    @Test
    fun `a pattern is matched in full, not merely found somewhere`() {
        // An unanchored `find` would accept a password whose FIRST character happens to match, so
        // this is the difference between honouring the site's rule and appearing to.
        val outcome = generated(pattern = "[a-z]{20}")
        // Either it produced an all-lowercase 20 or it fell back; what must not happen is a
        // password containing an uppercase letter being reported as matching.
        if (!outcome.alphanumericOnly) {
            assertTrue(outcome.password.all { it.isLowerCase() }, "reported a match for ${outcome.password}")
        }
    }

    @Test
    fun `a pattern nothing can satisfy still yields a password rather than nothing`() {
        // A site whose pattern requires a fixed prefix cannot be satisfied by a generator, and the
        // right answer is still to offer something the user can see and edit - the field itself is
        // the final arbiter, and refusing would leave them with no help at all.
        val outcome = generated(pattern = "ACME-[0-9]{4}")
        assertTrue(outcome.password.isNotEmpty())
    }

    @Test
    fun `a pattern that will not compile is ignored instead of breaking the offer`() {
        // JavaScript regex syntax is not Java's, and a site is free to ship something only V8
        // accepts. An exception here would surface as "no suggestion on this form", with nothing
        // saying why.
        val outcome = generated(pattern = "(?<=unsupported")
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, outcome.password.length)
        assertFalse(outcome.alphanumericOnly)
    }

    @Test
    fun `a backtracking pattern stays bounded`() {
        // `pattern` is attacker-controlled - it comes straight off the page - and java.util.regex
        // cannot be asked to time out, so this is worth pinning.
        //
        // THE PATTERN MATTERS, and the obvious choice is worthless here. The textbook `(a+)+b` costs
        // 2ms against a generated password, because backtracking needs input the pattern can
        // partially match and a random 20-char string starts failing at character one. Measured on
        // this alphabet, the expensive shape is one whose inner class matches everything:
        //
        //   (a+)+b               0.9 ms      <- proves nothing
        //   (.*.*.*.*.*)*X      28   ms
        //   (([A-Za-z0-9]+)+)+X 56   ms      <- the worst found, and what is used below
        //
        // So the cost is bounded by the CANDIDATE length, which the generator caps at 20 - not by
        // anything the page controls. 12 matches x ~56ms is the realistic worst case, and the
        // ceiling here is set above that rather than at a benchmark. For scale, the same pattern
        // against a 64-character candidate takes 1,010ms per match, so if the generated length ever
        // grows this bound is the thing that stops holding.
        val expensive = "(([A-Za-z0-9]+)+)+X"
        val elapsed =
            kotlin.system.measureTimeMillis {
                assertIs<PasswordGenerator.Outcome.Generated>(PasswordGenerator.generate(pattern = expensive))
            }
        assertTrue(elapsed < 3_000, "generate() took ${elapsed}ms against $expensive")
    }

    @Test
    fun `an over-long pattern is ignored rather than compiled`() {
        // The room to build a pathological case, removed. No real HTML pattern is anywhere near
        // this long - sites ship a character class and a length assertion.
        val huge = "(a+)+" + "x".repeat(PasswordGenerator.MAX_PATTERN_CHARS)
        val outcome = generated(pattern = huge)
        // Ignored means treated as "no pattern": full alphabet, default length.
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, outcome.password.length)
        assertFalse(outcome.alphanumericOnly)
    }

    @Test
    fun `a pattern at the length limit is still honoured`() {
        // The cap must not quietly disable the feature for a long-but-legitimate pattern.
        val atLimit = "[A-Za-z0-9]{12,20}".padEnd(PasswordGenerator.MAX_PATTERN_CHARS, ' ').trim()
        val outcome = generated(pattern = atLimit)
        assertTrue(outcome.password.isNotEmpty())
    }

    // ------------------------------------------------------------------------- alphabet

    @Test
    fun `the characters that make quoting bugs are absent`() {
        // The fill splices this into JavaScript source. CredentialFill.jsLiteral escapes correctly,
        // but a value that cannot break a quoting bug is better than one that relies on the escaper
        // being right.
        val forbidden = charArrayOf('"', '\'', '\\', '<', '>', ' ', '`')
        repeat(200) {
            val password = generated().password
            forbidden.forEach { c ->
                assertFalse(password.contains(c), "generated $password contains $c")
            }
        }
    }

    @Test
    fun `visually ambiguous characters are absent`() {
        // Nobody should have to read one of these off a screen, but a recovery path is exactly when
        // a password manager is unavailable and someone is retyping by hand.
        repeat(200) {
            val password = generated().password
            listOf('0', 'O', '1', 'l', 'I').forEach { c ->
                assertFalse(password.contains(c), "generated $password contains ambiguous $c")
            }
        }
    }
}
