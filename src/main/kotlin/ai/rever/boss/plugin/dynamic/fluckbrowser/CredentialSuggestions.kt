package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the page's focused login field is, so a credential list can be offered beside it without
 * the user having to right-click first.
 *
 * Geometry is in CSS pixels relative to the viewport, which is what the browser view's own
 * coordinate space is at zoom 1. [FluckBrowserTabComponent] scales by the tab's zoom level.
 *
 * Note what is deliberately absent: the field's **value**. Only [hasValue] crosses the boundary,
 * because a probe that ran twice a second and returned page text would be a keylogger. The
 * suggestion list closes once the box has something in it, and a Boolean is all that needs.
 */
@Serializable
internal data class FocusedLoginField(
    /** Identity within this document, so a dismissal sticks to the field it was made on. */
    val key: String,
    /** True for `type="password"`, false for the username/email side. */
    val isPassword: Boolean,
    /**
     * True when this is a password being *chosen* rather than recalled - a signup form, or the new
     * half of a change-password form. What decides whether to offer a generated password.
     *
     * Defaulted, like the two below, so a probe answer from before these existed still parses.
     */
    val isNewPassword: Boolean = false,
    /**
     * The field's `maxlength`, or -1 when it declares none.
     *
     * Carried because a site that caps at 12 and silently truncates a 20-character suggestion
     * leaves Secret Manager holding a password the account does not have. See [PasswordGenerator].
     */
    val maxLength: Int = -1,
    /** The field's `pattern` attribute, or null. Also for [PasswordGenerator]. */
    val pattern: String? = null,
    /** `location.href`, read from the page rather than the URL bar, which the user may be editing. */
    val pageUrl: String,
    /** Whether the box already has something in it. The value itself never leaves the page. */
    val hasValue: Boolean,
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    /**
     * What a dismissal is remembered against.
     *
     * The page URL is part of it because [key] alone is not unique across documents - a form's
     * first username box is `0|username||text` on a great many sites - and a dismissal that
     * carried over to the next site would silently hide the list there.
     */
    val dismissId: String get() = "$pageUrl#$key"

    /**
     * Position in the page's eligible-login-field list, which is what the fill script accepts as
     * its target. Null if [key] is not in the shape the probe produces, so a malformed answer
     * falls back to resolving from `document.activeElement` rather than filling field 0.
     */
    val index: Int? get() = key.substringBefore('|').toIntOrNull()
}

/** What one run of [LOGIN_FIELD_PROBE_JS] found. */
internal sealed interface LoginFieldProbe {
    /** No login field on this page at all. Nothing to offer, and nothing to watch closely. */
    data object NoLoginField : LoginFieldProbe

    /** The page has a login field, but the user is not in one right now. */
    data object Idle : LoginFieldProbe

    /** The user is in a login field, described by [field]. */
    data class Focused(
        val field: FocusedLoginField,
    ) : LoginFieldProbe
}

private val probeJson = Json { ignoreUnknownKeys = true }

/**
 * Read one probe answer. An unparseable or absent answer is [LoginFieldProbe.NoLoginField]:
 * showing nothing is the right failure, and it also backs the poll off to its slowest rate, so a
 * page that somehow answers garbage forever costs almost nothing.
 */
internal fun parseLoginFieldProbe(raw: Any?): LoginFieldProbe {
    // Any?, and normalised before matching. `executeJavaScript` returns Any?, so a wrapper type or
    // a quoted string would collapse every branch to NoLoginField and the feature would simply
    // never appear, with no log line. Shares normalizeJsStringResult with
    // busyStateFromScriptResult and ScrollRestore.parseCapture, which normalise for the same
    // reason.
    val text = normalizeJsStringResult(raw) ?: return LoginFieldProbe.NoLoginField
    return when {
        text.isBlank() -> LoginFieldProbe.NoLoginField
        text == PROBE_NONE -> LoginFieldProbe.NoLoginField
        text == PROBE_IDLE -> LoginFieldProbe.Idle
        else ->
            runCatching { LoginFieldProbe.Focused(probeJson.decodeFromString<FocusedLoginField>(text)) }
                .getOrElse { LoginFieldProbe.NoLoginField }
    }
}

