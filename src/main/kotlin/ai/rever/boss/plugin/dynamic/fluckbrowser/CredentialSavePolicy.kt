package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.SecretEntryData

/**
 * When to offer to save a credential the user typed, and whether it is a new secret or an update.
 *
 * Pure, and separate from the wiring for the same reason `shouldOfferSuggestions` is: this is
 * policy over a handful of inputs, and inline in a composable it would have no coverage at all.
 * Every branch here is a decision about whether to put a prompt in front of someone, which is worth
 * being able to argue with in a test rather than by reading a `when` inside a `LaunchedEffect`.
 *
 * The bias throughout is toward **silence**. A missed prompt costs the user one trip to Secret
 * Manager; a wrong one trains them to dismiss the bar without reading it, which costs every later
 * prompt as well.
 */
internal object CredentialSavePolicy {
    /**
     * A captured credential waiting to find out whether the login worked.
     *
     * Held in plugin memory only, never written anywhere. It cannot be *erased* on use - a Kotlin
     * `String` is immutable and there is no way to zero its backing array - so the honest statement
     * is that the reference is dropped and the value becomes garbage, not that it is wiped.
     */
    data class Pending(
        /** Stamped by the plugin from its own committed URL, never from the page's payload. */
        val domain: String,
        val username: String,
        val password: String,
        val wasFilledByBoss: Boolean,
        val capturedAtMs: Long,
    )

    /** What to do about a [Pending] once the login looks like it worked. */
    sealed interface Decision {
        /** Nothing worth asking about. */
        data object Ignore : Decision

        /** No secret covers this login yet. [username] may be blank, in which case the bar asks. */
        data class Save(
            val domain: String,
            val username: String,
            val password: String,
        ) : Decision

        /** A secret covers this login and holds a different password. */
        data class Update(
            val secret: SecretEntryData,
            val password: String,
        ) : Decision
    }

    /** Whether the login this credential was typed into has resolved yet. */
    enum class Outcome {
        /** No verdict yet. Keep holding the capture. */
        WAITING,

        /** The login form is gone from the page, which is what success looks like. */
        SUCCEEDED,

        /** Long enough that no verdict is coming. Drop it. */
        EXPIRED,
    }

    /**
     * How long a capture is held before it is dropped unanswered.
     *
     * Long enough for a slow login plus a redirect chain, short enough that a credential typed and
     * abandoned does not sit in memory for the rest of the session, and short enough that a prompt
     * can never arrive so late that the user has forgotten doing anything.
     */
    const val PENDING_WINDOW_MS = 90_000L

    /**
     * Read the outcome from what the login-field probe currently sees.
     *
     * **The signal is "the password box is gone", not "the URL changed".** Both were tried. A URL
     * change fires for the failure case too - a wrong password commonly re-renders the same form at
     * a new URL, or at the same one with `?error=1` - and it fires for the second screen of a
     * two-step sign-in, which is another login form and not a success. The form being gone covers
     * every one of those correctly, including a single-page login that never changes URL at all,
     * because a failed attempt leaves the box on screen.
     *
     * [LoginFieldProbe.NoLoginField] is also what an unreadable probe answer becomes. That mistake
     * is affordable in this direction: the cost is one prompt naming a site and a username, which
     * the user can dismiss, and the alternative reading would mean a garbage answer suppresses
     * every save on that page.
     *
     * **What is NOT affordable, and is handled in the probe rather than here:** a document that is
     * merely *between* pages. A form POST destroys the old document and the new one parses
     * incrementally, so the first observation after a submit often lands on a page with no inputs
     * yet, or on `about:blank` between commits. Read as "the form is gone" that produces a save
     * prompt for a password that just FAILED - the exact outcome this design exists to avoid, and on
     * the critical path of every submit rather than in some rare corner. [LOGIN_FIELD_PROBE_JS]
     * therefore answers `IDLE` while `document.readyState` is `loading` and for a document with no
     * origin, so those never reach this function as a verdict.
     *
     * [currentDomain] is what the tab is on now, and a mismatch against [Pending.domain] ends the
     * wait: see the branch for why that is a drop rather than a prompt.
     */
    fun outcome(
        pending: Pending,
        probe: LoginFieldProbe,
        nowMs: Long,
        currentDomain: String?,
    ): Outcome =
        when {
            nowMs - pending.capturedAtMs >= PENDING_WINDOW_MS -> Outcome.EXPIRED
            // The tab has moved to a different site. Dropped rather than carried, because a bar
            // offering site A's credential while the user is looking at site B is the kind of prompt
            // that teaches people to dismiss the bar unread - and the whole design leans on the bar
            // being trustworthy.
            //
            // The cost is real and accepted: a federated sign-in where the password is typed on an
            // identity provider and the flow ends on a different registrable domain loses its save.
            // That credential is usually already stored, and the alternative - prompting about a
            // site the user has left - is worse on every login that is not federated.
            currentDomain != null && currentDomain != pending.domain -> Outcome.EXPIRED
            probe == LoginFieldProbe.NoLoginField -> Outcome.SUCCEEDED
            // Idle or Focused: a login field is still on the page, so this attempt has not
            // resolved. That is the wrong-password case as well as the still-loading one.
            else -> Outcome.WAITING
        }

