package com.promenar.nexara.data.remote.parser

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

sealed class NormalizedError {
    abstract val category: ErrorCategory
    abstract val message: String
    abstract val technicalMessage: String
    abstract val retryable: Boolean
    abstract val retryAfter: Int?

    data class Network(
        override val message: String,
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.NETWORK
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class Auth(
        override val message: String,
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.AUTH
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class RateLimit(
        override val message: String,
        override val technicalMessage: String,
        override val retryAfter: Int?
    ) : NormalizedError() {
        override val category = ErrorCategory.RATE_LIMIT
        override val retryable = true
    }

    data class InvalidRequest(
        override val message: String,
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.INVALID_REQUEST
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class ServerError(
        override val message: String,
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.SERVER_ERROR
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class QuotaExceeded(
        override val message: String,
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.QUOTA_EXCEEDED
        override val retryable = false
        override val retryAfter: Int? = null
    }

    data class Timeout(
        override val message: String,
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.TIMEOUT
        override val retryable = true
        override val retryAfter: Int? = null
    }

    data class Unknown(
        override val message: String,
        override val technicalMessage: String
    ) : NormalizedError() {
        override val category = ErrorCategory.UNKNOWN
        override val retryable = true
        override val retryAfter: Int? = null
    }
}

object ErrorNormalizer {

    fun normalize(error: Throwable?, providerType: ProviderType? = null): NormalizedError {
        if (error == null) {
            return NormalizedError.Unknown(
                message = "发生未知错误，请重试",
                technicalMessage = "null error"
            )
        }

        val errorMsg = error.message ?: error.toString()
        val errorMsgLower = errorMsg.lowercase()
        val statusCode = extractStatusCode(error)

        if (isNetworkError(error, errorMsgLower)) {
            return NormalizedError.Network(
                message = "网络连接失败，请检查您的网络设置",
                technicalMessage = errorMsg
            )
        }

        if (isAuthError(error, statusCode, errorMsgLower)) {
            return NormalizedError.Auth(
                message = "API 密钥无效或已过期，请检查设置",
                technicalMessage = "$statusCode: $errorMsg"
            )
        }

        if (isRateLimitError(error, statusCode, errorMsgLower)) {
            val retryAfter = extractRetryAfter(error) ?: 60
            val waitTime = formatWaitTime(retryAfter)
            return NormalizedError.RateLimit(
                message = "请求过于频繁，请等待 $waitTime 后重试",
                technicalMessage = errorMsg,
                retryAfter = retryAfter
            )
        }

        if (isQuotaError(errorMsgLower)) {
            return NormalizedError.QuotaExceeded(
                message = "API 配额已用尽，请升级套餐或明日再试",
                technicalMessage = errorMsg
            )
        }

        if (isTimeoutError(error, errorMsgLower)) {
            return NormalizedError.Timeout(
                message = "请求超时，请重试",
                technicalMessage = errorMsg
            )
        }

        if (statusCode in 400..499) {
            return NormalizedError.InvalidRequest(
                message = "请求格式错误，请检查输入内容",
                technicalMessage = errorMsg
            )
        }

        if (statusCode >= 500) {
            return NormalizedError.ServerError(
                message = "API 服务暂时不可用，请稍后重试",
                technicalMessage = errorMsg
            )
        }

        return NormalizedError.Unknown(
            message = "发生未知错误，请重试",
            technicalMessage = errorMsg
        )
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

    private fun formatWaitTime(seconds: Int): String {
        return when {
            seconds < 60 -> "$seconds 秒"
            seconds < 3600 -> "${(seconds + 59) / 60} 分钟"
            seconds < 86400 -> "${(seconds + 3599) / 3600} 小时"
            else -> "${(seconds + 86399) / 86400} 天"
        }
    }
}

open class HttpStatusException(
    val statusCode: Int,
    override val message: String
) : Exception(message)

interface RetryAfterHolder {
    val retryAfter: Int?
}
