package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.security.SecureRandom

/**
 * Generates the password offered on a signup or change-password field.
 *
 * `SecureRandom`, not `Random`: the output is a credential, and `java.util.Random` is a 48-bit
 * linear congruential generator whose entire future output is recoverable from a couple of
 * observations. That the values here are never shown to anyone else is not the point - the seeding
 * is the difference, and there is no reason to take the weaker one.
 *
 * The interesting part is not the randomness though, it is fitting the **site's** rules. A password
 * the field silently refuses or truncates is worse than no suggestion at all, because what gets
 * saved to Secret Manager is then not what the account has. Two constraints are read off the field
 * by the login probe and honoured here: `maxlength`, and `pattern`.
 */
internal object PasswordGenerator {
    /**
     * Long enough that the character classes stop mattering, short enough to survive the length
     * caps real sites impose. 20 also keeps it inside the 16-to-24 window that the more annoying
     * validators tend to allow.
     */
    const val DEFAULT_LENGTH = 20

    /**
     * Below this a generated password is not worth offering, so a field whose `maxlength` is
     * smaller gets no suggestion at all rather than a weak one. Sites that cap at 8 exist; making
     * that choice silently on the user's behalf does not.
     */
    const val MIN_LENGTH = 12

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"

    /**
     * Punctuation chosen for what sites accept, not for entropy per character.
     *
     * Excluded deliberately: `"` `'` and `\` (they are the characters that make quoting bugs, and
     * the fill path splices this into JavaScript source - [CredentialFill.jsLiteral] escapes them
     * correctly, but a value that cannot break a quoting bug is better than one that relies on the
     * escaper being right); `<` and `>` (same argument for HTML contexts); space (trimmed by an
     * unknowable number of forms); and `` ` `` (shell-adjacent, and it reads as a smudge in most
     * fonts).
     */
    private const val SYMBOLS = "-_.!@#%^&*+=?~"

    /**
     * `0`, `O`, `1`, `l`, `I` are absent from the sets above.
     *
     * The password is stored and filled, so nobody should ever need to read it - but "should" does
     * a lot of work there. A recovery path where someone reads it off one screen and types it into
     * another is exactly the moment a password manager is least available, and that is the moment
     * an `l`/`1` confusion costs an hour.
     */
    private val ALL = LOWER + UPPER + DIGITS + SYMBOLS

    /** Same minus punctuation, for a site whose `pattern` rejects symbols. */
    private val ALPHANUMERIC = LOWER + UPPER + DIGITS

    private val random = SecureRandom()

    /**
     * What the generator was able to produce for a given field.
     *
     * A sealed result rather than a nullable String, because "this field cannot take a decent
     * password" is a thing the UI has to say differently from "here is one".
     */
    sealed interface Outcome {
        data class Generated(
            val password: String,
            /** True when the site's `pattern` forced dropping punctuation. */
            val alphanumericOnly: Boolean,
        ) : Outcome

        /** The field's own `maxlength` is below [MIN_LENGTH]. Nothing worth offering. */
        data class TooShort(
            val maxLength: Int,
        ) : Outcome
    }

    /**
     * Whether a field with this `maxlength` can hold a password worth offering.
     *
     * Separate from [generate] so a composable can ask without producing a value: this is called on
     * every recomposition while the caret sits in a password box, and generating a credential to
     * immediately discard it would be both wasteful and a slightly alarming thing for the code to
     * be doing.
     */
    fun fits(maxLength: Int?): Boolean {
        val cap = maxLength?.takeIf { it > 0 } ?: return true
        return cap >= MIN_LENGTH
    }

