package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.domain.tool.ToolRisk
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
        val configuredSkills = settings.getStringSet(ENABLED_SKILLS_KEY, null)?.toSet()
        val enabledRuntimeIds = if (configuredSkills == null) {
            allTools.filter { it.sourceId == "builtin" }
                .mapTo(mutableSetOf()) { it.runtimeToolId.ifBlank { it.function.name } }
        } else {
            configuredSkills.mapTo(mutableSetOf()) { GLOBAL_SKILL_ALIASES[it] ?: it }
        }
        val legacyEmptySelection = session.activeSkillIds.isEmpty() &&
            session.activeMcpServerIds.isEmpty()
        return allTools.filter { tool ->
            when (tool.sourceId) {
                "builtin" -> tool.runtimeToolId.ifBlank { tool.function.name } in enabledRuntimeIds &&
                    (!legacyEmptySelection || tool.risk == ToolRisk.SAFE_READ)
                // 自定义 DB/script Skill 没有隔离执行器，当前版本只能编辑，不能广告或执行。
                "custom" -> false
                "mcp" -> tool.mcpServerId != null && tool.mcpServerId in session.activeMcpServerIds
                else -> false
            }
        }
    }

    private companion object {
        const val ENABLED_SKILLS_KEY = "enabled_skills"
        val GLOBAL_SKILL_ALIASES = mapOf(
            "file_read" to "read_file",
            "file_write" to "write_file",
            "file_list" to "list_files",
            "file_search" to "search_files",
            "file_diff" to "diff_file",
            "file_patch" to "patch_file",
        )
    }
}
