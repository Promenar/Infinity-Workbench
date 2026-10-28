package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.ParsedSkillDocument
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillSlashInvocation
import kotlinx.coroutines.CancellationException

/** 本轮用户以 `/技能名` 点名的技能；由客户端在生成前完成加载。 */
sealed interface RequestedSkill {
    val name: String

    /** 已加载：[activation] 与 `activate_skill` 的返回内容一致。 */
    data class Loaded(override val name: String, val activation: String) : RequestedSkill

    /** 技能存在但当前无法加载（停用、待信任、损坏或读取失败）。 */
    data class Unavailable(override val name: String) : RequestedSkill
}

/**
 * 斜杠技能命令的预加载：生成每一轮请求时把技能说明作为系统提示的一个段落注入，
 * 模型无需再调用 `activate_skill`。只影响点名的这一轮，后续轮次不再携带。
 */
object SlashSkillActivation {
    /** 与 `activate_skill` 共用的技能说明格式。 */
    fun render(skill: DiscoveredSkill, document: ParsedSkillDocument): String = buildString {
        appendLine("# Skill: ${skill.name}")
        appendLine("Source: ${skill.scope.name.lowercase()}")
        appendLine("Directory: ${skill.virtualDir}")
        appendLine("Document: ${skill.virtualDir}/SKILL.md")
        appendLine("Follow these skill instructions for the current task.")
        appendLine()
        appendLine(document.body.ifBlank { "(empty)" })
        appendLine()
        appendLine("## Bundled files")
        appendLine("Read as needed with read_skill_file(path=\"${skill.virtualDir}/<relative-file>\") or file tools.")
        if (skill.files.isEmpty()) appendLine("(none)")
        skill.files.forEach { appendLine("- $it") }
        appendLine("Run bundled scripts with shell_exec or python_exec, subject to the execution tool's approval rules.")
        val shellDirectory = if (skill.scope == SkillScope.USER) "\$INFINITY_SKILLS/${skill.name}" else ".agents/skills/${skill.name}"
        appendLine("Script directory in the shell: `$shellDirectory`.")
    }.trimEnd()

    /**
     * 按用户消息原文解析本轮点名的技能。不是斜杠命令或没有同名技能时返回 null；
     * 同名技能存在但不可用时返回 [RequestedSkill.Unavailable]，让模型向用户说明。
     */
    suspend fun resolve(
        userText: String,
        skills: List<DiscoveredSkill>,
        readDocument: suspend (DiscoveredSkill) -> ParsedSkillDocument?,
    ): RequestedSkill? {
        val name = SkillSlashInvocation.parse(userText)?.skillName ?: return null
        return when (val selection = SkillSlashInvocation.select(skills, name)) {
            SkillSlashInvocation.Selection.NotFound -> null
            is SkillSlashInvocation.Selection.Unavailable -> RequestedSkill.Unavailable(name)
            is SkillSlashInvocation.Selection.Ready -> {
                val document = try {
                    readDocument(selection.skill)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                document?.let { RequestedSkill.Loaded(name, render(selection.skill, it)) }
                    ?: RequestedSkill.Unavailable(name)
            }
        }
    }

    /** 系统提示中的“本轮点名技能”段落；英文书写，与其它系统提示段落一致。 */
    fun promptSection(requested: RequestedSkill): String = buildString {
        appendLine("## Requested Skill")
        when (requested) {
            is RequestedSkill.Loaded -> {
                appendLine(
                    "The user's latest message starts with the slash command `/${requested.name}`, which loads this skill for the current turn. " +
                        "Its SKILL.md is already loaded below, so do not call `activate_skill` for it. " +
                        "Treat the text after `/${requested.name}` as the user's request and follow the skill's instructions.",
                )
                appendLine("<skill_activation>")
                appendLine(requested.activation)
                appendLine("</skill_activation>")
            }
            is RequestedSkill.Unavailable -> appendLine(
                "The user's latest message starts with `/${requested.name}`, but that skill is not available (disabled, untrusted, or unreadable). " +
                    "Tell the user briefly, then help with the rest of the message without it.",
            )
        }
    }
}
