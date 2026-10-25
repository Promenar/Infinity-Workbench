package com.promenar.nexara.ui.chat.manager.registry

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.serialization.json.JsonObject

interface SkillRegistry {
    fun getSkill(name: String): SkillDefinition?
    fun getSkillByRuntimeToolId(runtimeToolId: String): SkillDefinition? =
        getAllSkills().singleOrNull { it.runtimeToolId == runtimeToolId }
            ?: getSkill(runtimeToolId)?.takeIf { it.runtimeToolId == runtimeToolId }
    fun getAllSkills(): List<SkillDefinition>
    fun getAllTools(allowedIds: List<String>? = null): List<ProtocolTool>
}

interface SkillDefinition {
    val id: String
    val runtimeToolId: String get() = id
    val sourceId: String get() = "builtin"
    val name: String
    val description: String
    val mcpServerId: String?
    val parametersSchema: String
    /** 按当前配置决定是否向模型暴露；技能登记与设置列表不受影响。 */
    val isAvailable: Boolean get() = true
    val risk: ToolRisk get() = ToolRisk.UNKNOWN
    suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult
}

fun SkillDefinition.toProtocolTool(): ProtocolTool = ProtocolTool(
    type = "function",
    function = ProtocolToolFunction(
        name = name,
        description = description,
        parameters = parametersSchema,
    ),
    risk = risk,
    runtimeToolId = runtimeToolId,
    sourceId = sourceId,
    mcpServerId = mcpServerId,
)

interface SkillExecutionContext {
    val sessionId: String
    val agentId: String
    /** 该调用准入时的会话模式快照，后端不能把已批准的调用改到其它环境。 */
    val runMode: com.promenar.nexara.domain.model.SessionRunMode? get() = null
    /** 发起本次工具调用的助手消息，供会话状态版本绑定与会话画布记录来源。 */
    val assistantMessageId: String? get() = null
    val workspacePath: String?
    val workspaceRootUuid: String
    /** 执行底座已核验的审批目标，供技能固定来源身份。 */
    val approvedExecutionTarget: String? get() = null
    val isApprovalBoundExecution: Boolean get() = false
    /** 运行中输出推送到界面的通道；只用于展示。 */
    val liveOutput: com.promenar.nexara.domain.tool.ToolLiveOutputSink
        get() = com.promenar.nexara.domain.tool.ToolLiveOutputSink.None
    /** 本次调用的执行步骤 id，供需要按调用归档过程的工具使用。 */
    val toolStepId: String? get() = null
}
