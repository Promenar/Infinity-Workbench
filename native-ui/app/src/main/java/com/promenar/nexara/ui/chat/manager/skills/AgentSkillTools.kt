package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.skills.AgentSkillFormatException
import com.promenar.nexara.data.skills.AgentSkillStore
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import com.promenar.nexara.ui.chat.manager.registry.toProtocolTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 加载已启用技能的完整指令；技能正文由用户安装，按用户授权的指令对待。 */
class ActivateSkillTool(private val store: AgentSkillStore) : SkillDefinition {
    override val id = "activate_skill"
    override val name = "activate_skill"
    override val description = "加载一个已安装技能的完整指令。当任务与 System Prompt 中某个技能的描述相符时，先调用本工具再开始工作，并遵循返回的指令。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"name":{"type":"string","description":"技能名称"}},"required":["name"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val skillName = args.stringArgument("name")?.trim().orEmpty()
        val installed = store.find(skillName)
        if (installed == null || !installed.enabled) {
            val available = store.enabledSkills().joinToString(", ") { it.name }.ifEmpty { "无" }
            return ToolResult("activate_skill_${System.currentTimeMillis()}", "未找到已启用的技能 `$skillName`。可用技能：$available", "error")
        }
        val document = withContext(Dispatchers.IO) { store.readDocument(skillName) }
            ?: return ToolResult("activate_skill_${System.currentTimeMillis()}", "技能 `$skillName` 的 SKILL.md 无法读取", "error")
        return ToolResult(
            "activate_skill_${System.currentTimeMillis()}",
            buildString {
                appendLine("# Skill: ${document.metadata.name}")
                appendLine("Instructions installed by the user. Follow them for the current task.")
                appendLine()
                appendLine(document.body.ifBlank { "(empty)" })
                if (installed.files.isNotEmpty()) {
                    appendLine()
                    appendLine("## Bundled files")
                    appendLine("Read with read_skill_file(name=\"$skillName\", path=...). Scripts are reference only and cannot be executed.")
                    installed.files.take(MAX_LISTED_FILES).forEach { appendLine("- $it") }
                    if (installed.files.size > MAX_LISTED_FILES) appendLine("- … (+${installed.files.size - MAX_LISTED_FILES})")
                }
            }.trimEnd(),
        )
    }

    private companion object {
        const val MAX_LISTED_FILES = 100
    }
}

/** 读取已启用技能附带的文本文件。 */
class ReadSkillFileTool(private val store: AgentSkillStore) : SkillDefinition {
    override val id = "read_skill_file"
    override val name = "read_skill_file"
    override val description = "读取已启用技能包中附带的文本文件（参考资料、模板等）。路径来自 activate_skill 返回的文件清单。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"name":{"type":"string","description":"技能名称"},"path":{"type":"string","description":"技能包内相对路径"}},"required":["name","path"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val skillName = args.stringArgument("name")?.trim().orEmpty()
        val path = args.stringArgument("path").orEmpty()
        if (store.find(skillName)?.enabled != true) {
            return ToolResult("read_skill_file_${System.currentTimeMillis()}", "未找到已启用的技能 `$skillName`", "error")
        }
        return try {
            val text = withContext(Dispatchers.IO) { store.readFile(skillName, path) }
            ToolResult("read_skill_file_${System.currentTimeMillis()}", text)
        } catch (failure: AgentSkillFormatException) {
            ToolResult("read_skill_file_${System.currentTimeMillis()}", failure.message.orEmpty(), "error")
        }
    }
}

/** 仅在存在已启用技能时广告 activate_skill / read_skill_file；执行查找始终可用以便失败关闭重验。 */
class AgentSkillToolRegistry(private val store: AgentSkillStore) : SkillRegistry {
    private val tools: List<SkillDefinition> = listOf(ActivateSkillTool(store), ReadSkillFileTool(store))

    override fun getSkill(name: String): SkillDefinition? = tools.firstOrNull { it.name == name }

    override fun getAllSkills(): List<SkillDefinition> = tools

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        if (store.enabledSkills().isEmpty()) return emptyList()
        return tools.filter { allowedIds == null || it.id in allowedIds }.map(SkillDefinition::toProtocolTool)
    }
}
