package com.promenar.nexara.domain.generation

import kotlinx.coroutines.flow.StateFlow

/**
 * 生成失败的上层可见值。
 *
 * 本批次起，它只是 [GenerationFailure] 的薄壳：
 * - [failure] / [code] / [formatArgs] 是结构化失败契约，UI mapper 据此映射资源；
 * - [message] 与 [cause] 为**向后兼容访问器**，仅供尚未迁移到结构化契约的上层过渡使用。
 *
 * 兼容语义约束（必须保证）：
 * - [message] 永远只返回稳定、可回映射、**非自然语言**、**非异常原文**的信封串
 *   （由 [GenerationFailureCodec] 产出），不得返回 [Throwable.getMessage] 或协议原文；
 * - [cause] 仅诊断用途。
 *
 * 仍保留 `GenerationError(String, Throwable?)` 构造以兼容历史调用点
 * （例如 E2E fixture、androidTest），传入的任意字符串只会落入 [GenerationFailure.technical]，
 * 绝不会经 [message] 成为可展示文案。
 */
class GenerationError internal constructor(
    val failure: GenerationFailure,
) {
    constructor(message: String, cause: Throwable? = null) : this(
        GenerationFailure.unknown(technical = message.ifBlank { null }, cause = cause),
    )

    val code: GenerationFailureCode get() = failure.code
    val formatArgs: Map<String, String> get() = failure.formatArgs
    val technical: String? get() = failure.technical
    val cause: Throwable? get() = failure.cause

    /**
     * 向后兼容：稳定、可回映射、非自然语言的信封串。
     * 新代码请使用 [code] / [formatArgs] / [failure]。
     */
    val message: String
        get() = GenerationFailureCodec.encode(failure)

    override fun equals(other: Any?): Boolean = other is GenerationError && other.failure == failure

    override fun hashCode(): Int = failure.hashCode()

    override fun toString(): String = buildString {
        append("GenerationError(code=")
        append(code)
        failure.retryAfterSeconds?.takeIf { code == GenerationFailureCode.RATE_LIMIT && it > 0 }?.let {
            append(", retryAfterSeconds=")
            append(it)
        }
        append(')')
    }
}

data class GenerationTaskSnapshot(
    val taskId: String,
    val sessionId: String,
    val assistantMessageId: String,
    val phase: GenerationPhase,
    val generatedChars: Int,
    val startedAt: Long,
    val error: GenerationError? = null,
)

sealed interface StartGenerationResult {
    data class Started(val taskId: String) : StartGenerationResult
    data class Existing(val snapshot: GenerationTaskSnapshot) : StartGenerationResult
    data class Busy(val activeSessionId: String) : StartGenerationResult
    data class Rejected(val error: GenerationError) : StartGenerationResult
}

enum class CancellationReason { USER, TIMEOUT, REPLACED, SHUTDOWN, BACKGROUND_UNAVAILABLE }

interface GenerationCoordinator {
    val active: StateFlow<GenerationTaskSnapshot?>
    fun observe(sessionId: String): StateFlow<GenerationTaskSnapshot?>
    suspend fun start(request: GenerationRequest): StartGenerationResult
    fun cancel(taskId: String, reason: CancellationReason = CancellationReason.USER): Boolean
    fun acknowledgeTerminal(taskId: String): Boolean
    fun release(sessionId: String, discardTerminal: Boolean = false)
}
