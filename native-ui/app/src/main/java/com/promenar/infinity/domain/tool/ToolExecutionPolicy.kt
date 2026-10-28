package com.promenar.infinity.domain.tool

import com.promenar.infinity.domain.model.ExecutionMode

enum class ToolRisk {
    SAFE_READ,
    /** 只修改会话内部状态（如任务计划），不触及工作区文件或外部系统。 */
    SESSION_STATE,
    /** 创建会在未来自行执行的任务，所有模式均需用户批准。 */
    SCHEDULE,
    /** 授予智能体新的数据访问权限，所有模式均需用户批准。 */
    ACCESS_GRANT,
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
        usesLinux: Boolean = false,
    ): Boolean = risk in setOf(ToolRisk.SCHEDULE, ToolRisk.ACCESS_GRANT) || toolName in setOf("schedule_create", "request_knowledge_access") ||
        ToolArgumentApprovalRules.requiresExplicitApproval(toolName, arguments, resolvedTarget) ||
        (mode == ExecutionMode.WORKSPACE && (CommandToolClassification.backendFor(toolName, usesLinux) != null ||
            usesLinux && ToolExecutionTargets.isPathMutation(toolName) && isLinuxRootfsTarget(resolvedTarget)))

    private fun isLinuxRootfsTarget(target: String?): Boolean = runCatching {
        val parts = kotlinx.serialization.json.Json.parseToJsonElement(target ?: return false) as? kotlinx.serialization.json.JsonArray
        val metadata = parts?.lastOrNull() as? kotlinx.serialization.json.JsonObject
        (metadata?.get("linuxRootfs") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    }.getOrDefault(false)
}
