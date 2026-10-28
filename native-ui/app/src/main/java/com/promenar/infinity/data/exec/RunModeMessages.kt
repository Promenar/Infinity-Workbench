package com.promenar.infinity.data.exec

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
        if (exitCode != 127) return null
        val command = missing.find(output)?.groupValues?.get(1)?.substringAfterLast('/') ?: return null
        val pkg = packages[command] ?: return messages(RunModeMessage.SEARCH_PACKAGE)
        return messages(RunModeMessage.INSTALL_PACKAGE).replace("%1\$s", pkg).replace("%s", pkg)
    }
}
