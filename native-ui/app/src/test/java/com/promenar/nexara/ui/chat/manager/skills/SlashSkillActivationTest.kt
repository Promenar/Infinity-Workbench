package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.skills.AgentSkillMetadata
import com.promenar.nexara.data.skills.FileSkillCatalog
import com.promenar.nexara.data.skills.InMemoryPreferences
import com.promenar.nexara.data.skills.ParsedSkillDocument
import com.promenar.nexara.data.skills.SkillCatalogFiles
import com.promenar.nexara.data.skills.SkillScope
import com.promenar.nexara.data.skills.SkillStatus
import com.promenar.nexara.data.storage.JvmBoundRealPathFileSystem
import com.promenar.nexara.ui.chat.command.slashTestSkill
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderParams
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlashSkillActivationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val document: suspend (com.promenar.nexara.data.skills.DiscoveredSkill) -> ParsedSkillDocument? = {
        ParsedSkillDocument(AgentSkillMetadata(it.name, it.description), "Steps for ${it.scope.name}")
    }

    @Test fun `点名可用技能时加载正文，工作区同名优先`() = runTest {
        val skills = listOf(slashTestSkill("demo"), slashTestSkill("demo", SkillScope.PROJECT))
        val requested = SlashSkillActivation.resolve("/demo 帮我整理", skills, document) as RequestedSkill.Loaded
        assertThat(requested.name).isEqualTo("demo")
        assertThat(requested.activation).contains("# Skill: demo")
        assertThat(requested.activation).contains("Steps for PROJECT")
        assertThat(requested.activation).contains("/.agents/skills/demo/SKILL.md")
        assertThat(requested.activation).contains("- references/guide.md")
    }

    @Test fun `不是命令、没有同名技能时不注入，技能不可用或读取失败时注入说明`() = runTest {
        val skills = listOf(slashTestSkill("demo"), slashTestSkill("off", status = SkillStatus.DISABLED))
        assertThat(SlashSkillActivation.resolve("普通消息", skills, document)).isNull()
        assertThat(SlashSkillActivation.resolve("/etc/hosts 是什么", skills, document)).isNull()
        assertThat(SlashSkillActivation.resolve("/missing 你好", skills, document)).isNull()
        assertThat(SlashSkillActivation.resolve("/off 你好", skills, document)).isEqualTo(RequestedSkill.Unavailable("off"))
        assertThat(SlashSkillActivation.resolve("/demo", skills) { null }).isEqualTo(RequestedSkill.Unavailable("demo"))
        assertThat(SlashSkillActivation.resolve("/demo", skills) { throw IOException("读取失败") }).isEqualTo(RequestedSkill.Unavailable("demo"))
    }

    @Test fun `预加载内容与 activate_skill 返回一致`() = runTest {
        val userRoot = temporary.newFolder("users")
        val catalog = FileSkillCatalog(
            userRoot, InMemoryPreferences(), temporary.newFolder("state"), Dispatchers.IO,
            System::currentTimeMillis, SkillCatalogFiles(JvmBoundRealPathFileSystem()),
        )
        File(userRoot, "demo").apply { mkdirs() }.resolve("SKILL.md")
            .writeText("---\nname: demo\ndescription: Demo\n---\nInstructions for demo\n")
        File(userRoot, "demo/scripts").mkdirs()
        File(userRoot, "demo/scripts/run.py").writeText("print(1)\n")
        catalog.setEnabled(catalog.scan(null).skills.single(), true)
        val skills = catalog.scan(null).skills

        val loaded = SlashSkillActivation.resolve("/demo 开始", skills) { catalog.readDocument(it) } as RequestedSkill.Loaded
        val tool = ActivateSkillTool(catalog).execute(
            skillArgs("name" to "demo"),
            object : SkillExecutionContext {
                override val sessionId = "session"
                override val agentId = "agent"
                override val workspacePath = "/"
                override val workspaceRootUuid = "root"
            },
        )
        assertThat(tool.status).isNotEqualTo("error")
        assertThat(loaded.activation).isEqualTo(tool.content)
        assertThat(loaded.activation).contains("Instructions for demo")
        assertThat(loaded.activation).contains("- scripts/run.py")
    }

    @Test fun `系统提示段落：已加载时说明无需再激活并包裹技能正文`() {
        val section = SlashSkillActivation.promptSection(RequestedSkill.Loaded("demo", "# Skill: demo\nbody"))
        assertThat(section.lines().first()).isEqualTo("## Requested Skill")
        assertThat(section).contains("`/demo`")
        assertThat(section).contains("do not call `activate_skill`")
        assertThat(section).contains("<skill_activation>\n# Skill: demo\nbody\n</skill_activation>")
        val unavailable = SlashSkillActivation.promptSection(RequestedSkill.Unavailable("off"))
        assertThat(unavailable).contains("`/off`")
        assertThat(unavailable).contains("not available")
        assertThat(unavailable).doesNotContain("<skill_activation>")
    }

    @Test fun `上下文构建把点名技能放在历史摘要之后、系统时间之前，未点名时不出现`() = runTest {
        val session = Session(id = "s1", agentId = "a1", summary = "Earlier summary")
        val base = ContextBuilderParams(
            sessionId = "s1", content = "/demo 整理", assistantMsgId = "m1", session = session,
            availableToolNames = setOf("activate_skill"), availableSkills = listOf(slashTestSkill("demo")),
        )
        val prompt = ContextBuilder().buildContext(
            base.copy(requestedSkill = RequestedSkill.Loaded("demo", "# Skill: demo\nSteps")),
        ).finalSystemPrompt
        val skillIndex = prompt.indexOf("## Requested Skill")
        assertThat(skillIndex).isGreaterThan(prompt.indexOf("## Skills"))
        assertThat(skillIndex).isGreaterThan(prompt.indexOf("</history_summary>"))
        assertThat(skillIndex).isLessThan(prompt.indexOf("[System Time:"))
        assertThat(prompt).contains("<skill_activation>\n# Skill: demo\nSteps\n</skill_activation>")
        assertThat(prompt.trimEnd().lines().last()).contains("System Time")

        val plain = ContextBuilder().buildContext(base.copy(content = "整理")).finalSystemPrompt
        assertThat(plain).doesNotContain("## Requested Skill")
    }
}
