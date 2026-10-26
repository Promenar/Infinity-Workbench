package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry

/** 会话在当前时刻实际有权广告和执行的工具集合；提示构建与执行重验必须共用此策略。 */
fun interface SessionToolResolver {
    fun resolve(session: Session): List<ProtocolTool>
    suspend fun resolveForExecution(session: Session): List<ProtocolTool> = resolve(session)
}

class DefaultSessionToolResolver(
    private val settings: SharedPreferences,
    private val skillRegistry: SkillRegistry?,
    private val longTermMemoryAllowed: (Session) -> Boolean = { false },
    private val mediaCapabilities: (Session) -> com.promenar.nexara.data.model.ModelCapabilities = { com.promenar.nexara.data.model.ModelCapabilities() },
) : SessionToolResolver {
    override fun resolve(session: Session): List<ProtocolTool> {
        if (!session.options.toolsEnabled) return emptyList()
        val allTools = skillRegistry?.getAllTools(null).orEmpty()
        val memoryToolsAllowed = allTools.none { it.sourceId == "builtin" && it.runtimeToolId.ifBlank { it.function.name } in com.promenar.nexara.data.memory.LongTermMemoryPreferences.TOOL_IDS } ||
            (com.promenar.nexara.data.memory.LongTermMemoryPreferences.isEnabled(settings) && longTermMemoryAllowed(session))
        val capabilities = mediaCapabilities(session)
        val canView = capabilities.vision || capabilities.audioInput || capabilities.videoUnderstanding
        return allTools.filter { tool ->
            if (tool.sourceId == "builtin" && tool.runtimeToolId.ifBlank { tool.function.name } in com.promenar.nexara.data.memory.LongTermMemoryPreferences.TOOL_IDS && !memoryToolsAllowed) return@filter false
            if (tool.sourceId == "builtin" && session.runMode == com.promenar.nexara.domain.model.SessionRunMode.LINUX &&
                tool.runtimeToolId.ifBlank { tool.function.name } in LINUX_HIDDEN_TOOLS) return@filter false
            if (tool.sourceId == "builtin" && tool.runtimeToolId.ifBlank { tool.function.name } == "view_media" && !canView) return@filter false
            when (tool.sourceId) {
                // 内置工具只受全局启用状态约束；写入、删除、脚本等风险由会话执行模式统一审批。
                // 会话 activeSkillIds 只选择自定义 Skill，其是否为空不得影响内置工具集合。
                "builtin" -> BuiltinToolPreferences.isEnabled(settings, tool.runtimeToolId.ifBlank { tool.function.name })
                // 自定义 DB/script Skill 没有隔离执行器，当前版本只能编辑，不能广告或执行。
                "custom" -> false
                "mcp" -> tool.mcpServerId != null && tool.mcpServerId in session.activeMcpServerIds
                else -> false
            }
        }.map { tool -> SessionRunModeToolDefinitions.forSession(tool, session.runMode) }
    }

    companion object {
        val LINUX_HIDDEN_TOOLS = setOf("python_exec", "python_sandbox", "exec_js", "run_script", "termux_exec")
    }
}
