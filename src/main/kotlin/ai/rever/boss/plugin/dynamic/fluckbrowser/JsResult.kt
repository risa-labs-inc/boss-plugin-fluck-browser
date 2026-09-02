package ai.rever.boss.plugin.dynamic.fluckbrowser

/**
 * Normalizes an `executeJavaScript` return value that is meant to be a plain string.
 *
 * `BrowserHandle.executeJavaScript` implementations differ on whether a returned JS string
 * arrives already unwrapped or still `"quoted"` - this plugin had three independent copies of
 * the same trim-then-strip-quotes-then-trim logic to cope with it
 * ([TabHibernation.busyStateFromScriptResult], [CredentialSuggestions]'s login-field probe
 * parser, [ScrollRestore.parseCapture]), each commenting on the same quoting variance rather
 * than sharing a fix for it. One helper here so a change to that variance is one edit, not three.
 *
 * The trailing trim (after stripping quotes) is not redundant with the leading one: it recovers
 * from a value like `"' foo '"` where whitespace sits *inside* the quotes - the leading trim
 * only ever sees the outside.
 *
 * **String-shaped, not JSON-shaped**, which matters now that [CredentialSuggestions]'s login-field
 * probe (whose payload IS JSON) shares it: `trim('"')` strips every leading and trailing quote
 * character and unescapes nothing, so a host that ever marshalled that probe's result as a QUOTED
 * JSON string would leave the inner `\"` escapes intact and the decode would fall through to its
 * no-field default. Not a regression - the copy this replaced did exactly the same thing - but the
 * shared helper is the wrong place to fix it if that host ever appears: a JSON caller needs its
 * own unwrapping, not a widening of this one, which every string caller would then pay for.
 */
internal fun normalizeJsStringResult(result: Any?): String? = result?.toString()?.trim()?.trim('"')?.trim()
