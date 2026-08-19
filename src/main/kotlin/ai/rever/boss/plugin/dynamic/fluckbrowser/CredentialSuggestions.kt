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
internal fun parseLoginFieldProbe(raw: String?): LoginFieldProbe =
    when {
        raw == null || raw.isBlank() -> LoginFieldProbe.NoLoginField
        raw == PROBE_NONE -> LoginFieldProbe.NoLoginField
        raw == PROBE_IDLE -> LoginFieldProbe.Idle
        else ->
            runCatching { LoginFieldProbe.Focused(probeJson.decodeFromString<FocusedLoginField>(raw)) }
                .getOrElse { LoginFieldProbe.NoLoginField }
    }

/**
 * How long to wait before probing again.
 *
 * Each probe is a synchronous round-trip into Chromium on the UI thread, so the rate has to earn
 * itself. It does that by being tied to what is actually on screen:
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
 * Answers "is the user in a login box, and where is it" in one round-trip.
 *
 * One script rather than a cheap gate plus a detailed follow-up, because two scripts would be two
 * blocking IPC calls per poll and the gate's answer is a by-product of the work the detailed one
 * already does.
 *
 * The eligibility rules mirror the host's `bossFillable`, and for the same reason: a field the
 * user cannot see is not a field to offer a credential for. Google's sign-in page carries a
 * `display: none` password input, and offering to fill *that* is what this whole change is about.
 */
/**
 * Field-eligibility helpers shared by the focus probe and the fill script.
 *
 * One copy because the two must agree on what counts as a field a person could have typed into.
 * Offering a credential for a box and then filling a different one is the failure this whole
 * change exists to remove, and two copies of these rules is how that comes back.
 *
 * The rules mirror the host's `bossFillable` deliberately - see BossConsole#215. `getClientRects()`
 * rather than `offsetParent`, because the latter is null for every `position: fixed` element and
 * would reject the visible field on a modal login.
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

internal val LOGIN_FIELD_PROBE_JS =
    """
    (function() {
$FIELD_ELIGIBILITY_JS
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
        return JSON.stringify({
            key: index + '|' + (el.name || '') + '|' + (el.id || '') + '|' + (el.type || ''),
            isPassword: (el.type || '').toLowerCase() === 'password',
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
