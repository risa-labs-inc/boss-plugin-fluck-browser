package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.UrlHistoryEntry
import ai.rever.boss.plugin.browser.BrowserAddressBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Adapts AppKit editing to the same callbacks and suggestion state as BrowserToolbar. */
@Composable
internal fun rememberNativeAddressBar(
    value: TextFieldValue,
    completion: String?,
    suggestions: List<UrlHistoryEntry>,
    showing: Boolean,
    selected: Int,
    onEdit: (TextFieldValue) -> Unit,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onAccept: () -> Unit,
    onSelected: (Int) -> Unit,
    onDelete: (UrlHistoryEntry) -> Unit,
    onFocusLost: () -> Unit,
    suggestionsContent: @Composable () -> Unit = {},
): BrowserAddressBarState {
    var revision by remember { mutableIntStateOf(0) }
    val locals = currentCompositionLocalContext
    return BrowserAddressBarState(
        value.text, value.selection.start, value.selection.end, completion,
        showing && suggestions.isNotEmpty(), selected in suggestions.indices, revision,
        onEdit = { text, start, end -> onEdit(TextFieldValue(text, TextRange(start, end))) },
        onCommand = { command ->
            revision++
            when (command) {
                "submit" -> {
                    val target = resolveAddressSubmission(value.text, suggestions, selected, completion != null)
                    onDismiss()
                    onNavigate(target)
                }
                "next" -> if (showing) onSelected((selected + 1).coerceAtMost(suggestions.lastIndex))
                "previous" -> if (showing) onSelected((selected - 1).coerceAtLeast(-1))
                "accept" -> onAccept()
                "right" -> { onAccept(); onDismiss() }
                "cancel" -> if (showing && suggestions.isNotEmpty()) onDismiss() else onCancel()
                "delete" -> suggestions.getOrNull(selected)?.let(onDelete)
            }
        },
        onFocusLost = onFocusLost,
        onDismiss = onDismiss,
        suggestions = { CompositionLocalProvider(locals) { suggestionsContent() } },
    )
}

/** Both URL fields commit the same explicit row, inline suggestion, or typed input. */
internal fun resolveAddressSubmission(
    text: String,
    suggestions: List<UrlHistoryEntry>,
    selected: Int,
    hasInlineCompletion: Boolean,
): String = when {
    selected in suggestions.indices -> suggestions[selected].url
    hasInlineCompletion && suggestions.isNotEmpty() -> suggestions.first().url
    else -> processUrlInput(text)
}