    /**
     * Decide what to offer for [pending], given every secret the user can see.
     *
     * @param secrets the full list; matching to this domain happens here so the caller cannot pass
     *   a differently-filtered set than the one the decision assumes.
     */
    fun decide(
        pending: Pending,
        secrets: List<SecretEntryData>,
    ): Decision {
        if (pending.password.isBlank()) return Decision.Ignore
        val matches = matchSecretsForDomain(pending.domain, secrets)

        // Already stored, unchanged. This is the common case for a site the user logs into every
        // day, and getting it wrong means a prompt on every single sign-in.
        if (matches.any { it.password == pending.password && sameUsername(it.username, pending.username) }) {
            return Decision.Ignore
        }

        // A credential this plugin filled, which the user did not change.
        //
        // The marker alone is NOT enough to decide that, which is the trap here: the fill sets
        // `data-boss-filled` and nothing ever clears it, so a filled field the user then edited
        // still carries it. Trusting the flag by itself would silently refuse to save exactly the
        // change the user made by hand. So the flag only suppresses when a saved secret really does
        // hold this password - which also catches a site that reformats the username it was given.
        if (pending.wasFilledByBoss && matches.any { it.password == pending.password }) {
            return Decision.Ignore
        }

        // A row holding exactly this password that never got a username. That is specifically what
        // the suggestor leaves behind: it stores a generated password the moment it lands in the
        // field, and on a signup form where the email is typed AFTER the password there was no
        // username to store with it. Without this the submit that follows looks like a brand-new
        // credential and offers to Save a second row for the same account.
        //
        // Scoped to a BLANK stored username on purpose. Matching on the password alone would find a
        // real account that happens to share a password with another one on the same site, and
        // rename it - destroying that mapping. A row with no username is not a mapping yet, so
        // filling it in is a repair rather than an overwrite.
        if (pending.username.isNotBlank()) {
            matches.firstOrNull { it.password == pending.password && it.username.isBlank() }?.let {
                return Decision.Update(it, pending.password)
            }
        }

        if (pending.username.isNotBlank()) {
            val existing = matches.firstOrNull { sameUsername(it.username, pending.username) }
            // A same-username match here necessarily holds a different password: an identical one
            // returned Ignore above.
            return existing?.let { Decision.Update(it, pending.password) }
                ?: Decision.Save(pending.domain, pending.username, pending.password)
        }

        // No username came back. This is the second screen of a two-step sign-in, where the
        // identifier was entered on the previous page and is not in this document at all.
        //
        // One saved login for the site is unambiguous, so offer to update it. Several is a genuine
        // question the plugin cannot answer, and picking one would overwrite the wrong account's
        // password - so it falls through to Save, where the bar asks for the username.
        return matches.singleOrNull()?.let { Decision.Update(it, pending.password) }
            ?: Decision.Save(pending.domain, "", pending.password)
    }

    /**
     * Usernames compare case-insensitively, and a blank captured username matches nothing.
     *
     * Email addresses are the usual login identifier and their local part is case-sensitive in the
     * RFC but case-insensitive at every provider anyone actually uses. Treating `User@x.com` as a
     * different account from `user@x.com` would offer to Save a duplicate of a secret that is
     * already there.
     */
    private fun sameUsername(
        stored: String,
        captured: String,
    ): Boolean = captured.isNotBlank() && stored.equals(captured, ignoreCase = true)
}