/**
 * How long to wait before probing again.
 *
 * Each probe is a synchronous round-trip into Chromium. It no longer runs on the UI thread - the
 * caller dispatches it to `Dispatchers.IO`, because `executeJavaScript` blocks until the renderer
 * answers and a renderer in a long task would otherwise park the EDT. That removes the worst
 * consequence but not the cost: it is still a thread and an IPC per probe, so the rate is tied to
 * what is actually on screen:
 *
 * - **Focused** - the list is (or is about to be) open beside the box, and it has to follow the
 *   field as the page scrolls. This is the only rate that is quick, and it only runs while the
 *   user is literally typing into a login form.
 * - **Idle** - the page has a login box the user has not entered yet, so the only thing being
 *   watched for is them clicking into it.
 * - **NoLoginField** - almost every page. Still checked, slowly, because a single-page app can
 *   grow a login form without a navigation.
 */
internal fun loginProbeDelayMs(probe: LoginFieldProbe): Long =
    when (probe) {
        is LoginFieldProbe.Focused -> 300L
        LoginFieldProbe.Idle -> 900L
        LoginFieldProbe.NoLoginField -> 4_000L
    }

private const val PROBE_NONE = "NONE"
private const val PROBE_IDLE = "IDLE"

/**
 * Field-eligibility helpers shared by the focus probe and the fill script.
 *
 * One copy because the two must agree on what counts as a field a person could have typed into.
 * Offering a credential for a box and then filling a different one is the failure this whole
 * change exists to remove, and two copies of these rules is how that comes back.
 *
 * `getClientRects()` rather than `offsetParent`, because the latter is null for every
 * `position: fixed` element and would reject the visible field on a modal login. The host once had
 * an equivalent test; do not go looking for it, BossConsole#215 deleted that resolver along with
 * the API it served, and these rules are now the only copy.
 */
internal val FIELD_ELIGIBILITY_JS =
    """
    function rects(el) {
        try { return el.getClientRects().length; } catch (e) { return 0; }
    }
    function fillable(el) {
        if (!el || el.tagName !== 'INPUT') return false;
        var t = (el.type || 'text').toLowerCase();
        if (t !== 'text' && t !== 'email' && t !== 'tel' && t !== 'password') return false;
        if (el.disabled || el.readOnly) return false;
        if (el.getAttribute('aria-hidden') === 'true') return false;
        if (rects(el) === 0) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) return false;
        if (r.left < -2000 || r.top < -2000) return false;
        // Inside the viewport, not merely on the page. Focusing a box and then scrolling does not
        // blur it, and the suggestion popup is an always-on-top window anchored to this rect - off
        // the viewport it gets drawn over the toolbar or outside the tab entirely, not clipped.
        if (r.bottom <= 0 || r.right <= 0) return false;
        if (r.top >= window.innerHeight || r.left >= window.innerWidth) return false;
        var cs = window.getComputedStyle(el);
        if (!cs) return true;
        if (cs.display === 'none') return false;
        if (cs.visibility === 'hidden' || cs.visibility === 'collapse') return false;
        if (parseFloat(cs.opacity || '1') === 0) return false;
        return true;
    }
    function tokens(el) {
        return (el.getAttribute('autocomplete') || '').toLowerCase().split(/[\s,]+/);
    }
    function hasToken(el, want) {
        var parts = tokens(el);
        for (var i = 0; i < parts.length; i++) { if (parts[i] === want) return true; }
        return false;
    }
    function hints(el) {
        var s = [
            el.name, el.id, el.getAttribute('aria-label'),
            el.placeholder, el.getAttribute('autocomplete')
        ].join(' ').toLowerCase();
        return /user|e-?mail|login|account|identifier|signin/.test(s);
    }
    // A login field, not merely a text box: offering credentials beside a site's search bar
    // would be noise on most of the web. A password box always qualifies; a text box has to
    // say so through its autocomplete token, its type, or its name.
    function isLoginField(el) {
        if (!fillable(el)) return false;
        if ((el.type || '').toLowerCase() === 'password') return true;
        if (hasToken(el, 'username') || hasToken(el, 'email')) return true;
        if ((el.type || '').toLowerCase() === 'email') return true;
        return hints(el);
    }
    """.trimIndent()

