package com.promenar.nexara.data.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.chat.command.slashTestSkill
import org.junit.Test

class SkillSlashInvocationTest {
    @Test fun `解析命令词与补充说明`() {
        assertThat(SkillSlashInvocation.parse("/demo")).isEqualTo(SkillSlashInvocation.Parsed("demo", ""))
        assertThat(SkillSlashInvocation.parse("/demo   整理一下\n第二行  ")).isEqualTo(SkillSlashInvocation.Parsed("demo", "整理一下\n第二行"))
        assertThat(SkillSlashInvocation.parse("/demo\n换行后的说明")!!.argument).isEqualTo("换行后的说明")
        assertThat(SkillSlashInvocation.parse("/Demo-Tool x")!!.skillName).isEqualTo("demo-tool")
    }

    @Test fun `非命令形态不解析或没有技能名`() {
        listOf("demo", " /demo", "/", "/ demo", "/etc/hosts", "/demo/sub 说明").forEach {
            assertThat(SkillSlashInvocation.parse(it)).isNull()
        }
        listOf("/demo_x", "/-demo", "/demo--x", "/重命名", "/" + "a".repeat(65)).forEach {
            assertThat(SkillSlashInvocation.parse(it)!!.skillName).isNull()
        }
    }

    @Test fun `同名时已启用优先，均启用时工作区优先，均不可用时报告不可用`() {
        val user = slashTestSkill("demo")
        val project = slashTestSkill("demo", SkillScope.PROJECT)
        val userOff = slashTestSkill("demo", status = SkillStatus.DISABLED)
        val projectPending = slashTestSkill("demo", SkillScope.PROJECT, status = SkillStatus.PENDING_TRUST)
        assertThat(SkillSlashInvocation.select(listOf(user, project), "demo")).isEqualTo(SkillSlashInvocation.Selection.Ready(project))
        assertThat(SkillSlashInvocation.select(listOf(user, projectPending), "demo")).isEqualTo(SkillSlashInvocation.Selection.Ready(user))
        assertThat(SkillSlashInvocation.select(listOf(userOff, projectPending), "demo"))
            .isEqualTo(SkillSlashInvocation.Selection.Unavailable(projectPending))
        assertThat(SkillSlashInvocation.select(listOf(user), "other")).isEqualTo(SkillSlashInvocation.Selection.NotFound)
    }
}
