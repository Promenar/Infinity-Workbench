package com.promenar.nexara.domain.tool

import com.promenar.nexara.domain.model.ExecutionMode

enum class ToolRisk {
    SAFE_READ,
    /** 只修改会话内部状态（如任务计划），不触及工作区文件或外部系统。 */
    SESSION_STATE,
    FILE_WRITE,
    PATCH,
    DELETE,
    PROCESS,
    SCRIPT,
    EXTERNAL_WRITE,
    UNKNOWN,
}

object ToolExecutionPolicy {
    fun requiresApproval(mode: ExecutionMode, risk: ToolRisk): Boolean = when (mode) {
        ExecutionMode.AUTO -> false
        ExecutionMode.MANUAL -> true
        ExecutionMode.SEMI -> risk != ToolRisk.SAFE_READ && risk != ToolRisk.SESSION_STATE
    }
}
