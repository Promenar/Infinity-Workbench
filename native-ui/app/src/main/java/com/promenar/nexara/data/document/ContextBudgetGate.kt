package com.promenar.nexara.data.document

/** 仅计算上下文容量；调用方必须保留原始历史、提示词和附件内容。 */
object ContextBudgetGate {
    fun evaluate(request: ContextBudgetRequest): ContextBudgetDecision {
        val modelContextTokens = request.modelContextTokens
        if (modelContextTokens == null || modelContextTokens <= 0) {
            return ContextBudgetDecision.Blocked(
                reason = ContextBudgetBlockReason.UnknownCapacity,
                requiredTokens = request.inputTokensOrZero(),
                availableTokens = 0,
            )
        }

        val availableTokens = (modelContextTokens.toLong() - request.outputReserveTokens -
            request.safetyMarginTokens).coerceAtLeast(0)
        if (request.hasNegativeEstimate()) {
            return ContextBudgetDecision.Blocked(
                reason = ContextBudgetBlockReason.InvalidEstimate,
                requiredTokens = 0,
                availableTokens = availableTokens,
            )
        }

        val requiredTokens = request.inputTokensOrZero()
        return if (requiredTokens <= availableTokens) {
            ContextBudgetDecision.Allowed(requiredTokens, availableTokens)
        } else {
            ContextBudgetDecision.Blocked(
                reason = ContextBudgetBlockReason.ExceededCapacity,
                requiredTokens = requiredTokens,
                availableTokens = availableTokens,
            )
        }
    }
}

data class ContextBudgetRequest(
    val modelContextTokens: Int?,
    val outputReserveTokens: Int,
    val safetyMarginTokens: Int,
    val historyTokens: Int,
    val promptTokens: Int,
    val attachmentTokens: Int,
) {
    internal fun inputTokensOrZero(): Long =
        listOf(historyTokens, promptTokens, attachmentTokens)
            .filter { it >= 0 }
            .sumOf(Int::toLong)

    internal fun hasNegativeEstimate(): Boolean = listOf(
        outputReserveTokens,
        safetyMarginTokens,
        historyTokens,
        promptTokens,
        attachmentTokens,
    ).any { it < 0 }
}

sealed interface ContextBudgetDecision {
    data class Allowed(
        val requiredTokens: Long,
        val availableTokens: Long,
    ) : ContextBudgetDecision

    data class Blocked(
        val reason: ContextBudgetBlockReason,
        val requiredTokens: Long,
        val availableTokens: Long,
    ) : ContextBudgetDecision
}

enum class ContextBudgetBlockReason {
    UnknownCapacity,
    InvalidEstimate,
    ExceededCapacity,
}