/**
 * Answers "is the user in a login box, and where is it" in one round-trip.
 *
 * One script rather than a cheap gate plus a detailed follow-up, because two scripts would be two
 * blocking IPC calls per poll and the gate's answer is a by-product of the work the detailed one
 * already does.
 *
 * Eligibility comes from [FIELD_ELIGIBILITY_JS], shared with the fill script so the two cannot
 * disagree about what counts as a field. A field the user cannot see is not a field to offer a
 * credential for: Google's sign-in page carries a `display: none` password input, and filling
 * *that* is the bug this whole feature was written against.
 */
internal val LOGIN_FIELD_PROBE_JS =
    """
    (function() {
$FIELD_ELIGIBILITY_JS
        // document.activeElement survives the browser view losing focus, so without this the list
        // would still be drawn - over the URL bar's own autocomplete, or after the user alt-tabbed
        // away entirely. Also drops an unfocused tab to the slowest poll rate.
        if (!document.hasFocus()) return 'IDLE';
        // A document that is still parsing has not told us anything yet.
        //
        // This is the difference between "no login form here" and "no login form YET", and the save
        // prompt turns on it: a form POST destroys the old document and the new one parses
        // incrementally, so the first probe after a submit very often lands before the new page's
        // inputs exist. Answering NONE there reads as "the login form is gone", which is the signal
        // CredentialSavePolicy treats as success - so mistyping a password and being bounced back to
        // the same form would offer to save the wrong one. IDLE is the honest answer: no verdict yet.
        if (document.readyState === 'loading') return 'IDLE';
        // Nor has a document with no origin. `about:blank` between two commits reports readyState
        // 'complete' with no inputs, so the check above does not catch it - and it sits exactly in
        // the gap a form POST opens. Deliberately not solved by demanding two consecutive answers
        // instead: at the no-login-field poll rate that would delay every genuine prompt by four
        // seconds to fix a case this line fixes for nothing.
        if (!location.host) return 'IDLE';
        var all = document.querySelectorAll('input');
        var fields = [];
        for (var i = 0; i < all.length; i++) {
            if (isLoginField(all[i])) fields.push(all[i]);
        }
        if (fields.length === 0) return 'NONE';
        var active = document.activeElement;
        var index = -1;
        for (i = 0; i < fields.length; i++) { if (fields[i] === active) index = i; }
        if (index === -1) return 'IDLE';
        var el = fields[index];
        var r = el.getBoundingClientRect();
        var isPassword = (el.type || '').toLowerCase() === 'password';

        // A password the user is CHOOSING, not one they are recalling. Three signals, in order of
        // how much they can be trusted:
        //
        // 1. autocomplete says so. `current-password` is decisive in the other direction and is
        //    checked first, because a change-password form has both and the box holding the old
        //    password must never be offered a generated one.
        // 2. More than one password box on the page. A sign-in form has one; a signup or
        //    change-password form has two or three.
        // 3. Name/id/placeholder wording. Last, because "confirm" also appears on plenty of
        //    fields that are not passwords at all - which is why this only runs for a password box.
        var isNewPassword = false;
        if (isPassword && !hasToken(el, 'current-password')) {
            if (hasToken(el, 'new-password')) {
                isNewPassword = true;
            } else {
                var passwordCount = 0;
                for (i = 0; i < fields.length; i++) {
                    if ((fields[i].type || '').toLowerCase() === 'password') passwordCount++;
                }
                if (passwordCount > 1) {
                    isNewPassword = true;
                } else {
                    var words = [
                        el.name, el.id, el.placeholder, el.getAttribute('aria-label')
                    ].join(' ').toLowerCase();
                    isNewPassword = /new|confirm|repeat|retype|register|sign-?up|create/.test(words);
                }
            }
        }

        return JSON.stringify({
            key: index + '|' + (el.name || '') + '|' + (el.id || '') + '|' + (el.type || ''),
            isPassword: isPassword,
            isNewPassword: isNewPassword,
            maxLength: (typeof el.maxLength === 'number' ? el.maxLength : -1),
            pattern: el.getAttribute('pattern'),
            pageUrl: location.href,
            hasValue: !!(el.value && el.value.length > 0),
            left: r.left,
            top: r.top,
            width: r.width,
            height: r.height
        });
    })();
    """.trimIndent()

