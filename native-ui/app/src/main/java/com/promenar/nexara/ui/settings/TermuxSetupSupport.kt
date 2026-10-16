package com.promenar.nexara.ui.settings

import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.termux.TermuxCommandResult
import com.promenar.nexara.domain.storage.ExternalFolderException

internal object TermuxSetupSupport {
    private val publicKeyPattern = Regex("(?:ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)) [A-Za-z0-9+/]+={0,2}(?: .+)?")

    fun shellQuote(value: String): String {
        require('\u0000' !in value) { "命令参数包含无效字符" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun authorizedKeysCommand(publicKey: String): String {
        require(publicKey.none(Char::isISOControl)) { "公钥必须为单行 OpenSSH 公钥" }
        val key = publicKey.trim()
        require(publicKeyPattern.matches(key)) { "公钥必须为单行 OpenSSH 公钥" }
        return "(umask 077 && mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
            "printf '\\n%s\\n' ${shellQuote(key)} >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys)"
    }

    fun normalizeMountAlias(alias: String, existingAliases: Set<String>): String {
        require(alias.none(Char::isISOControl)) { "别名不能包含换行或控制字符" }
        val normalized = try {
            ExternalMountPaths.validateAlias(alias)
        } catch (_: ExternalFolderException) {
            throw IllegalArgumentException("请输入有效别名，不能使用路径或保留名称")
        }
        require(normalized !in existingAliases) { "别名已存在，请选择其它名称" }
        return normalized
    }

    fun availableMountAlias(existingAliases: Set<String>): String {
        if ("termux" !in existingAliases) return "termux"
        return generateSequence(2) { it + 1 }.map { "termux-$it" }.first { it !in existingAliases }
    }

    fun diagnosticSucceeded(result: TermuxCommandResult): Boolean =
        !result.timedOut && result.errorCode == null && result.exitCode == 0 && result.stdout.trim() == "NEXARA_TERMUX_OK"
}
