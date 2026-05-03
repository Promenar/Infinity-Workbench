package com.promenar.nexara.data.remote.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ErrorNormalizerTest {

    private fun normalize(error: Throwable?) = ErrorNormalizer.normalize(error)

    @Nested
    @DisplayName("null error")
    inner class NullError {

        @Test
        fun `null returns Unknown`() {
            val result = normalize(null)
            assertThat(result).isInstanceOf(NormalizedError.Unknown::class.java)
            assertThat(result.retryable).isTrue()
        }
    }

    @Nested
    @DisplayName("Network errors")
    inner class NetworkErrors {

        @Test
        fun `network in message`() {
            val result = normalize(Exception("Network error"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
            assertThat(result.retryable).isTrue()
            assertThat(result.category).isEqualTo(ErrorCategory.NETWORK)
        }

        @Test
        fun `connection in message`() {
            val result = normalize(Exception("Connection refused"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `fetch in message`() {
            val result = normalize(Exception("Failed to fetch"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `ECONNREFUSED in message`() {
            val result = normalize(Exception("Error: ECONNREFUSED 127.0.0.1:443"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `NetworkError class name`() {
            val result = normalize(NetworkError("err_network"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }
    }

    @Nested
    @DisplayName("Auth errors")
    inner class AuthErrors {

        @Test
        fun `401 status code`() {
            val result = normalize(HttpStatusException(401, "Unauthorized"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
            assertThat(result.retryable).isFalse()
        }

        @Test
        fun `403 status code`() {
            val result = normalize(HttpStatusException(403, "Forbidden"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
            assertThat(result.retryable).isFalse()
        }

        @Test
        fun `unauthorized in message`() {
            val result = normalize(Exception("Unauthorized access"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `api key in message`() {
            val result = normalize(Exception("Invalid API key provided"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `401 in message text`() {
            val result = normalize(Exception("HTTP 401: Auth failed"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `forbidden in message`() {
            val result = normalize(Exception("Access forbidden"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }
    }

    @Nested
    @DisplayName("Rate limit errors")
    inner class RateLimitErrors {

        @Test
        fun `429 status code`() {
            val result = normalize(HttpStatusException(429, "Too Many Requests"))
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryable).isTrue()
            assertThat(result.retryAfter).isEqualTo(60)
        }

        @Test
        fun `429 with retry-after in message`() {
            val result = normalize(
                HttpStatusException(429, "Rate limited. Retry after 30 seconds.")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(30)
        }

        @Test
        fun `429 with wait seconds in message`() {
            val result = normalize(
                HttpStatusException(429, "Please wait 120 seconds before retrying")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(120)
        }

        @Test
        fun `rate limit in message`() {
            val result = normalize(Exception("Rate limit exceeded"))
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }

        @Test
        fun `throttle in message`() {
            val result = normalize(Exception("Request throttled"))
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }

        @Test
        fun `RetryAfterHolder provides retry time`() {
            val error = object : Exception("rate limited"), RetryAfterHolder {
                override val retryAfter = 45
            }
            val result = normalize(error)
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(45)
        }

        @Test
        fun `wait time format - seconds`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 30 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.message).contains("30 秒")
        }

        @Test
        fun `wait time format - minutes`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 120 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.message).contains("分钟")
        }

        @Test
        fun `wait time format - hours`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 7200 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.message).contains("小时")
        }

        @Test
        fun `wait time format - days`() {
            val result = ErrorNormalizer.normalize(
                HttpStatusException(429, "Retry after 172800 seconds")
            ) as NormalizedError.RateLimit
            assertThat(result.message).contains("天")
        }
    }

    @Nested
    @DisplayName("Quota errors")
    inner class QuotaErrors {

        @Test
        fun `quota in message`() {
            val result = normalize(Exception("You exceeded your quota"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
            assertThat(result.retryable).isFalse()
        }

        @Test
        fun `insufficient_quota in message`() {
            val result = normalize(Exception("Error: insufficient_quota"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
        }

        @Test
        fun `billing in message`() {
            val result = normalize(Exception("Billing limit exceeded"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
        }

        @Test
        fun `limit exceeded in message`() {
            val result = normalize(Exception("Usage limit exceeded"))
            assertThat(result).isInstanceOf(NormalizedError.QuotaExceeded::class.java)
        }
    }

    @Nested
    @DisplayName("Timeout errors")
    inner class TimeoutErrors {

        @Test
        fun `timeout in message`() {
            val result = normalize(Exception("Request timeout"))
            assertThat(result).isInstanceOf(NormalizedError.Timeout::class.java)
            assertThat(result.retryable).isTrue()
        }

        @Test
        fun `ETIMEDOUT in message`() {
            val result = normalize(Exception("Error: ETIMEDOUT"))
            assertThat(result).isInstanceOf(NormalizedError.Timeout::class.java)
        }

        @Test
        fun `TimeoutError class name`() {
            val result = normalize(TimeoutError("Request took too long"))
            assertThat(result).isInstanceOf(NormalizedError.Timeout::class.java)
        }
    }

    @Nested
    @DisplayName("Invalid request errors (4xx)")
    inner class InvalidRequestErrors {

        @Test
        fun `400 status code`() {
            val result = normalize(HttpStatusException(400, "Bad request"))
            assertThat(result).isInstanceOf(NormalizedError.InvalidRequest::class.java)
            assertThat(result.retryable).isFalse()
        }

        @Test
        fun `422 status code`() {
            val result = normalize(HttpStatusException(422, "Unprocessable entity"))
            assertThat(result).isInstanceOf(NormalizedError.InvalidRequest::class.java)
        }

        @Test
        fun `404 status code is not auth`() {
            val result = normalize(HttpStatusException(404, "Not found"))
            assertThat(result).isInstanceOf(NormalizedError.InvalidRequest::class.java)
        }
    }

    @Nested
    @DisplayName("Server errors (5xx)")
    inner class ServerErrors {

        @Test
        fun `500 status code`() {
            val result = normalize(HttpStatusException(500, "Internal server error"))
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
            assertThat(result.retryable).isTrue()
        }

        @Test
        fun `502 status code`() {
            val result = normalize(HttpStatusException(502, "Bad gateway"))
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
        }

        @Test
        fun `503 status code`() {
            val result = normalize(HttpStatusException(503, "Service unavailable"))
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
        }
    }

    @Nested
    @DisplayName("Unknown errors")
    inner class UnknownErrors {

        @Test
        fun `unrecognized error returns Unknown`() {
            val result = normalize(Exception("Something went wrong"))
            assertThat(result).isInstanceOf(NormalizedError.Unknown::class.java)
            assertThat(result.retryable).isTrue()
        }

        @Test
        fun `null message returns Unknown`() {
            val result = normalize(Exception(null as String?))
            assertThat(result).isInstanceOf(NormalizedError.Unknown::class.java)
        }
    }

    @Nested
    @DisplayName("Priority / precedence")
    inner class Precedence {

        @Test
        fun `network takes priority over rate limit text`() {
            val result = normalize(Exception("Network rate limit connection error"))
            assertThat(result).isInstanceOf(NormalizedError.Network::class.java)
        }

        @Test
        fun `auth status 401 takes priority over timeout text`() {
            val result = normalize(HttpStatusException(401, "Timeout waiting for auth"))
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `rate limit 429 takes priority over quota text`() {
            val result = normalize(
                HttpStatusException(429, "quota exceeded rate limit")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }
    }

    @Nested
    @DisplayName("Provider-specific error patterns")
    inner class ProviderSpecific {

        @Test
        fun `OpenAI-style error`() {
            val result = normalize(
                HttpStatusException(429, "You exceeded your current quota. Please check your plan and billing details.")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
        }

        @Test
        fun `DeepSeek-style rate limit`() {
            val result = normalize(
                HttpStatusException(429, "Rate limit reached. Retry after 60 seconds.")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(60)
        }

        @Test
        fun `Anthropic-style auth error`() {
            val result = normalize(
                HttpStatusException(401, "invalid x-api-key")
            )
            assertThat(result).isInstanceOf(NormalizedError.Auth::class.java)
        }

        @Test
        fun `VertexAI-style server error`() {
            val result = normalize(
                HttpStatusException(500, "Internal server error in Vertex AI")
            )
            assertThat(result).isInstanceOf(NormalizedError.ServerError::class.java)
        }

        @Test
        fun `MiniMax-style 429 with wait seconds`() {
            val result = normalize(
                HttpStatusException(429, "Please wait 43877 seconds before retrying")
            )
            assertThat(result).isInstanceOf(NormalizedError.RateLimit::class.java)
            assertThat(result.retryAfter).isEqualTo(43877)
        }
    }

    @Nested
    @DisplayName("NormalizedError sealed class properties")
    inner class SealedClassProperties {

        @Test
        fun `all subtypes have correct category`() {
            val errors: List<NormalizedError> = listOf(
                NormalizedError.Network("net", "tech"),
                NormalizedError.Auth("auth", "tech"),
                NormalizedError.RateLimit("rate", "tech", 30),
                NormalizedError.InvalidRequest("inv", "tech"),
                NormalizedError.ServerError("srv", "tech"),
                NormalizedError.QuotaExceeded("quota", "tech"),
                NormalizedError.Timeout("tim", "tech"),
                NormalizedError.Unknown("unk", "tech")
            )
            val expected = listOf(
                ErrorCategory.NETWORK,
                ErrorCategory.AUTH,
                ErrorCategory.RATE_LIMIT,
                ErrorCategory.INVALID_REQUEST,
                ErrorCategory.SERVER_ERROR,
                ErrorCategory.QUOTA_EXCEEDED,
                ErrorCategory.TIMEOUT,
                ErrorCategory.UNKNOWN
            )
            assertThat(errors.map { it.category }).isEqualTo(expected)
        }

        @Test
        fun `retryable flags are correct`() {
            assertThat(NormalizedError.Network("", "").retryable).isTrue()
            assertThat(NormalizedError.Auth("", "").retryable).isFalse()
            assertThat(NormalizedError.RateLimit("", "", null).retryable).isTrue()
            assertThat(NormalizedError.InvalidRequest("", "").retryable).isFalse()
            assertThat(NormalizedError.ServerError("", "").retryable).isTrue()
            assertThat(NormalizedError.QuotaExceeded("", "").retryable).isFalse()
            assertThat(NormalizedError.Timeout("", "").retryable).isTrue()
            assertThat(NormalizedError.Unknown("", "").retryable).isTrue()
        }

        @Test
        fun `RateLimit preserves retryAfter`() {
            val err = NormalizedError.RateLimit("msg", "tech", 120)
            assertThat(err.retryAfter).isEqualTo(120)
        }

        @Test
        fun `RateLimit null retryAfter`() {
            val err = NormalizedError.RateLimit("msg", "tech", null)
            assertThat(err.retryAfter).isNull()
        }
    }
}

private class NetworkError(message: String) : Exception(message)
private class TimeoutError(message: String) : Exception(message)
