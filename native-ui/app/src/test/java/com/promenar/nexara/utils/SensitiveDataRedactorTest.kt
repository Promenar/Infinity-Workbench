package com.promenar.nexara.utils

import com.google.common.truth.Truth.assertThat
import okhttp3.Headers.Companion.toHeaders
import org.junit.jupiter.api.Test

class SensitiveDataRedactorTest {
    @Test
    fun `redactHeaders removes credential values`() {
        val headers = mapOf(
            "Authorization" to "Bearer obviously-fake-token",
            "Cookie" to "session=obviously-fake-cookie",
            "X-Api-Key" to "obviously-fake-api-key",
            "Content-Type" to "application/json"
        ).toHeaders()

        val redacted = SensitiveDataRedactor.redactHeaders(headers)

        assertThat(redacted["Authorization"]).isEqualTo("[REDACTED]")
        assertThat(redacted["Cookie"]).isEqualTo("[REDACTED]")
        assertThat(redacted["X-Api-Key"]).isEqualTo("[REDACTED]")
        assertThat(redacted["Content-Type"]).isEqualTo("application/json")
        assertThat(redacted.toString()).doesNotContain("obviously-fake")
    }

    @Test
    fun `redactUrl removes query and fragment`() {
        val redacted = SensitiveDataRedactor.redactUrl(
            "https://example.invalid/v1/chat?api_key=obviously-fake-api-key#private-fragment"
        )

        assertThat(redacted).isEqualTo("https://example.invalid/v1/chat")
    }

    @Test
    fun `redactMessage removes structured user content and credentials`() {
        val message = """
            Authorization: Bearer obviously-fake-token
            Cookie: session=obviously-fake-cookie
            prompt=obviously-fake-prompt
            response=obviously-fake-response
            body=obviously-fake-body
            bindArgs=[obviously-fake-sql-value]
            url=https://example.invalid/chat?token=obviously-fake-query
        """.trimIndent()

        val redacted = SensitiveDataRedactor.redactMessage(message)

        assertThat(redacted).doesNotContain("obviously-fake")
        assertThat(redacted).doesNotContain("?token=")
        assertThat(redacted).contains("[REDACTED]")
    }

    @Test
    fun `safeThrowable never includes message or stack trace`() {
        val throwable = IllegalStateException("request failed with obviously-fake-token")
            .apply { stackTrace = arrayOf(StackTraceElement("SecretClass", "secretMethod", "Secret.kt", 42)) }

        val debugText = SensitiveDataRedactor.safeThrowable(throwable, debug = true)
        val releaseText = SensitiveDataRedactor.safeThrowable(throwable, debug = false)

        assertThat(debugText).doesNotContain("obviously-fake")
        assertThat(debugText).doesNotContain("SecretClass")
        assertThat(releaseText).doesNotContain("obviously-fake")
        assertThat(releaseText).doesNotContain("SecretClass")
        assertThat(releaseText).contains("IllegalStateException")
    }

    @Test
    fun `https validator accepts only absolute https URLs`() {
        assertThat(HttpsUrlValidator.isAllowed("https://example.invalid/v1")).isTrue()
        assertThat(HttpsUrlValidator.isAllowed("HTTPS://example.invalid/v1")).isTrue()
        assertThat(HttpsUrlValidator.isAllowed("http://example.invalid/v1")).isFalse()
        assertThat(HttpsUrlValidator.isAllowed("//example.invalid/v1")).isFalse()
        assertThat(HttpsUrlValidator.isAllowed("not-a-url")).isFalse()
    }
}
