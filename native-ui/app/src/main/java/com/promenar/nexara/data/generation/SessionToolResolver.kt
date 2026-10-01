package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry

/** 会话在当前时刻实际有权广告和执行的工具集合；提示构建与执行重验必须共用此策略。 */
fun interface SessionToolResolver {
    fun resolve(session: Session): List<ProtocolTool>
}

class DefaultSessionToolResolver(
    private val settings: SharedPreferences,
    private val skillRegistry: SkillRegistry?,
) : SessionToolResolver {
    override fun resolve(session: Session): List<ProtocolTool> {
        if (!session.options.toolsEnabled) return emptyList()
        val allTools = skillRegistry?.getAllTools(null).orEmpty()
        return allTools.filter { tool ->
            when (tool.sourceId) {
                // 内置工具只受全局启用状态约束；写入、删除、脚本等风险由会话执行模式统一审批。
                // 会话 activeSkillIds 只选择自定义 Skill，其是否为空不得影响内置工具集合。
                "builtin" -> BuiltinToolPreferences.isEnabled(settings, tool.runtimeToolId.ifBlank { tool.function.name })
                // 自定义 DB/script Skill 没有隔离执行器，当前版本只能编辑，不能广告或执行。
                "custom" -> false
                "mcp" -> tool.mcpServerId != null && tool.mcpServerId in session.activeMcpServerIds
                else -> false
            }
        }
    }
}
