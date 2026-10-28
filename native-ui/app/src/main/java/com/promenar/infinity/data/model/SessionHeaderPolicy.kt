package com.promenar.infinity.data.model

import java.security.MessageDigest

enum class SessionHeaderMode { INHERIT, ON, OFF;
    companion object {
        fun fromStored(value: String?): SessionHeaderMode = entries.firstOrNull { it.name == value } ?: INHERIT
    }
}

object SessionHeaderPolicy {
    const val DEFAULT_NAME = "x-opencode-session"
    private val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val protectedNames = setOf(
        "user-agent", "authorization", "proxy-authorization", "host", "content-type", "content-length",
        "connection", "transfer-encoding", "accept", "accept-encoding", "cache-control", "cookie",
        "x-api-key", "x-goog-api-key", "anthropic-version", "anthropic-beta",
    )

    fun isValidName(name: String): Boolean = token.matches(name) && name.lowercase(java.util.Locale.ROOT) !in protectedNames

    fun resolveName(enabled: Boolean, name: String, mode: SessionHeaderMode): String? {
        if (!(when (mode) { SessionHeaderMode.INHERIT -> enabled; SessionHeaderMode.ON -> true; SessionHeaderMode.OFF -> false })) return null
        return name.ifBlank { DEFAULT_NAME }.takeIf(::isValidName)
    }

    fun identifier(salt: String, sessionId: String): String {
        require(salt.isNotBlank() && sessionId.isNotBlank()) { "会话标识输入不能为空" }
        return MessageDigest.getInstance("SHA-256")
            .digest("$salt\u0000$sessionId".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
