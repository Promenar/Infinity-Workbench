package com.promenar.nexara.data.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContextBudgetGateTest {

    @Test
    fun `恰好容纳历史 prompt和附件时允许发送`() {
        val result = ContextBudgetGate.evaluate(
            ContextBudgetRequest(
                modelContextTokens = 100,
                outputReserveTokens = 20,
                safetyMarginTokens = 5,
                historyTokens = 40,
                promptTokens = 20,
                attachmentTokens = 15,
            ),
        )

        assertThat(result).isEqualTo(
            ContextBudgetDecision.Allowed(requiredTokens = 75, availableTokens = 75),
        )
    }

    @Test
    fun `超限时返回required和available且不截断任何输入`() {
        val request = ContextBudgetRequest(
            modelContextTokens = 100,
            outputReserveTokens = 20,
            safetyMarginTokens = 5,
            historyTokens = 40,
            promptTokens = 20,
            attachmentTokens = 16,
        )

        val result = ContextBudgetGate.evaluate(request)

        assertThat(result).isEqualTo(
            ContextBudgetDecision.Blocked(
                reason = ContextBudgetBlockReason.ExceededCapacity,
                requiredTokens = 76,
                availableTokens = 75,
            ),
        )
        assertThat(request.attachmentTokens).isEqualTo(16)
    }

    @Test
    fun `未知 零或负模型上下文均以UnknownCapacity阻止发送`() {
        listOf(null, 0, -1).forEach { contextTokens ->
            val result = ContextBudgetGate.evaluate(
                ContextBudgetRequest(
                    modelContextTokens = contextTokens,
                    outputReserveTokens = 10,
                    safetyMarginTokens = 5,
                    historyTokens = 20,
                    promptTokens = 10,
                    attachmentTokens = 3,
                ),
            )

            assertThat(result).isEqualTo(
                ContextBudgetDecision.Blocked(
                    reason = ContextBudgetBlockReason.UnknownCapacity,
                    requiredTokens = 33,
                    availableTokens = 0,
                ),
            )
        }
    }

    @Test
    fun `负估算不降低需求而是以InvalidEstimate阻止发送`() {
        val result = ContextBudgetGate.evaluate(
            ContextBudgetRequest(
                modelContextTokens = 100,
                outputReserveTokens = 20,
                safetyMarginTokens = 5,
                historyTokens = 40,
                promptTokens = -1,
                attachmentTokens = 15,
            ),
        )

        assertThat(result).isEqualTo(
            ContextBudgetDecision.Blocked(
                reason = ContextBudgetBlockReason.InvalidEstimate,
                requiredTokens = 0,
                availableTokens = 75,
            ),
        )
    }
}
