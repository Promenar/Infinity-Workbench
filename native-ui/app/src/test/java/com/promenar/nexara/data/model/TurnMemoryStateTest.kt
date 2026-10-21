package com.promenar.nexara.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TurnMemoryStateTest {
    @Test
    fun `状态按JSON往返，旧版纯文本状态与损坏内容按无状态处理`() {
        val stored = TurnMemoryState(TurnMemoryState.Status.STORED, chunkCount = 3, updatedAt = 42)
        assertThat(stored.encode()).isEqualTo("""{"status":"stored","chunkCount":3,"updatedAt":42}""")
        assertThat(TurnMemoryState.decode(stored.encode())).isEqualTo(stored)

        for (legacy in listOf(null, "", "success", "processing", "error", "{broken", """{"status":"unknown"}""")) {
            assertThat(TurnMemoryState.decode(legacy)).isNull()
        }
        assertThat(Message("a", MessageRole.ASSISTANT, "", vectorizationStatus = """{"status":"removed"}""").turnMemory?.status)
            .isEqualTo(TurnMemoryState.Status.REMOVED)
    }
}
