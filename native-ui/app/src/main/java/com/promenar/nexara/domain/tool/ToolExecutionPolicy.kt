package com.promenar.nexara.domain.tool

import com.promenar.nexara.domain.model.ExecutionMode

enum class ToolRisk {
    SAFE_READ,
    /** 只修改会话内部状态（如任务计划），不触及工作区文件或外部系统。 */
    SESSION_STATE,
    /** 创建会在未来自行执行的任务，所有模式均需用户批准。 */
    SCHEDULE,
    FILE_WRITE,
    PATCH,
    DELETE,
    PROCESS,
    SCRIPT,
    EXTERNAL_WRITE,
    UNKNOWN,
}

object ToolExecutionPolicy {
    fun requiresApproval(
        mode: ExecutionMode,
        toolName: String,
        risk: ToolRisk,
        arguments: String = "{}",
        resolvedTarget: String? = null,
    ): Boolean = risk == ToolRisk.SCHEDULE || toolName == "schedule_create" ||
        ToolArgumentApprovalRules.requiresExplicitApproval(toolName, arguments, resolvedTarget) ||
        (mode == ExecutionMode.WORKSPACE && CommandToolClassification.backendFor(toolName) != null)
}
