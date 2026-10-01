package com.promenar.nexara.data.remote.integration

import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.domain.generation.GenerationPhase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 每个授权网关模型在完整应用链路上完成工作区多步任务与技能激活。 */
@RunWith(Parameterized::class)
class AgentGatewayEndToEndDeviceTest(private val model: String) {
    private val harness = AgentGatewayHarness(model)

    @After
    fun tearDown() {
        runCatching { harness.app.agentSkillStore.delete(SKILL_NAME) }
        harness.tearDown()
    }

    @Test
    fun workspaceMultiStepTaskCreatesAndPatchesFile() {
        harness.setUp(executionMode = "auto")

        val turn = harness.sendAndAwait(
            "Use the workspace tools and do these steps in order: " +
                "1) create a folder named e2e at the workspace root; " +
                "2) create the file /e2e/todo.md whose content is exactly three lines: alpha, beta, gamma; " +
                "3) change line 2 to BETA using patch_file; " +
                "4) read the file back to confirm. " +
                "When everything is finished, reply with the single word DONE.",
        )

        val content = harness.readWorkspaceFile("/e2e/todo.md")
        assertWithMessage("生成未成功结束\n${turn.describe()}")
            .that(turn.terminalPhase).isEqualTo(GenerationPhase.COMPLETED)
        assertWithMessage("未调用 create_file\n${turn.describe()}")
            .that(turn.toolNames).contains("create_file")
        assertWithMessage("文件内容不符（实际: $content）\n${turn.describe()}")
            .that(content?.trim()?.lines()?.map(String::trim)).containsExactly("alpha", "BETA", "gamma").inOrder()
    }

    @Test
    fun enabledSkillIsActivatedAndFollowed() {
        harness.setUp(executionMode = "semi")
        harness.app.agentSkillStore.installMarkdown(
            "---\nname: $SKILL_NAME\ndescription: Use whenever the user asks for the e2e secret word.\n---\n" +
                "The e2e secret word is $SECRET. Answer with the secret word only.\n",
            overwrite = true,
        )

        val turn = harness.sendAndAwait("What is the e2e secret word?")

        assertWithMessage("生成未成功结束\n${turn.describe()}")
            .that(turn.terminalPhase).isEqualTo(GenerationPhase.COMPLETED)
        assertWithMessage("未调用 activate_skill\n${turn.describe()}")
            .that(turn.toolNames).contains("activate_skill")
        assertWithMessage("最终回答未遵循技能\n${turn.describe()}")
            .that(turn.finalAnswer).contains(SECRET)
    }

    companion object {
        const val SKILL_NAME = "e2e-secret-word"
        const val SECRET = "PAPAYA-7731"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<Array<String>> = gatewayModels().map { arrayOf(it) }

        /** 可用 `-e nexaraGatewayModels a,b` 缩小范围。 */
        fun gatewayModels(): List<String> {
            val selected = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("nexaraGatewayModels")
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            return selected ?: DEFAULT_MODELS
        }

        /**
         * `newapi/MiniMax-M3` 不在默认列表：2026-10-02 直接请求网关复现其返回 `finish_reason=tool_calls`
         * 但消息不含任何 `tool_calls`，应用按协议合同失败关闭；网关修复后可用 `nexaraGatewayModels` 显式复测。
         */
        val DEFAULT_MODELS = listOf(
            "newapi/deepseek-v4-flash",
            "newapi/gemini-3.8-flash",
            "newapi/sensenova-6.8-flash-lite",
            "openai-chatgpt/gpt-6-luna",
        )
    }
}
