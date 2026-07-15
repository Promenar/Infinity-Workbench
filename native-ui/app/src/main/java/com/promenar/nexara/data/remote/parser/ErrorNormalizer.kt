package com.promenar.nexara.data.remote.parser

import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode

enum class ErrorCategory {
    NETWORK,
    AUTH,
    RATE_LIMIT,
    INVALID_REQUEST,
    SERVER_ERROR,
    QUOTA_EXCEEDED,
    TIMEOUT,
    UNKNOWN
}

/**
 * 协议/网络层错误的分类结果。
 *
 * 自结构化失败契约起，本类**不再产出任何自然语言展示文案**：
 * - [technicalMessage] 仅供诊断（日志/调试），禁止作为 UI 展示 message 传播；
 * - 调用方应使用 [toFailure] 得到稳定的 [GenerationFailure]（code + 类型化参数）。
 */
sealed class NormalizedError {
    abstract val category: ErrorCategory
    abstract val technicalMessage: String
    abstract val retryable: Boolean
    abstract val retryAfter: Int?

    final override fun toString(): String =
        "NormalizedError(category=$category, retryable=$retryable, retryAfter=$retryAfter)"

    data class Network(
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.NETWORK
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class Auth(
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.AUTH
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class RateLimit(
        override val technicalMessage: String,
        override val retryAfter: Int?
    ) : NormalizedError() {
        override val category = ErrorCategory.RATE_LIMIT
        override val retryable = true
    }

    data class InvalidRequest(
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.INVALID_REQUEST
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class ServerError(
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.SERVER_ERROR
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class QuotaExceeded(
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.QUOTA_EXCEEDED
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class Timeout(
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.TIMEOUT
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class Unknown(
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.UNKNOWN
        override val retryable = true
        override val retryAfter: Int? = null
    }

    /**
     * 转为结构化失败。technical 仅诊断，code + 类型化 retryAfter 参数供上层映射。
     */
    fun toFailure(cause: Throwable? = null): GenerationFailure = when (this) {
        is Network -> GenerationFailure(GenerationFailureCode.NETWORK, emptyMap(), technicalMessage, cause)
        is Auth -> GenerationFailure(GenerationFailureCode.AUTH, emptyMap(), technicalMessage, cause)
        is RateLimit -> GenerationFailure.rateLimited(
            retryAfterSeconds = retryAfter,
            technical = technicalMessage,
            cause = cause,
        )
        is InvalidRequest -> GenerationFailure(GenerationFailureCode.INVALID_REQUEST, emptyMap(), technicalMessage, cause)
        is ServerError -> GenerationFailure(GenerationFailureCode.SERVER, emptyMap(), technicalMessage, cause)
        is QuotaExceeded -> GenerationFailure(GenerationFailureCode.QUOTA, emptyMap(), technicalMessage, cause)
        is Timeout -> GenerationFailure(GenerationFailureCode.TIMEOUT, emptyMap(), technicalMessage, cause)
        is Unknown -> GenerationFailure.unknown(technical = technicalMessage, cause = cause)
    }
}

object ErrorNormalizer {

    fun normalize(error: Throwable?, providerType: ProviderType? = null): NormalizedError {
        if (error == null) {
            return NormalizedError.Unknown(technicalMessage = "null error")
        }

        val errorMsg = error.message ?: error.toString()
        val errorMsgLower = errorMsg.lowercase()
        val statusCode = extractStatusCode(error)

        if (isNetworkError(error, errorMsgLower)) {
            return NormalizedError.Network(technicalMessage = errorMsg)
        }

        if (isAuthError(error, statusCode, errorMsgLower)) {
            return NormalizedError.Auth(technicalMessage = "$statusCode: $errorMsg")
        }

        if (isRateLimitError(error, statusCode, errorMsgLower)) {
            val retryAfter = extractRetryAfter(error) ?: 60
            return NormalizedError.RateLimit(
                technicalMessage = errorMsg,
                retryAfter = retryAfter,
            )
        }

        if (isQuotaError(errorMsgLower)) {
            return NormalizedError.QuotaExceeded(technicalMessage = errorMsg)
        }

        if (isTimeoutError(error, errorMsgLower)) {
            return NormalizedError.Timeout(technicalMessage = errorMsg)
        }

        if (statusCode in 400..499) {
            return NormalizedError.InvalidRequest(technicalMessage = errorMsg)
        }

        if (statusCode >= 500) {
            return NormalizedError.ServerError(technicalMessage = errorMsg)
        }

        return NormalizedError.Unknown(technicalMessage = errorMsg)
    }

    private fun extractStatusCode(error: Throwable): Int {
        return when (error) {
            is HttpStatusException -> error.statusCode
            else -> {
                val msg = error.message ?: ""
                val match = Regex("""\b(?:status[_ ]?code?[:\s]*)?(\d{3})\b""").find(msg)
                val code = match?.groupValues?.lastOrNull()?.toIntOrNull()
                if (code != null && code in 100..599) code else 0
            }
        }
    }

    private fun isNetworkError(error: Throwable, msg: String): Boolean {
        return msg.contains("network") ||
            msg.contains("fetch") ||
            msg.contains("connection") ||
            msg.contains("err_network") ||
            msg.contains("econnrefused") ||
            msg.contains("enotfound") ||
            error::class.simpleName?.lowercase()?.contains("network") == true
    }

    private fun isAuthError(error: Throwable, status: Int, msg: String): Boolean {
        return status == 401 ||
            status == 403 ||
            msg.contains("401") ||
            msg.contains("403") ||
            msg.contains("unauthorized") ||
            msg.contains("forbidden") ||
            msg.contains("authentication") ||
            msg.contains("api key")
    }

    private fun isRateLimitError(error: Throwable, status: Int, msg: String): Boolean {
        return status == 429 ||
            msg.contains("429") ||
            msg.contains("rate limit") ||
            msg.contains("too many requests") ||
            msg.contains("throttle")
    }

    private fun isQuotaError(msg: String): Boolean {
        return msg.contains("quota") ||
            msg.contains("limit exceeded") ||
            msg.contains("insufficient_quota") ||
            msg.contains("billing")
    }

    private fun isTimeoutError(error: Throwable, msg: String): Boolean {
        return msg.contains("timeout") ||
            msg.contains("etimedout") ||
            error::class.simpleName?.lowercase()?.contains("timeout") == true
    }

    private fun extractRetryAfter(error: Throwable): Int? {
        val msg = error.message ?: return null

        val retryAfterMatch = Regex("""retry after (\d+) seconds?""", RegexOption.IGNORE_CASE)
            .find(msg)
        if (retryAfterMatch != null) {
            return retryAfterMatch.groupValues[1].toIntOrNull()
        }

        val waitMatch = Regex("""wait (\d+) seconds?""", RegexOption.IGNORE_CASE)
            .find(msg)
        if (waitMatch != null) {
            return waitMatch.groupValues[1].toIntOrNull()
        }

        if (error is RetryAfterHolder) {
            return error.retryAfter
        }

        return null
    }
}

open class HttpStatusException(
    val statusCode: Int,
    override val message: String
) : Exception(message)

interface RetryAfterHolder {
    val retryAfter: Int?
}
