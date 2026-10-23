package com.promenar.nexara.ui.chat.command

import com.promenar.nexara.data.skills.DiscoveredSkill
import com.promenar.nexara.data.skills.SkillScope
import com.promenar.nexara.data.skills.SkillSlashInvocation
import com.promenar.nexara.data.skills.SkillStatus

/**
 * 在本机执行、不发给模型的内置命令。只收录从聊天页出发需要两步及以上操作的动作；
 * [aliases] 同时收录中英文叫法，与界面语言无关。
 */
enum class BuiltinSlashCommand(val commandName: String, val aliases: List<String>) {
    NEW("new", listOf("新会话", "新建", "新对话", "new-chat")),
    RENAME("rename", listOf("重命名", "改名", "标题", "title")),
    PROMPT("prompt", listOf("提示词", "会话提示词", "system", "instructions")),
    COMPACT("compact", listOf("压缩", "压缩上下文", "summarize", "summary")),
    CLEAR("clear", listOf("清空", "清空历史", "reset")),
    MODE("mode", listOf("执行模式", "模式", "审批", "approval")),
    TOOLS("tools", listOf("工具", "工具调用")),
    EXPORT("export", listOf("导出", "导出会话", "save")),
    ;

    companion object {
        /** 按命令名精确查找（不区分大小写）。 */
        fun byName(token: String): BuiltinSlashCommand? = token.lowercase().let { key -> entries.firstOrNull { it.commandName == key } }

        /** 按别名精确查找（不区分大小写）；同名技能优先于别名，由调用方先查技能。 */
        fun byAlias(token: String): BuiltinSlashCommand? = token.lowercase().let { key ->
            entries.firstOrNull { command -> command.aliases.any { it.lowercase() == key } }
        }
    }
}

/** 命令来源：内置命令，或全局（用户级）、工作区（项目级）技能。 */
enum class SlashCommandSource { BUILTIN, GLOBAL_SKILL, WORKSPACE_SKILL }

/**
 * 命令目录中的一项。界面只负责展示与回调；匹配、排序与发送前判定都在纯 Kotlin 中完成，
 * 以后增加用户自定义命令时只需新增来源。
 */
data class SlashCommand(
    /** 输入框中填写的命令名，不含 `/`。 */
    val name: String,
    /** 显示名：内置命令为当前语言的名称，技能为 `display_name`（缺省时为 null）。 */
    val displayName: String?,
    /** 一行简短说明。 */
    val description: String,
    val source: SlashCommandSource,
    val aliases: List<String> = emptyList(),
    val builtin: BuiltinSlashCommand? = null,
) {
    val isSkill: Boolean get() = builtin == null
}

/** 命令目录：触发条件、过滤排序与技能命令的生成。 */
object SlashCommandCatalog {
    /**
     * 输入框文本以 `/` 开头且光标位于第一个词内时返回第一个词（不含 `/`）作为过滤词，否则返回 null。
     * 第一个词里再出现 `/` 时按路径处理，不弹出候选。
     */
    fun triggerQuery(text: String, cursor: Int): String? {
        if (!text.startsWith("/")) return null
        val wordEnd = text.indexOfFirst(Char::isWhitespace).let { if (it < 0) text.length else it }
        if (cursor < 1 || cursor > wordEnd) return null
        val word = text.substring(1, wordEnd)
        return word.takeUnless { '/' in it }
    }

    /**
     * 把已启用技能转为命令：同名时只保留一条（工作区优先，与发送时的选取规则一致），
     * 与内置命令名重名的技能不进入目录（输入同名命令时执行内置命令）；与别名重名时技能优先。
     */
    fun skillCommands(skills: List<DiscoveredSkill>): List<SlashCommand> {
        val reserved = BuiltinSlashCommand.entries.mapTo(mutableSetOf()) { it.commandName }
        return skills.asSequence()
            .filter { it.status == SkillStatus.ENABLED && it.name !in reserved }
            .groupBy { it.name }
            .map { (name, group) ->
                val skill = (SkillSlashInvocation.select(group, name) as SkillSlashInvocation.Selection.Ready).skill
                SlashCommand(
                    name = skill.name,
                    displayName = skill.displayName?.takeIf { it.isNotBlank() && it != skill.name },
                    description = (skill.shortDescription?.takeIf(String::isNotBlank) ?: skill.description)
                        .replace('\n', ' ').trim(),
                    source = if (skill.scope == SkillScope.PROJECT) SlashCommandSource.WORKSPACE_SKILL else SlashCommandSource.GLOBAL_SKILL,
                )
            }
            .sortedBy { it.name }
    }

    /** 内置命令在前，技能按名称排序在后。 */
    fun build(builtins: List<SlashCommand>, skills: List<DiscoveredSkill>): List<SlashCommand> =
        builtins + skillCommands(skills)

    /**
     * 按过滤词匹配命令名、显示名与别名，不区分大小写。排序：命令名前缀 > 显示名或别名前缀 > 任意位置包含；
     * 同级内保持目录顺序（内置在前、技能按名称）。空过滤词返回全部。
     */
    fun filter(commands: List<SlashCommand>, query: String): List<SlashCommand> {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return commands
        return commands.mapIndexedNotNull { index, command ->
            val name = command.name.lowercase()
            val others = listOfNotNull(command.displayName) + command.aliases
            val rank = when {
                name.startsWith(key) -> 0
                others.any { it.lowercase().startsWith(key) } -> 1
                name.contains(key) || others.any { it.lowercase().contains(key) } -> 2
                else -> return@mapIndexedNotNull null
            }
            Triple(rank, index, command)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}
