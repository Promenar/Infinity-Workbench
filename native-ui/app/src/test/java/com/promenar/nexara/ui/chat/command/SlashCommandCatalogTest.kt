package com.promenar.nexara.ui.chat.command

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.skills.SkillScope
import com.promenar.nexara.data.skills.SkillStatus
import org.junit.Test

class SlashCommandCatalogTest {
    private val builtins = BuiltinSlashCommand.entries.map {
        SlashCommand(it.commandName, displayName = "显示-${it.commandName}", description = "说明", source = SlashCommandSource.BUILTIN, aliases = it.aliases, builtin = it)
    }

    @Test fun `行首斜杠且光标在第一个词内才触发`() {
        assertThat(SlashCommandCatalog.triggerQuery("/", 1)).isEqualTo("")
        assertThat(SlashCommandCatalog.triggerQuery("/ren", 4)).isEqualTo("ren")
        // 光标在词中间时仍以整个第一个词过滤。
        assertThat(SlashCommandCatalog.triggerQuery("/rename", 3)).isEqualTo("rename")
        assertThat(SlashCommandCatalog.triggerQuery("/rename 标题", 7)).isEqualTo("rename")
    }

    @Test fun `输入空格、删除斜杠、光标离开第一个词或路径形态时隐藏`() {
        assertThat(SlashCommandCatalog.triggerQuery("/rename ", 8)).isNull()
        assertThat(SlashCommandCatalog.triggerQuery("/rename 标题", 10)).isNull()
        assertThat(SlashCommandCatalog.triggerQuery("rename", 6)).isNull()
        assertThat(SlashCommandCatalog.triggerQuery("", 0)).isNull()
        assertThat(SlashCommandCatalog.triggerQuery(" /rename", 8)).isNull()
        assertThat(SlashCommandCatalog.triggerQuery("/rename", 0)).isNull()
        assertThat(SlashCommandCatalog.triggerQuery("/etc/hosts", 10)).isNull()
        assertThat(SlashCommandCatalog.triggerQuery("/re\n第二行", 3)).isEqualTo("re")
        assertThat(SlashCommandCatalog.triggerQuery("/re\n第二行", 5)).isNull()
    }

    @Test fun `空过滤词返回全部且内置命令在前技能按名称在后`() {
        val commands = SlashCommandCatalog.build(builtins, listOf(slashTestSkill("zeta"), slashTestSkill("alpha")))
        val all = SlashCommandCatalog.filter(commands, "")
        assertThat(all.take(builtins.size).map { it.name }).containsExactlyElementsIn(BuiltinSlashCommand.entries.map { it.commandName }).inOrder()
        assertThat(all.drop(builtins.size).map { it.name }).containsExactly("alpha", "zeta").inOrder()
    }

    @Test fun `命令名前缀优先于别名前缀，别名前缀优先于包含匹配`() {
        val commands = SlashCommandCatalog.build(builtins, listOf(slashTestSkill("re-search"), slashTestSkill("score")))
        val names = SlashCommandCatalog.filter(commands, "re").map { it.name }
        // 名称前缀：rename、re-search；别名前缀：clear（别名 reset）；名称包含：score。
        assertThat(names.indexOf("rename")).isLessThan(names.indexOf("clear"))
        assertThat(names.indexOf("re-search")).isLessThan(names.indexOf("clear"))
        assertThat(names.indexOf("clear")).isLessThan(names.indexOf("score"))
        assertThat(names).doesNotContain("new")
    }

    @Test fun `过滤不区分大小写并匹配中英文别名与显示名`() {
        val commands = SlashCommandCatalog.build(builtins, listOf(slashTestSkill("pdf-tools", displayName = "PDF 工具箱")))
        assertThat(SlashCommandCatalog.filter(commands, "RENAME").map { it.name }).containsExactly("rename")
        assertThat(SlashCommandCatalog.filter(commands, "重命名").map { it.name }).containsExactly("rename")
        assertThat(SlashCommandCatalog.filter(commands, "导出").map { it.name }).containsExactly("export")
        assertThat(SlashCommandCatalog.filter(commands, "工具箱").map { it.name }).containsExactly("pdf-tools")
        assertThat(SlashCommandCatalog.filter(commands, "显示-compact").map { it.name }).containsExactly("compact")
        assertThat(SlashCommandCatalog.filter(commands, "不存在")).isEmpty()
    }

    @Test fun `技能目录只列已启用技能，同名去重时工作区优先，与内置命令名重名的不列出`() {
        val skills = listOf(
            slashTestSkill("demo"),
            slashTestSkill("demo", SkillScope.PROJECT, description = "项目版"),
            slashTestSkill("off", status = SkillStatus.DISABLED),
            slashTestSkill("pending", SkillScope.PROJECT, status = SkillStatus.PENDING_TRUST),
            slashTestSkill("rename"),
            slashTestSkill("summary"),
            slashTestSkill("brief", shortDescription = "短说明\n第二行", displayName = "brief"),
        )
        val commands = SlashCommandCatalog.skillCommands(skills)
        assertThat(commands.map { it.name }).containsExactly("brief", "demo", "summary").inOrder()
        val demo = commands.single { it.name == "demo" }
        assertThat(demo.source).isEqualTo(SlashCommandSource.WORKSPACE_SKILL)
        assertThat(demo.description).isEqualTo("项目版")
        assertThat(demo.isSkill).isTrue()
        val brief = commands.single { it.name == "brief" }
        assertThat(brief.description).isEqualTo("短说明 第二行")
        // 显示名与命令名相同不重复显示。
        assertThat(brief.displayName).isNull()
        assertThat(commands.single { it.name == "summary" }.source).isEqualTo(SlashCommandSource.GLOBAL_SKILL)
    }

    @Test fun `内置命令按名称与别名查找`() {
        assertThat(BuiltinSlashCommand.byName("Rename")).isEqualTo(BuiltinSlashCommand.RENAME)
        assertThat(BuiltinSlashCommand.byName("重命名")).isNull()
        assertThat(BuiltinSlashCommand.byAlias("重命名")).isEqualTo(BuiltinSlashCommand.RENAME)
        assertThat(BuiltinSlashCommand.byAlias("SUMMARY")).isEqualTo(BuiltinSlashCommand.COMPACT)
        assertThat(BuiltinSlashCommand.byAlias("rename")).isNull()
    }
}
