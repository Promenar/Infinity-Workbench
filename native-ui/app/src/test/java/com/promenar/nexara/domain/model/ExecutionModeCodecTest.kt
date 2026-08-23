package com.promenar.nexara.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecutionModeCodecTest {
    @Test
    fun `缺失或未知模式回退为 semi`() {
        listOf(null, "", "   ", "unknown", "automatic").forEach { raw ->
            assertThat(ExecutionModeCodec.parseOrSemi(raw)).isEqualTo(ExecutionMode.SEMI)
        }
    }

    @Test
    fun `合法模式忽略大小写并稳定序列化`() {
        assertThat(ExecutionModeCodec.parseOrSemi("AUTO")).isEqualTo(ExecutionMode.AUTO)
        assertThat(ExecutionModeCodec.parseOrSemi(" Semi ")).isEqualTo(ExecutionMode.SEMI)
        assertThat(ExecutionModeCodec.parseOrSemi("manual")).isEqualTo(ExecutionMode.MANUAL)

        assertThat(ExecutionModeCodec.serialize(ExecutionMode.AUTO)).isEqualTo("auto")
        assertThat(ExecutionModeCodec.serialize(ExecutionMode.SEMI)).isEqualTo("semi")
        assertThat(ExecutionModeCodec.serialize(ExecutionMode.MANUAL)).isEqualTo("manual")
    }
}
