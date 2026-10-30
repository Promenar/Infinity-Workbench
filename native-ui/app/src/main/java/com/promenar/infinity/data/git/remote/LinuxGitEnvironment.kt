package com.promenar.infinity.data.git.remote

/** 为 Linux 原生 git 生成仅在当前命令生效的凭据代理配置。 */
object LinuxGitEnvironment {
    private val loopback = Regex("127\\.0\\.0\\.1:([0-9]{1,5})")

    fun from(lease: Map<String, String>): Map<String, String> {
        val environment = linkedMapOf("GIT_TERMINAL_PROMPT" to "0")
        val proxy = lease[GitRemoteBroker.ENV_HTTP_PROXY] ?: return environment
        val port = loopback.matchEntire(proxy)?.groupValues?.get(1)?.toIntOrNull()
        if (port == null || port !in 1..65535) return environment
        val nonce = lease[GitRemoteBroker.ENV_NONCE] ?: return environment
        if (nonce.isBlank() || nonce.any(Char::isISOControl)) return environment
        val hosts = lease[GitRemoteBroker.ENV_CREDENTIAL_HOSTS]?.split(',')
            ?.mapNotNull(GitCredentialHost::normalize)?.distinct().orEmpty()
        if (hosts.isEmpty()) return environment

        val prefix = "http://$proxy/"
        val config = hosts.map { host -> "url.$prefix$host/.insteadOf" to "https://$host/" } + listOf(
            "http.$prefix.extraHeader" to "${GitCredentialProxy.NONCE_HEADER}: $nonce",
            // 空值覆盖 HTTP(S)_PROXY；只对应用回环代理禁用上游代理。
            "http.$prefix.proxy" to "",
        )
        environment["GIT_CONFIG_COUNT"] = config.size.toString()
        config.forEachIndexed { index, (key, value) ->
            environment["GIT_CONFIG_KEY_$index"] = key
            environment["GIT_CONFIG_VALUE_$index"] = value
        }
        return environment
    }
}
