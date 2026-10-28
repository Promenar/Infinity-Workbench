package com.promenar.infinity.data.remote.parser

import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.ProviderErrorMessage

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
    /** 服务商响应中的错误说明（已清洗），只来自 HTTP 错误响应体。 */
    abstract val providerMessage: String?

    final override fun toString(): String =
        "NormalizedError(category=$category, retryable=$retryable, retryAfter=$retryAfter)"

    data class Network(
        override val technicalMessage: String,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.NETWORK
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class Auth(
        override val technicalMessage: String,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.AUTH
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class RateLimit(
        override val technicalMessage: String,
        override val retryAfter: Int?,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.RATE_LIMIT
        override val retryable = true
    }

    data class InvalidRequest(
        override val technicalMessage: String,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.INVALID_REQUEST
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class ServerError(
        override val technicalMessage: String,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.SERVER_ERROR
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class QuotaExceeded(
        override val technicalMessage: String,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.QUOTA_EXCEEDED
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class Timeout(
        override val technicalMessage: String,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.TIMEOUT
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class Unknown(
        override val technicalMessage: String,
        override val providerMessage: String? = null,
    ) : NormalizedError() {
        override val category = ErrorCategory.UNKNOWN
        override val retryable = true
        override val retryAfter: Int? = null
    }

    /**
     * 转为结构化失败。technical 仅诊断，code + 类型化 retryAfter 参数供上层映射。
     */
    fun toFailure(cause: Throwable? = null): GenerationFailure {
        val failure = when (this) {
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
        val provider = providerMessage ?: return failure
        return failure.copy(formatArgs = failure.formatArgs + (GenerationFailure.KEY_PROVIDER_MESSAGE to provider))
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
        val provider = (error as? HttpStatusException)?.let { ProviderErrorMessage.extract(it.message) }

        // HTTP 错误先看状态码；响应体文本只作为分类补充，避免把正文里的数字或单词当成网络/鉴权错误。
        if (error !is HttpStatusException && isNetworkError(error, errorMsgLower)) {
            return NormalizedError.Network(technicalMessage = errorMsg)
        }

        if (isAuthError(error, statusCode, errorMsgLower)) {
            return NormalizedError.Auth(technicalMessage = "$statusCode: $errorMsg", providerMessage = provider)
        }

        if (isRateLimitError(error, statusCode, errorMsgLower)) {
            val retryAfter = extractRetryAfter(error) ?: 60
            return NormalizedError.RateLimit(
                technicalMessage = errorMsg,
                retryAfter = retryAfter,
                providerMessage = provider,
            )
        }

        if (isQuotaError(errorMsgLower)) {
            return NormalizedError.QuotaExceeded(technicalMessage = errorMsg, providerMessage = provider)
        }

        if (statusCode == 408 || isTimeoutError(error, errorMsgLower)) {
            return NormalizedError.Timeout(technicalMessage = errorMsg, providerMessage = provider)
        }

        if (statusCode in 400..499) {
            return NormalizedError.InvalidRequest(technicalMessage = errorMsg, providerMessage = provider)
        }

        if (statusCode >= 500) {
            return NormalizedError.ServerError(technicalMessage = errorMsg, providerMessage = provider)
        }

        if (error is HttpStatusException && isNetworkError(error, errorMsgLower)) {
            return NormalizedError.Network(technicalMessage = errorMsg)
        }

        return NormalizedError.Unknown(technicalMessage = errorMsg, providerMessage = provider)
    }

    private fun extractStatusCode(error: Throwable): Int {
        return when (error) {
            is HttpStatusException -> error.statusCode
            else -> {
                val msg = error.message ?: ""
                // 只认带 status / HTTP 前缀的状态码，避免把 JSON 偏移量等任意三位数当成 HTTP 状态。
                val match = Regex("""(?i)\b(?:status(?:[_ ]?code)?|http(?:/\d(?:\.\d)?)?)[\s:=]*(\d{3})\b""").find(msg)
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
        // HTTP 响应只看状态码：响应体里的请求编号常含 401/403 这样的数字；被包成 403 的限流交给重试。
        if (error is HttpStatusException) return status == 401 || (status == 403 && RATE_LIMIT_PHRASES.none { it in msg })
        return status == 401 ||
            status == 403 ||
            Regex("""\b40[13]\b""").containsMatchIn(msg) ||
            msg.contains("unauthorized") ||
            msg.contains("forbidden") ||
            msg.contains("authentication") ||
            msg.contains("api key")
    }

    private fun isRateLimitError(error: Throwable, status: Int, msg: String): Boolean {
        if (status == 429 || status == 529) return true
        // 网关常把上游限流、过载包成 400/403/5xx 返回，按响应体说法识别，交给自动重试。
        return RATE_LIMIT_PHRASES.any { it in msg }
    }

    private val RATE_LIMIT_PHRASES = listOf(
        "rate limit", "rate_limit", "ratelimit", "too many requests", "throttl", "overloaded", "over capacity",
        "server is busy", "concurrency limit", "tpm limit", "rpm limit",
        "限流", "频繁", "负载已饱和", "负载过高", "饱和", "繁忙", "并发", "请求过多", "速率",
    )

    private fun isQuotaError(msg: String): Boolean {
        return msg.contains("quota") ||
            msg.contains("余额不足") ||
            msg.contains("额度") ||
            msg.contains("insufficient balance") ||
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
