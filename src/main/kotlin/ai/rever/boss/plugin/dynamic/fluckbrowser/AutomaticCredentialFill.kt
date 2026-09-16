package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.api.SecretEntryData
import java.net.URI

/** An origin that is safe for unattended credential filling. */
private data class AutomaticFillOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

private val EXPLICIT_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Tags used by Secret Manager for values that are keys/configuration, not website logins. */
private val NON_LOGIN_SECRET_TAGS =
    setOf(
        "api_key",
        "api-key",
        "apikey",
        "ai-provider",
        "ai_provider",
        "ai-provider-definition",
    )

/**
 * Select the only credential that may be filled without a human choosing it.
 *
 * Automatic fill is deliberately stricter than [matchSecretsForDomain], which remains the manual
 * picker policy. An unattended write requires an exact host and origin: sibling subdomains never
 * share credentials, ordinary HTTP is refused, and an explicitly stored scheme/port must equal
 * the page's effective origin. A bare host is shorthand for its default HTTPS origin.
 */
internal fun automaticFillCandidate(
    pageUrl: String,
    secrets: List<SecretEntryData>,
): SecretEntryData? {
    val pageOrigin = automaticPageOrigin(pageUrl) ?: return null

    return secrets
        .asSequence()
        .filter { it.username.isNotBlank() && it.password.isNotBlank() }
        .filterNot { secret ->
            secret.tags.any { tag -> tag.trim().lowercase() in NON_LOGIN_SECRET_TAGS }
        }
        .filter { secret -> automaticSecretOrigin(secret.website) == pageOrigin }
        // Inspect two eligible rows, rather than taking two raw domain matches first. Otherwise a
        // blank/API-key row between two logins can hide the second account and make the first look
        // unambiguous merely because of backend ordering.
        .take(2)
        .toList()
        .singleOrNull()
}

/** The browser `location.origin` spelling for a page eligible for automatic fill. */
internal fun automaticFillOrigin(pageUrl: String): String? =
    automaticPageOrigin(pageUrl)?.let { origin ->
        val defaultPort =
            (origin.scheme == "https" && origin.port == 443) ||
                (origin.scheme == "http" && origin.port == 80)
        "${origin.scheme}://${origin.host}${if (defaultPort) "" else ":${origin.port}"}"
    }

/** Canonical HTTP(S) origin used to bind a manual fill or persist a browser credential. */
internal fun credentialPageOrigin(pageUrl: String): String? {
    val uri = parseAbsoluteWebUri(pageUrl) ?: return null
    val scheme = uri.scheme.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = normalizedHost(uri) ?: return null
    val port = effectivePort(uri, scheme) ?: return null
    val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    return "$scheme://$host${if (defaultPort) "" else ":$port"}"
}

private fun automaticPageOrigin(pageUrl: String): AutomaticFillOrigin? {
    val uri = parseAbsoluteWebUri(pageUrl) ?: return null
    val scheme = uri.scheme.lowercase()
    val host = normalizedHost(uri) ?: return null
    if (scheme != "https" && !(scheme == "http" && isExplicitLoopback(host))) return null
    return AutomaticFillOrigin(scheme, host, effectivePort(uri, scheme) ?: return null)
}

private fun automaticSecretOrigin(website: String): AutomaticFillOrigin? {
    val value = website.trim()
    if (value.isEmpty()) return null

    val explicit = EXPLICIT_SCHEME.containsMatchIn(value)
    val uri = parseAbsoluteWebUri(if (explicit) value else "https://$value") ?: return null
    val scheme = uri.scheme.lowercase()
    val host = normalizedHost(uri) ?: return null

    // Bare entries mean the default HTTPS origin. HTTP is accepted only when a loopback entry says
    // so explicitly; this keeps `localhost` convenient without making an omitted scheme weaken the
    // transport policy or blur ports between local applications.
    if (!explicit && scheme != "https") return null
    if (explicit && scheme != "https" && !(scheme == "http" && isExplicitLoopback(host))) return null

    return AutomaticFillOrigin(scheme, host, effectivePort(uri, scheme) ?: return null)
}

private fun parseAbsoluteWebUri(value: String): URI? =
    runCatching { URI(value) }
        .getOrNull()
        ?.takeIf { it.isAbsolute && !it.host.isNullOrBlank() && it.userInfo == null }

private fun normalizedHost(uri: URI): String? =
    uri.host
        ?.trim()
        ?.lowercase()
        ?.removeSuffix(".")
        ?.takeIf { it.isNotEmpty() }

private fun effectivePort(uri: URI, scheme: String): Int? =
    when {
        uri.port in 1..65535 -> uri.port
        uri.port != -1 -> null
        scheme == "https" -> 443
        scheme == "http" -> 80
        else -> null
    }

private fun isExplicitLoopback(host: String): Boolean =
    host == "localhost" ||
        (host.startsWith("127.") && host.substringAfter("127.").split('.').all { part ->
            part.toIntOrNull() in 0..255
        })
