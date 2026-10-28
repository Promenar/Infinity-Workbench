package com.promenar.nexara.data.branch

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.branch.BranchLimits
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.*
import kotlinx.serialization.json.JsonObject

/** 待批准方案必须由生成运行时原子落盘，技能入口不能自行派发。 */
class SessionBranchTool : SkillDefinition {
    override val id = BranchLimits.TOOL_NAME
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val description = "提交可独立执行的工作方案，暂停等待用户选择派发到分支、在此执行或拒绝。" +
        "goal 包含目标与验收。必须单独调用；同批其它工具不会执行。分支最多一层。"
    override val parametersSchema = """{"type":"object","additionalProperties":false,"required":["title","goal","steps"],"properties":{
        "title":{"type":"string"},"goal":{"type":"string"},"steps":{"type":"array","items":{"type":"string"},"description":"1 至 12 个非空步骤"},
        "exclusions":{"type":"string"},"workMode":{"type":"string","enum":["shared","worktree"]},
        "worktree":{"type":"object","additionalProperties":false,"required":["branch","base","directory","reason"],"properties":{
        "branch":{"type":"string"},"base":{"type":"string"},"directory":{"type":"string"},"reason":{"type":"string"}}},
        "communication":{"type":"string"},"contextMode":{"type":"string","enum":["fresh","inherit"]},
        "approvalMode":{"type":"string","enum":["workspace","full_access"]}}}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext) = ToolResult(id,
        "当前运行无法创建工作方案，请在交互式主会话中调用 session_branch。", "error")
}
