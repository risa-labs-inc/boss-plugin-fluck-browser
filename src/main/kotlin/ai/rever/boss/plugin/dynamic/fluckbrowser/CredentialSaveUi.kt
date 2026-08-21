package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The two write-side surfaces: offering a generated password, and offering to store a typed one.
 *
 * Both are drawn through `BossPopup` by the caller, not composed in place. Chromium composites its
 * own native window over the Compose scene, so anything drawn over page content is invisible - the
 * same reason the saved-logins list and the URL autocomplete go through a popup.
 *
 * Neither one ever renders a *stored* password, even masked. [PasswordSuggestionCard] shows the
 * generated one because that is the entire point of the card - the user is being offered a value to
 * accept, and a value nobody can see is not an offer - but [SaveCredentialBar] identifies a
 * credential by its username alone.
 */

/** Wide enough for a 20-character password plus its buttons, narrow enough to sit beside a field. */
internal val SUGGESTION_CARD_WIDTH = 300.dp

/**
 * A generated password that reached Secret Manager, and what Edit needs to reopen it.
 *
 * [secretId] is resolved by re-reading the secret list after the create, because `createSecret`
 * returns `Result<Unit>` and does not hand back an id. When it cannot be resolved, Edit still opens
 * the dialog and falls back to creating - which is worse than updating, but better than an Edit
 * button that does nothing.
 */
internal data class SavedSecretNotice(
    val domain: String,
    val username: String,
    val password: String,
    val secretId: String?,
)

/** Long enough to read and act on, short enough not to sit over the page. */
internal const val SAVED_NOTICE_DURATION_MS = 8_000L

/**
 * Offer a generated password beside a new-password field.
 *
 * @param alphanumericOnly true when the site's `pattern` forced dropping punctuation, which is
 *   worth saying: a password visibly weaker than the usual one otherwise looks like a bug.
 */
@Composable
internal fun PasswordSuggestionCard(
    password: String,
    alphanumericOnly: Boolean,
    onUse: () -> Unit,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.width(SUGGESTION_CARD_WIDTH).wrapContentHeight(),
        elevation = 8.dp,
        backgroundColor = BossThemeColors.SurfaceColor,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
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
                    "Suggested password",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Hide suggestion",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            // Monospace, and wrapping rather than ellipsised: a truncated password is unreadable
            // and unverifiable, and the user may well want to read it back.
            Text(
                password,
                color = BossThemeColors.TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onUse) {
                    Text("Use", color = MaterialTheme.colors.primary, fontSize = 12.sp)
                }
                IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Generate another",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy password",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Text(
                if (alphanumericOnly) {
                    "Saved to Secret Manager when used. This site allows letters and digits only."
                } else {
                    "Saved to Secret Manager when used"
                },
                color = BossThemeColors.TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}

/** Wide enough for an email address and three controls. */
internal val SAVE_BAR_WIDTH = 380.dp

/**
 * Offer to store, or update, a credential the user just signed in with.
 *
 * [username] is editable rather than fixed because it is genuinely absent on the second screen of a
 * two-step sign-in - the identifier was on the previous page and is not in this document. Storing a
 * password with no username would make a secret nobody can match later, so the bar asks instead of
 * guessing.
 *
 * @param isUpdate true when a stored secret for this site holds a different password. Saying
 *   "Update" rather than "Save" is what tells the user their old password is about to be replaced.
 */
@Composable
internal fun SaveCredentialBar(
    domain: String,
    username: String,
    isUpdate: Boolean,
    onUsernameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onNever: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.width(SAVE_BAR_WIDTH).wrapContentHeight(),
        elevation = 8.dp,
        backgroundColor = BossThemeColors.SurfaceColor,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (isUpdate) "Update password for $domain?" else "Save password for $domain?",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Not now",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            if (username.isBlank()) {
                // No identifier came back from the page. Asked for rather than guessed: see KDoc.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .height(32.dp)
                        .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
                        .border(
                            1.dp,
                            BossThemeColors.TextSecondary.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp),
                        ).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                        cursorBrush = SolidColor(MaterialTheme.colors.primary),
                        modifier = Modifier.fillMaxWidth(),
                        // Box, not two siblings. Emitted bare into the enclosing Row they lay out
                        // side by side, so the placeholder sat next to the caret and the field
                        // jumped sideways on the first keystroke. The repo's other text fields use
                        // this shape for the same reason.
                        decorationBox = { inner ->
                            Box {
                                if (username.isEmpty()) {
                                    Text(
                                        "Username for this login",
                                        color = BossThemeColors.TextSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            } else {
                Text(
                    username,
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 32.dp, end = 12.dp, bottom = 2.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Never for this site",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onNever() }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onConfirm, enabled = username.isNotBlank()) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (username.isNotBlank()) {
                            MaterialTheme.colors.primary
                        } else {
                            BossThemeColors.TextSecondary
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isUpdate) "Update" else "Save",
                        color = if (username.isNotBlank()) {
                            MaterialTheme.colors.primary
                        } else {
                            BossThemeColors.TextSecondary
                        },
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
