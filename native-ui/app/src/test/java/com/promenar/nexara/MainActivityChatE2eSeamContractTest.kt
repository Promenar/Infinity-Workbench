package com.promenar.nexara

import org.junit.Test
import com.google.common.truth.Truth.assertThat

class MainActivityChatE2eSeamContractTest {
    @Test
    fun `Release构建忽略指定会话测试入口`() {
        assertThat(debugChatSessionId(false, "session-e2e")).isNull()
    }

    @Test
    fun `Debug构建只接受并规范化非空会话ID`() {
        assertThat(debugChatSessionId(true, " session-e2e "))
            .isEqualTo("session-e2e")
        assertThat(debugChatSessionId(true, "  ")).isNull()
        assertThat(debugChatSessionId(true, null)).isNull()
    }
}
