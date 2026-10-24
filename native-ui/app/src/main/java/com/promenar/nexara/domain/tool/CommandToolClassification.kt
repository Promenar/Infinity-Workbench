package com.promenar.nexara.domain.tool

enum class CommandToolBackend { BUILTIN_SHELL, BUILTIN_PYTHON, TERMUX, SSH }

object CommandToolClassification {
    fun backendFor(toolName: String): CommandToolBackend? = when (toolName) {
        "shell_exec" -> CommandToolBackend.BUILTIN_SHELL
        "python_exec" -> CommandToolBackend.BUILTIN_PYTHON
        "termux_exec" -> CommandToolBackend.TERMUX
        "ssh_exec", "ssh_session_open", "ssh_session_send" -> CommandToolBackend.SSH
        else -> null
    }
}
