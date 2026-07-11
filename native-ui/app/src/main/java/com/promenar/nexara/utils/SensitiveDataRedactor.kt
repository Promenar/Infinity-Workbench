package com.promenar.nexara.utils

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

object SensitiveDataRedactor {
    private const val REDACTED = "[REDACTED]"
    private const val SENSITIVE_KEY_PATTERN =
        "authorization|cookie|set-cookie|proxy-authorization|x-api-key|x-auth-token|api[_-]?key|access[_-]?token|refresh[_-]?token|token|prompt|response|body|bindArgs"
    private val sensitiveHeaders = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "x-api-key",
        "x-auth-token"
    )
    private val urlPattern = Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE)
    private val bearerPattern = Regex("""(?i)(bearer\s+)[^\s,;\"]+""")
    private val jsonSensitiveValuePattern = Regex(
        """(?i)(\")($SENSITIVE_KEY_PATTERN)\1(\s*:\s*)(\"(?:\\.|[^\"\\])*\")"""
    )
    private val unquotedSensitiveValuePattern = Regex(
        """(?i)(?<![\"'\w])($SENSITIVE_KEY_PATTERN)(\s*[:=]\s*)(?:\"(?:\\.|[^\"\\])*\"|\[[^\]\r\n]*\]|[^\r\n,}]+)"""
    )

    fun redactHeaders(headers: Headers): Map<String, String> = buildMap {
        headers.names().forEach { name ->
            put(name, if (name.lowercase() in sensitiveHeaders) REDACTED else headers.values(name).joinToString(", "))
        }
    }

    fun redactUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        if (parsed != null) {
            return parsed.newBuilder()
                .username("")
                .password("")
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }
        val boundary = listOf(url.indexOf('?'), url.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull()
        return if (boundary == null) url else url.substring(0, boundary)
    }

    fun redactMessage(message: String): String {
        val withoutUrlSecrets = urlPattern.replace(message) { redactUrl(it.value) }
        val withoutBearerTokens = bearerPattern.replace(withoutUrlSecrets, "$1$REDACTED")
        val withoutJsonSecrets = jsonSensitiveValuePattern.replace(withoutBearerTokens) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[1]}" +
                "${match.groupValues[3]}\"$REDACTED\""
        }
        return unquotedSensitiveValuePattern.replace(withoutJsonSecrets) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
        }
    }

    fun safeThrowable(throwable: Throwable, debug: Boolean): String {
        val category = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val causeCategory = throwable.cause?.javaClass?.simpleName
            ?.takeIf { debug && it.isNotBlank() }
            ?.let { ":$it" }
            .orEmpty()
        val code = (category + causeCategory).hashCode().toUInt().toString(16).uppercase().takeLast(8)
        return "$category$causeCategory[E-$code]"
    }
}

object HttpsUrlValidator {
    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)
}
