package com.promenar.nexara.ui.chat.command

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.skills.DiscoveredSkill
import com.promenar.nexara.data.skills.SkillScope
import com.promenar.nexara.data.skills.SkillStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SlashCommandControllerTest {
    private class Harness(scope: TestScope, var skills: List<DiscoveredSkill>? = emptyList()) {
        var input = ""
        val actions = mutableListOf<SlashCommandAction>()
        val blocks = mutableListOf<Pair<SlashBlockReason, String>>()
        var sent = 0
        var scans = 0
        var scanGate: CompletableDeferred<Unit>? = null
        var context = SlashCommandContext(hasMessages = true)
        val controller = SlashCommandController(
            scope = scope,
            scanSkills = {
                scans++
                scanGate?.await()
                skills
            },
            context = { context },
            perform = { actions += it },
            block = { reason, name -> blocks += reason to name },
            setInput = { input = it },
        )

        fun submit(text: String) {
            input = text
            controller.submit(text) { sent++; input = "" }
        }
    }

    private fun command(name: String) = SlashCommand(name, null, "", SlashCommandSource.GLOBAL_SKILL)
    private fun builtin(command: BuiltinSlashCommand) =
        SlashCommand(command.commandName, "名称", "说明", SlashCommandSource.BUILTIN, command.aliases, command)

    @Test fun `普通消息与非命令形态直接发送且不扫描技能`() = runTest {
        val harness = Harness(this)
        listOf("你好", "/", "/ 空格开头", "/etc/hosts 里有什么", "/嗯 你好", " /rename 前有空格").forEach { harness.submit(it) }
        advanceUntilIdle()
        assertThat(harness.sent).isEqualTo(6)
        assertThat(harness.scans).isEqualTo(0)
        assertThat(harness.actions).isEmpty()
    }

    @Test fun `内置命令在本机执行且不发送，成功后清空输入框`() = runTest {
        val harness = Harness(this)
        harness.submit("/rename 新标题")
        assertThat(harness.actions).containsExactly(SlashCommandAction.Rename("新标题"))
        assertThat(harness.input).isEmpty()
        harness.submit("/重命名 中文别名")
        assertThat(harness.actions.last()).isEqualTo(SlashCommandAction.Rename("中文别名"))
        assertThat(harness.sent).isEqualTo(0)
        assertThat(harness.scans).isEqualTo(0)
    }

    @Test fun `内置命令被拒绝时保留草稿`() = runTest {
        val harness = Harness(this)
        harness.submit("/mode fast")
        assertThat(harness.actions.single()).isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.UNKNOWN_MODE, "fast"))
        assertThat(harness.input).isEqualTo("/mode fast")
        assertThat(harness.sent).isEqualTo(0)
    }

    @Test fun `可用技能命令重新扫描后原样发送`() = runTest {
        val harness = Harness(this, listOf(slashTestSkill("demo")))
        harness.submit("/Demo 帮我整理")
        advanceUntilIdle()
        assertThat(harness.scans).isEqualTo(1)
        assertThat(harness.sent).isEqualTo(1)
        assertThat(harness.blocks).isEmpty()
    }

    @Test fun `没有对应技能或命令的词按普通消息发送`() = runTest {
        val harness = Harness(this, emptyList())
        harness.submit("/tmp 目录里有什么")
        advanceUntilIdle()
        assertThat(harness.blocks).isEmpty()
        assertThat(harness.sent).isEqualTo(1)
    }

    @Test fun `技能停用、待信任、损坏或扫描失败时提示并保留草稿`() = runTest {
        val cases = listOf(
            listOf(slashTestSkill("demo", status = SkillStatus.DISABLED)) to SlashBlockReason.DISABLED,
            listOf(slashTestSkill("demo", SkillScope.PROJECT, status = SkillStatus.PENDING_TRUST)) to SlashBlockReason.PENDING_TRUST,
            listOf(slashTestSkill("demo", status = SkillStatus.BROKEN)) to SlashBlockReason.BROKEN,
            null to SlashBlockReason.SCAN_FAILED,
        )
        cases.forEach { (skills, reason) ->
            val harness = Harness(this, skills)
            harness.submit("/demo 帮我整理")
            advanceUntilIdle()
            assertThat(harness.blocks).containsExactly(reason to "demo")
            assertThat(harness.sent).isEqualTo(0)
            assertThat(harness.input).isEqualTo("/demo 帮我整理")
        }
    }

    @Test fun `同名技能优先于英文别名，没有技能时执行别名对应的内置命令`() = runTest {
        val withSkill = Harness(this, listOf(slashTestSkill("summary")))
        withSkill.submit("/summary 本周进展")
        advanceUntilIdle()
        assertThat(withSkill.sent).isEqualTo(1)
        assertThat(withSkill.actions).isEmpty()

        val withoutSkill = Harness(this, emptyList())
        withoutSkill.submit("/summary")
        advanceUntilIdle()
        assertThat(withoutSkill.sent).isEqualTo(0)
        assertThat(withoutSkill.actions).containsExactly(SlashCommandAction.Compact)
    }

    @Test fun `技能确认期间重复发送被忽略`() = runTest {
        val harness = Harness(this, listOf(slashTestSkill("demo")))
        harness.scanGate = CompletableDeferred()
        harness.submit("/demo 一")
        harness.submit("/demo 一")
        harness.scanGate!!.complete(Unit)
        advanceUntilIdle()
        assertThat(harness.scans).isEqualTo(1)
        assertThat(harness.sent).isEqualTo(1)
    }

    @Test fun `点选技能把第一个词替换为命令名并保留已写的补充说明`() = runTest {
        val harness = Harness(this)
        harness.controller.pick(command("pdf-tools"), "/pd")
        assertThat(harness.input).isEqualTo("/pdf-tools ")
        harness.controller.pick(command("pdf-tools"), "/p 合并这两个文件")
        assertThat(harness.input).isEqualTo("/pdf-tools 合并这两个文件")
        assertThat(harness.actions).isEmpty()
    }

    @Test fun `点选内置命令立即执行并清空输入框，第一个词之后的内容作为参数`() = runTest {
        val harness = Harness(this)
        harness.controller.pick(builtin(BuiltinSlashCommand.RENAME), "/ren")
        assertThat(harness.actions.last()).isEqualTo(SlashCommandAction.OpenRenameDialog)
        assertThat(harness.input).isEmpty()
        harness.controller.pick(builtin(BuiltinSlashCommand.RENAME), "/r 季度复盘")
        assertThat(harness.actions.last()).isEqualTo(SlashCommandAction.Rename("季度复盘"))
        harness.context = SlashCommandContext(isGenerating = true, hasMessages = true)
        harness.controller.pick(builtin(BuiltinSlashCommand.COMPACT), "/comp")
        assertThat(harness.actions.last()).isEqualTo(SlashCommandAction.Reject(SlashCommandRejection.COMPACT_GENERATING))
    }

    @Test fun `发送判定的优先级`() {
        assertThat(SlashSendPlanner.classify("/rename x")).isEqualTo(SlashSendDecision.RunBuiltin(BuiltinSlashCommand.RENAME, "x"))
        assertThat(SlashSendPlanner.classify("/导出 txt")).isEqualTo(SlashSendDecision.RunBuiltin(BuiltinSlashCommand.EXPORT, "txt"))
        assertThat(SlashSendPlanner.classify("/my-skill 说明\n第二行")).isEqualTo(SlashSendDecision.NeedsSkills("my-skill", "说明\n第二行"))
        assertThat(SlashSendPlanner.classify("/My_Skill")).isEqualTo(SlashSendDecision.SendAsMessage)
        assertThat(SlashSendPlanner.classify("普通")).isEqualTo(SlashSendDecision.SendAsMessage)
    }
}
