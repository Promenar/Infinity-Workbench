package com.promenar.nexara.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NexaraLoggerDiagnosticTest {
    @Test
    fun `诊断字段拒绝正文凭据和URL但保留状态元数据`() {
        val sanitized = sanitizeDiagnosticFields(
            mapOf(
                "messageContent" to "private prompt",
                "apiKey" to "sk-secret",
                "baseUrl" to "https://example.com/private",
                "sessionId" to "session-1",
                "count" to 3,
            ),
        )

        assertThat(sanitized["messageContent"]).isEqualTo("[omitted]")
        assertThat(sanitized["apiKey"]).isEqualTo("[omitted]")
        assertThat(sanitized["baseUrl"]).isEqualTo("[omitted]")
        assertThat(sanitized["sessionId"]).isEqualTo("session-1")
        assertThat(sanitized["count"]).isEqualTo(3)
    }
}
