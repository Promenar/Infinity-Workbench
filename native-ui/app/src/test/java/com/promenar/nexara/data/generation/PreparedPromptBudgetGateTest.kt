package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.document.ContextBudgetBlockReason
import com.promenar.nexara.data.document.ContextBudgetDecision
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.PromptRequest
import com.promenar.nexara.data.remote.protocol.ProtocolMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class PreparedPromptBudgetGateTest {
    @Test
    fun `无完整文档时不改变既有请求行为`() {
        val decision = PreparedPromptBudgetGate.evaluate(
            session(),
            prompt("普通消息"),
            settings(128_000),
            false,
        )

        assertThat(decision).isNull()
    }

    @Test
    fun `最终Prompt超过容量时在Provider前阻止`() {
        val documentPrompt = buildString {
            append("<<<NEXARA_DOCUMENT_BEGIN hash>>>\n")
            append("x".repeat(40_000))
            append("\n<<<NEXARA_DOCUMENT_END hash>>>")
        }
        val decision = PreparedPromptBudgetGate.evaluate(
            session(),
            prompt(documentPrompt),
            settings(8_000),
            true,
        )

        assertThat(decision).isInstanceOf(ContextBudgetDecision.Blocked::class.java)
        assertThat((decision as ContextBudgetDecision.Blocked).reason)
            .isEqualTo(ContextBudgetBlockReason.ExceededCapacity)
        assertThat(decision.requiredTokens).isGreaterThan(decision.availableTokens)
    }

    @Test
    fun `完整文档容量未知时必须阻止而不是假定128K`() {
        val decision = PreparedPromptBudgetGate.evaluate(
            session(modelId = "unknown-model"),
            prompt("正文", modelId = "unknown-model"),
            settings(0),
            true,
        )

        assertThat((decision as ContextBudgetDecision.Blocked).reason)
            .isEqualTo(ContextBudgetBlockReason.UnknownCapacity)
    }

    @Test
    fun `中文按UTF8字节保守计量不会低估`() {
        val decision = PreparedPromptBudgetGate.evaluate(
            session(),
            prompt("中".repeat(2_000), maxTokens = 1_024),
            settings(7_500),
            true,
        )

        assertThat(decision).isInstanceOf(ContextBudgetDecision.Blocked::class.java)
    }

    @Test
    fun `用户容量覆盖按稳定模型ID读取而目录仍按最终远端ID解析`() {
        val prefs = mockk<SharedPreferences> {
            every { getInt(any(), any()) } returns 0
            every { getInt("model_info_provider-x::actual-model_context", any()) } returns 128_000
        }
        val decision = PreparedPromptBudgetGate.evaluate(
            session(modelId = "session-alias"),
            prompt("正文", modelId = "actual-model"),
            prefs,
            true,
            stableModelId = "provider-x::actual-model",
        )

        assertThat(decision).isInstanceOf(ContextBudgetDecision.Allowed::class.java)
    }

    private fun session(modelId: String = "test-model") = Session(
        id = "session",
        agentId = "agent",
        modelId = modelId,
        inferenceParams = InferenceParams(maxTokens = 1_024),
    )

    private fun prompt(
        content: String,
        modelId: String = "test-model",
        maxTokens: Int? = null,
    ) = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = content)),
        model = modelId,
        maxTokens = maxTokens,
    )

    private fun settings(context: Int): SharedPreferences = mockk {
        every { getInt(any(), any()) } returns context
    }
}
