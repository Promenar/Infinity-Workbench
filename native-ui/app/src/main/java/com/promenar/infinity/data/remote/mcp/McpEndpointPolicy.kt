package com.promenar.infinity.data.remote.mcp

import java.net.URI

/** MCP 的传输与凭据边界。 */
object McpEndpointPolicy {
    enum class Kind { HTTPS, LOOPBACK_HTTP }

    fun kind(url: String): Kind? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return when {
            uri.scheme.equals("https", ignoreCase = true) -> Kind.HTTPS
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host == "127.0.0.1" && uri.port in 1..65535 &&
                LOOPBACK_AUTHORITY.matches(uri.rawAuthority.orEmpty()) && uri.rawFragment == null -> Kind.LOOPBACK_HTTP
            else -> null
        }
    }

    fun isAllowed(
        type: String,
        url: String,
        allowLoopbackHttp: Boolean = false,
        hasBearerToken: Boolean = false,
    ): Boolean = type.equals("http", ignoreCase = true) && when (kind(url)) {
        Kind.HTTPS -> true
        Kind.LOOPBACK_HTTP -> allowLoopbackHttp && hasBearerToken
        null -> false
    }

    fun isValidBearerToken(token: String): Boolean = BEARER_TOKEN.matches(token)

    private val LOOPBACK_AUTHORITY = Regex("127\\.0\\.0\\.1:[0-9]{1,5}")
    private val BEARER_TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")
}
