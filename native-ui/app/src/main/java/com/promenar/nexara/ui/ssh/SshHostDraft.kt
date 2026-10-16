package com.promenar.nexara.ui.ssh

import com.promenar.nexara.data.ssh.SshAuthType
import com.promenar.nexara.data.ssh.SshHost

/** 表单只传递非秘密配置；认证材料仅以存在性参与校验。 */
internal data class SshHostDraft(
    val alias: String,
    val hostname: String,
    val port: String,
    val username: String,
    val authType: SshAuthType,
    val keepTerminal: Boolean,
    val forwarding: Boolean,
    val localPort: String,
    val forwardHost: String,
    val forwardPort: String,
) {
    fun toHost(existing: SshHost?, hasPrivateKey: Boolean, hasPassword: Boolean, generate: Boolean): SshHost {
        val safeAlias = alias.trim()
        val safeHost = hostname.trim()
        val safeUsername = username.trim()
        val parsedPort = port.trim().toIntOrNull()
        val parsedLocal = localPort.trim().toIntOrNull()
        val parsedForward = forwardPort.trim().toIntOrNull()
        val safeForwardHost = forwardHost.trim().ifEmpty { "127.0.0.1" }
        val effectiveAuth = if (generate) SshAuthType.KEY else authType
        when {
            !Regex("^[a-z][a-z0-9_-]{0,47}$").matches(safeAlias) -> invalid("SSH_ALIAS_INVALID")
            existing != null && existing.alias != safeAlias -> invalid("SSH_ALIAS_IMMUTABLE")
            !safeHost.validHost() -> invalid("SSH_HOST_INVALID")
            parsedPort == null || parsedPort !in 1..65535 -> invalid("SSH_PORT_INVALID")
            safeUsername.isBlank() || safeUsername.any(Char::isISOControl) -> invalid("SSH_USERNAME_INVALID")
            !safeForwardHost.validHost() -> invalid("SSH_FORWARD_INVALID")
            forwarding && (parsedLocal == null || parsedLocal !in 1..65535 ||
                parsedForward == null || parsedForward !in 1..65535) -> invalid("SSH_FORWARD_INVALID")
            !generate && (existing == null || existing.authType != effectiveAuth) &&
                effectiveAuth == SshAuthType.KEY && !hasPrivateKey -> invalid("SSH_KEY_REQUIRED")
            !generate && (existing == null || existing.authType != effectiveAuth) &&
                effectiveAuth == SshAuthType.PASSWORD && !hasPassword -> invalid("SSH_PASSWORD_REQUIRED")
        }
        return (existing ?: SshHost(alias = safeAlias, hostname = safeHost, username = safeUsername)).copy(
            alias = safeAlias,
            hostname = safeHost,
            port = parsedPort!!,
            username = safeUsername,
            authType = effectiveAuth,
            keepTerminal = keepTerminal,
            localForwardPort = if (forwarding) parsedLocal else null,
            forwardHost = safeForwardHost,
            forwardPort = if (forwarding) parsedForward else null,
        )
    }

    private fun String.validHost(): Boolean = isNotBlank() && none { it.isWhitespace() || it.isISOControl() }
    private fun invalid(code: String): Nothing = throw SshInputException(code)
}
