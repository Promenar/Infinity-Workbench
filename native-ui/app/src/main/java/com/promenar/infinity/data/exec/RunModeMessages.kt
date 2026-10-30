package com.promenar.infinity.data.exec

import com.promenar.infinity.data.git.remote.GitCredentialHost
import java.net.URI

/** 执行层保持可在 JVM 验证，应用通过资源函数注入当前语言。 */
enum class RunModeMessage { LINUX_UNAVAILABLE, MODE_REQUIRED, BUILTIN_TOOL_BLOCKED, MODE_CHANGED, INSTALL_PACKAGE, SEARCH_PACKAGE }

class RunModeMessages(private val text: (RunModeMessage) -> String = { key ->
    when (key) {
        RunModeMessage.LINUX_UNAVAILABLE -> "该会话使用 Linux 模式，但 Linux 环境当前不可用。请在会话中修复环境或手动切换运行模式。"
        RunModeMessage.MODE_REQUIRED -> "请先在会话中选择运行模式。"
        RunModeMessage.MODE_CHANGED -> "会话运行模式已改变，该调用未执行。请按当前模式重新发起工具调用。"
        RunModeMessage.BUILTIN_TOOL_BLOCKED -> "该会话使用 Linux 模式，请用 shell_exec 执行 Linux 命令；不要改用其它运行环境。"
        RunModeMessage.INSTALL_PACKAGE -> "可以用 apk add %s 安装缺少的软件包（遵循命令审批）；不要改用其它运行环境。"
        RunModeMessage.SEARCH_PACKAGE -> "可以用 apk search 查找软件包，再用 apk add 安装（遵循命令审批）；不要改用其它运行环境。"
    }
}) {
    operator fun invoke(key: RunModeMessage): String = text(key)
}

object LinuxMissingCommandGuidance {
    private val packages = mapOf("git" to "git", "python" to "python3", "python3" to "python3", "pip" to "py3-pip", "pip3" to "py3-pip", "bash" to "bash", "curl" to "curl")
    private val missing = Regex("([A-Za-z0-9_.+/-]+): (?:command )?not found\\b", RegexOption.IGNORE_CASE)

    fun hint(exitCode: Int?, output: String, messages: RunModeMessages = RunModeMessages()): String? {
        if (exitCode != 127) return LinuxGitAuthGuidance.hint(exitCode, output)
        val command = missing.find(output)?.groupValues?.get(1)?.substringAfterLast('/')
            ?: return LinuxGitAuthGuidance.hint(exitCode, output)
        val pkg = packages[command] ?: return messages(RunModeMessage.SEARCH_PACKAGE)
        return messages(RunModeMessage.INSTALL_PACKAGE).replace("%1\$s", pkg).replace("%s", pkg)
    }
}

/** 原生 git 的 401 响应正文可能被凭据交互错误覆盖，按实际错误补齐设置入口。 */
object LinuxGitAuthGuidance {
    private val promptDisabled = Regex("could not read (?:Username|Password) for '(https?://[^'\\r\\n]+)': terminal prompts disabled")
    private val remoteExplanation = Regex("(?m)^remote: .*?[\\u3400-\\u9fff]")
    private const val SETTINGS = "设置 → 执行环境与连接 → Git 凭据"

    fun hint(exitCode: Int?, output: String): String? {
        if (exitCode == null || exitCode == 0 || remoteExplanation.containsMatchIn(output)) return null
        val address = promptDisabled.find(output)?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(address) }.getOrNull() ?: return null
        return when {
            uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 ->
                "远端拒绝了已保存的令牌（HTTP 401），令牌可能已过期或被撤销。" +
                    "请让用户在 $SETTINGS 中重新登录 GitHub 或更换该主机的令牌，不要重试这条命令；无需在 Linux 中另行登录。"
            uri.scheme == "https" && GitCredentialHost.of(uri.host ?: return null, uri.port) != null ->
                "该 HTTPS 主机没有可用的已保存凭据。请让用户在 $SETTINGS 中保存该主机的凭据或登录 GitHub，然后重试；无需在 Linux 中另行登录。"
            else -> null
        }
    }
}
