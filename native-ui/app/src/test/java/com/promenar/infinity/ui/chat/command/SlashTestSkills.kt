package com.promenar.infinity.ui.chat.command

import com.promenar.infinity.data.skills.AgentSkillMetadata
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.ParsedSkillDocument
import com.promenar.infinity.data.skills.SkillCatalog
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillSnapshot
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.data.skills.SkillWorkspace
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/** 斜杠命令测试用的技能构造。 */
fun slashTestSkill(
    name: String,
    scope: SkillScope = SkillScope.USER,
    status: SkillStatus = SkillStatus.ENABLED,
    description: String = "Description of $name",
    displayName: String? = null,
    shortDescription: String? = null,
) = DiscoveredSkill(
    id = if (scope == SkillScope.USER) "user:$name" else "project:ws:$name",
    scope = scope,
    name = name,
    description = description,
    displayName = displayName,
    shortDescription = shortDescription,
    virtualDir = if (scope == SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
    files = listOf("references/guide.md"),
    status = status,
    modifiedSinceConfirmed = false,
    allowImplicitInvocation = true,
    brokenReason = null,
    metadata = AgentSkillMetadata(name, description),
)

/** 固定内容的技能目录：正文为 `Body of <name>`。 */
class StaticSkillCatalog(var skills: List<DiscoveredSkill> = emptyList()) : SkillCatalog {
    override val userRoot = File("/tmp/slash-skills")
    override val revision = MutableStateFlow(0L)
    var scans = 0

    override suspend fun scan(workspace: SkillWorkspace?): SkillSnapshot {
        scans++
        return SkillSnapshot(workspace, skills)
    }
    override suspend fun readDocument(skill: DiscoveredSkill): ParsedSkillDocument? =
        ParsedSkillDocument(AgentSkillMetadata(skill.name, skill.description), "Body of ${skill.name}")
    override suspend fun readFile(skill: DiscoveredSkill, path: String): String = ""
    override suspend fun setEnabled(skill: DiscoveredSkill, enabled: Boolean) = Unit
    override suspend fun trust(skill: DiscoveredSkill) = Unit
    override suspend fun acknowledgeModified(skill: DiscoveredSkill) = Unit
    override suspend fun delete(skill: DiscoveredSkill) = Unit
}
