package com.promenar.nexara.ui.settings

import com.promenar.nexara.data.termux.TermuxCommandResult

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

    fun diagnosticSucceeded(result: TermuxCommandResult): Boolean =
        !result.timedOut && result.errorCode == null && result.exitCode == 0 && result.stdout.trim() == "NEXARA_TERMUX_OK"
}
