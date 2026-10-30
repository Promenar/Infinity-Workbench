package com.promenar.infinity.ui.ssh

import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.tailnet.TailnetHosts
import java.util.Locale

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
    /**
     * 新建主机且主机 ID 留空时按主机名生成；[takenAliases] 为已有主机 ID，新建时不得重复。
     * 主机 ID 规则不放宽，生成值本身满足同一规则。
     */
    fun toHost(
        existing: SshHost?,
        hasPrivateKey: Boolean,
        hasPassword: Boolean,
        generate: Boolean,
        takenAliases: Set<String> = emptySet(),
    ): SshHost {
        val safeAlias = alias.trim().ifEmpty {
            if (existing == null) suggestAlias(hostname, takenAliases).orEmpty() else ""
        }
        val safeHost = hostname.trim()
        val safeUsername = username.trim()
        val parsedPort = port.trim().toIntOrNull()
        val parsedLocal = localPort.trim().toIntOrNull()
        val parsedForward = forwardPort.trim().toIntOrNull()
        val safeForwardHost = forwardHost.trim().ifEmpty { "127.0.0.1" }
        val effectiveAuth = if (generate) SshAuthType.KEY else authType
        when {
            !ALIAS.matches(safeAlias) -> invalid("SSH_ALIAS_INVALID")
            existing != null && existing.alias != safeAlias -> invalid("SSH_ALIAS_IMMUTABLE")
            existing == null && safeAlias in takenAliases -> invalid("SSH_ALIAS_TAKEN")
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

    companion object {
        private val ALIAS = Regex("^[a-z][a-z0-9_-]{0,47}$")
        private const val ALIAS_MAX = 48

        /** 输入框只做小写化；其余字符原样保留，由保存时的规则校验给出提示。 */
        fun normalizeAliasInput(value: String): String = value.lowercase(Locale.ROOT)

        /**
         * 由主机名生成主机 ID：域名取第一段（`fnos.tail1.ts.net` → `fnos`），IP 字面量整体保留并加 `host-` 前缀；
         * 非法字符折叠为 `-`，与已有主机 ID 冲突时追加序号。主机名为空或无可用字符时返回 null。
         */
        fun suggestAlias(hostname: String, takenAliases: Set<String> = emptySet()): String? {
            val name = TailnetHosts.normalize(hostname)
            if (name.isEmpty()) return null
            val isAddress = TailnetHosts.parseAddressLiteral(name) != null
            val source = if (isAddress) name else name.substringBefore('.')
            val slug = source.replace(Regex("[^a-z0-9_-]+"), "-").trim('-', '_')
            if (slug.isEmpty()) return null
            val base = (if (isAddress || !slug.first().isLetter()) "host-$slug" else slug).take(ALIAS_MAX).trimEnd('-', '_')
            if (base !in takenAliases) return base
            return (2..999).asSequence().map { index ->
                val suffix = "-$index"
                base.take(ALIAS_MAX - suffix.length).trimEnd('-', '_') + suffix
            }.firstOrNull { it !in takenAliases }
        }
    }
}