/**
 * The credential list offered beside a focused login box.
 *
 * Deliberately small: matched accounts, an escape hatch to the full picker, and a way to close it.
 * It sits over live page content, so anything more would obscure the form it is trying to help
 * with. Passwords are never shown, and never rendered even masked - the row is an account to
 * choose, not a credential to read.
 */
@Composable
internal fun CredentialSuggestionList(
    secrets: List<SecretEntryData>,
    onPick: (SecretEntryData) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        elevation = 8.dp,
        backgroundColor = BossThemeColors.SurfaceColor,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Saved logins",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Hide suggestions",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            secrets.forEach { secret ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(secret) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            secret.username,
                            color = BossThemeColors.TextPrimary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            getDisplayName(secret.website),
                            color = BossThemeColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Divider(color = BossThemeColors.TextSecondary.copy(alpha = 0.15f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowAll() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Other logins...",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Narrow enough for a compact login box, wide enough for an email address. */
internal val SUGGESTION_MIN_WIDTH = 220.dp

/** A wide field should not hand the list the whole page width. */
internal val SUGGESTION_MAX_WIDTH = 420.dp

/**
 * What the user is told when a fill lands nowhere.
 *
 * Deliberately about the page rather than about BOSS: by the time this shows, the credential was
 * found and the click registered, and the thing that failed is the match against this page's form.
 */
internal const val FILL_FAILED_NOTICE = "No login box on this page could be filled"

internal const val FILL_NOTICE_DURATION_MS = 4_000L

/**
 * Advisory bound on one probe, mirroring `HibernationPolicy.BUSY_CHECK_TIMEOUT_MS`.
 *
 * `withTimeoutOrNull` can only abandon a call that suspends, so a call blocked inside JxBrowser
 * keeps its thread regardless. It is worth setting anyway: the thread it keeps is an IO one, and
 * the timeout does bound the queued case.
 */
internal const val LOGIN_PROBE_TIMEOUT_MS = 2_000L

/**
 * Whether to draw the suggestion list, given what the probe found and what the user has done.
 *
 * Pure and separate because it is policy over three inputs, and inline in a composable it had no
 * coverage at all - the same reason `shouldClearContextMenuTarget` and
 * `browserMouseNavigationForButton` live outside their call sites in this plugin.
 *
 * (The generated-password predicate below was inserted between this doc and the function it
 * describes, which detached it. Kotlin gives a doc comment to whatever declaration follows it, so
 * inserting a declaration silently reassigns the one above.)
 */
/**
 * Whether to offer a generated password beside [field].
 *
 * @param forced the user asked for one from the right-click menu. It bypasses the new-password
 *   heuristic, and it is the only way back after the card has been dismissed - without it, waving
 *   the card away on a signup form is a dead end for that field, since the automatic offer is
 *   suppressed for exactly the box the user then wants help with. An explicit request needs no
 *   heuristic: the user has said what this box is for. It still requires a *password* box, so the
 *   menu item can never put a password into a username field.
 */
internal fun shouldOfferGeneratedPassword(
    field: FocusedLoginField?,
    dismissedId: String?,
    enabled: Boolean,
    forced: Boolean = false,
): Boolean {
    if (!enabled) return false
    if (field == null) return false
    if (forced) {
        // Deliberately skips the dismissal check as well: the request came after the dismissal.
        return field.isPassword && !field.hasValue && PasswordGenerator.fits(field.maxLength)
    }
    if (!field.isNewPassword) return false
    // Dismissed for this box on this page, same scoping as the saved-logins list.
    if (field.dismissId == dismissedId) return false
    // Something is already typed. Offering to replace a password the user has started choosing is
    // worse than staying out of the way, and this is also what closes the card after Use.
    if (field.hasValue) return false
    // A field too short to hold a decent password gets no offer at all, rather than a weak one.
    return PasswordGenerator.fits(field.maxLength)
}

internal fun shouldOfferSuggestions(
    field: FocusedLoginField?,
    dismissedId: String?,
    matchCount: Int,
): Boolean {
    if (field == null) return false
    // Dismissed for this box on this page. Scoped by dismissId, not key - see FocusedLoginField.
    if (field.dismissId == dismissedId) return false
    // Something is already typed. Offering to overwrite it is worse than staying out of the way,
    // and this is also what closes the list after a successful fill.
    if (field.hasValue) return false
    return matchCount > 0
}
