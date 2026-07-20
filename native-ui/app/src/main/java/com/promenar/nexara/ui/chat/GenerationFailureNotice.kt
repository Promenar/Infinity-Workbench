package com.promenar.nexara.ui.chat

import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.ResolvedStatus
import com.promenar.nexara.ui.common.status.UiStatusNotice

/**
 * 生成失败的纯 UI 映射边界。
 *
 * 用户可见层只消费稳定 code、资源 ID 与经过白名单筛选的格式参数；诊断信息、异常原文、
 * 持久化信封和未知旧字符串都不会成为展示模板或参数。
 */
object GenerationFailureNotice {
    private const val CODE_NETWORK = "generation.failure.network"
    private const val CODE_AUTH = "generation.failure.auth"
    private const val CODE_RATE_LIMIT = "generation.failure.rate_limit"
    private const val CODE_RATE_LIMIT_RETRY = "generation.failure.rate_limit_retry"
    private const val CODE_QUOTA = "generation.failure.quota"
    private const val CODE_TIMEOUT = "generation.failure.timeout"
    private const val CODE_INVALID_REQUEST = "generation.failure.invalid_request"
    private const val CODE_CONTEXT_LIMIT = "generation.failure.context_limit"
    private const val CODE_SERVER = "generation.failure.server"
    private const val CODE_BUSY = "generation.failure.busy"
    private const val CODE_PERSISTENCE = "generation.failure.persistence"
    private const val CODE_UNKNOWN = "generation.failure.unknown"

    fun from(failure: GenerationFailure): UiStatusNotice {
        val retryAfterSeconds = failure.retryAfterSeconds?.takeIf { it > 0 }
        val code = when (failure.code) {
            GenerationFailureCode.NETWORK -> CODE_NETWORK
            GenerationFailureCode.AUTH -> CODE_AUTH
            GenerationFailureCode.RATE_LIMIT ->
                if (retryAfterSeconds != null) CODE_RATE_LIMIT_RETRY else CODE_RATE_LIMIT
            GenerationFailureCode.QUOTA -> CODE_QUOTA
            GenerationFailureCode.TIMEOUT -> CODE_TIMEOUT
            GenerationFailureCode.INVALID_REQUEST -> CODE_INVALID_REQUEST
            GenerationFailureCode.CONTEXT_LIMIT -> CODE_CONTEXT_LIMIT
            GenerationFailureCode.SERVER -> CODE_SERVER
            GenerationFailureCode.BUSY -> CODE_BUSY
            GenerationFailureCode.PERSISTENCE -> CODE_PERSISTENCE
            GenerationFailureCode.UNKNOWN -> CODE_UNKNOWN
        }
        return UiStatusNotice(
            severity = NoticeSeverity.Error,
            code = code,
            formatArgs = when (failure.code) {
                GenerationFailureCode.RATE_LIMIT -> retryAfterSeconds?.let(::listOf).orEmpty()
                GenerationFailureCode.CONTEXT_LIMIT -> listOfNotNull(
                    failure.formatArgs[GenerationFailure.KEY_REQUIRED_TOKENS]?.toLongOrNull(),
                    failure.formatArgs[GenerationFailure.KEY_AVAILABLE_TOKENS]?.toLongOrNull(),
                ).takeIf { it.size == 2 }.orEmpty()
                else -> emptyList()
            },
            technical = failure.technical,
        )
    }

    /** Room 旧列只接受有效结构化信封；其它值一律回落通用失败。 */
    fun fromPersisted(raw: String?): UiStatusNotice {
        val decoded = runCatching { GenerationFailureCodec.decode(raw) }.getOrNull()
        return from(decoded ?: GenerationFailure.unknown(technical = null))
    }

    /** 未知 notice code 也由通用资源兜底，绝不展示内部 code 或其参数。 */
    fun template(notice: UiStatusNotice): ResolvedStatus = when (notice.code) {
        CODE_NETWORK -> ResolvedStatus(R.string.generation_failure_network)
        CODE_AUTH -> ResolvedStatus(R.string.generation_failure_auth)
        CODE_RATE_LIMIT -> ResolvedStatus(R.string.generation_failure_rate_limit)
        CODE_RATE_LIMIT_RETRY -> {
            val seconds = (notice.formatArgs.firstOrNull() as? Int)?.takeIf { it > 0 }
            if (seconds != null) {
                ResolvedStatus(R.string.generation_failure_rate_limit_retry, listOf(seconds))
            } else {
                ResolvedStatus(R.string.generation_failure_rate_limit)
            }
        }
        CODE_QUOTA -> ResolvedStatus(R.string.generation_failure_quota)
        CODE_TIMEOUT -> ResolvedStatus(R.string.generation_failure_timeout)
        CODE_INVALID_REQUEST -> ResolvedStatus(R.string.generation_failure_invalid_request)
        CODE_CONTEXT_LIMIT -> if (notice.formatArgs.size == 2) {
            ResolvedStatus(R.string.chat_document_error_over_capacity, notice.formatArgs)
        } else {
            ResolvedStatus(R.string.chat_document_error_invalid_estimate)
        }
        CODE_SERVER -> ResolvedStatus(R.string.generation_failure_server)
        CODE_BUSY -> ResolvedStatus(R.string.generation_failure_busy)
        CODE_PERSISTENCE -> ResolvedStatus(R.string.generation_failure_persistence)
        CODE_UNKNOWN -> ResolvedStatus(R.string.generation_failure_unknown)
        else -> ResolvedStatus(R.string.generation_failure_unknown)
    }
}