    /**
     * Generate a password that fits [maxLength] and, where possible, [pattern].
     *
     * **Do not call this on the composition thread.** [pattern] is attacker-controlled - it comes
     * straight off the page - and `java.util.regex` cannot be asked to time out.
     *
     * Measured rather than assumed, because the size of the problem decides the fix. Backtracking is
     * exponential in the CANDIDATE length, and the candidate here is capped at 20 characters, so the
     * worst pattern found costs about 56ms per match (`(([A-Za-z0-9]+)+)+X`; the textbook `(a+)+b`
     * costs 0.9ms, since a random password stops matching at character one). That is a few hundred
     * milliseconds across the retries - a repeatable UI stall on every focus and every regenerate,
     * not the indefinite hang it first looked like.
     *
     * So it is bounded, but by an accident of the candidate length rather than by anything here: the
     * same pattern against a 64-character candidate takes 1,010ms per match. If [DEFAULT_LENGTH] or
     * a field's `maxlength` ever raises what gets generated, this stops being cheap. Hence all
     * three: off the UI thread, [MAX_PATTERN_CHARS] on the input, and few [PATTERN_ATTEMPTS].
     *
     * @param maxLength the field's `maxlength`, or null when it declares none.
     * @param pattern the field's `pattern` attribute, or null. Applied as a full match, which is
     *   what the HTML spec says the browser does.
     */
    fun generate(
        maxLength: Int? = null,
        pattern: String? = null,
        length: Int = DEFAULT_LENGTH,
    ): Outcome {
        // A maxlength of 0 or a negative one means "no limit" in practice: browsers report -1 for
        // an absent attribute, and a real 0 would make the field unusable for anything.
        val cap = maxLength?.takeIf { it > 0 }
        if (cap != null && cap < MIN_LENGTH) return Outcome.TooShort(cap)
        val target = minOf(length, cap ?: length).coerceAtLeast(MIN_LENGTH)

        val compiled = compilePattern(pattern)
        if (compiled == null) {
            // No pattern, or one this platform cannot compile. Not a reason to refuse: an
            // unparseable pattern is the site's problem, and the fill is verified against the
            // field afterwards anyway.
            return Outcome.Generated(build(target, ALL), alphanumericOnly = false)
        }

        // Try the full alphabet first, then alphanumeric. Retries because a pattern like
        // "at least one digit" is satisfied by most candidates but not all, and re-rolling is
        // cheaper and clearer than trying to synthesise a string from a regex.
        repeat(PATTERN_ATTEMPTS) {
            val candidate = build(target, ALL)
            if (compiled.matches(candidate)) return Outcome.Generated(candidate, alphanumericOnly = false)
        }
        repeat(PATTERN_ATTEMPTS) {
            val candidate = build(target, ALPHANUMERIC)
            if (compiled.matches(candidate)) return Outcome.Generated(candidate, alphanumericOnly = true)
        }
        // Nothing matched. Offer the alphanumeric one rather than nothing: the pattern may be
        // wrong, or may require something no generator can guess (a fixed prefix), and the user
        // can still see and edit what is offered. The field itself is the final arbiter.
        return Outcome.Generated(build(target, ALPHANUMERIC), alphanumericOnly = true)
    }

    /**
     * One character from each class first, then fill, then shuffle.
     *
     * The guarantee matters because "must contain a digit" is the commonest site rule, and a
     * uniformly random 20-character string omits at least one class often enough to be a support
     * problem. The shuffle is what keeps the guarantee from becoming a pattern: without it every
     * password would start lower-upper-digit-symbol, which is a real weakness rather than a
     * cosmetic one.
     */
    private fun build(
        length: Int,
        alphabet: String,
    ): String {
        val required = listOf(LOWER, UPPER, DIGITS, SYMBOLS).filter { cls -> cls.any { it in alphabet } }
        val chars = ArrayList<Char>(length)
        required.forEach { cls -> chars.add(pick(cls)) }
        while (chars.size < length) chars.add(pick(alphabet))
        // Fisher-Yates with SecureRandom. Not Collections.shuffle(list) without an rng argument,
        // which uses a shared java.util.Random.
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return chars.joinToString("")
    }

    private fun pick(alphabet: String): Char = alphabet[random.nextInt(alphabet.length)]

    /**
     * The field's `pattern` as a Kotlin [Regex], or null if it will not compile.
     *
     * JavaScript regex syntax is close to Java's but not identical, and a site is free to ship
     * something only V8 accepts. A pattern that throws must not take the suggestion down with it,
     * which is why this returns null rather than propagating.
     */
    private fun compilePattern(pattern: String?): Regex? {
        val raw = pattern?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Length-bounded, because this string comes off the page and `java.util.regex` has no
        // timeout. A nested-quantifier pattern whose inner class matches the whole alphabet costs
        // ~56ms per match against a generated candidate (see [generate] for the measurements), and
        // a longer pattern is more room to build worse. Not a fix on its own - the thread is the
        // fix - but the same spirit as CredentialCapture.MAX_FIELD_LENGTH bounding its inputs.
        //
        // 200 characters is far past any real HTML `pattern`; the ones sites ship are a character
        // class and a length assertion.
        if (raw.length > MAX_PATTERN_CHARS) return null
        return runCatching { Regex("^(?:$raw)$") }.getOrNull()
    }

    /** Longer than any pattern a site legitimately ships. See [compilePattern]. */
    internal const val MAX_PATTERN_CHARS = 200

    /**
     * Re-rolls allowed per alphabet before giving up on the site's pattern.
     *
     * Lowered from 24. A pattern that a random 20-character string satisfies at all is satisfied
     * within a handful of draws ("must contain a digit" succeeds ~90% of the time), so the tail was
     * only ever spent on patterns that were never going to match - which is exactly the case where
     * each attempt is most expensive.
     */
    private const val PATTERN_ATTEMPTS = 6
}
