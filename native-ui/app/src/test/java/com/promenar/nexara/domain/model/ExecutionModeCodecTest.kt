package com.promenar.nexara.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecutionModeCodecTest {
    @Test
    fun `旧值与新值读取后只保存两档范围权限`() {
        listOf("auto", "full_access", " AUTO ", "FULL_ACCESS").forEach { raw ->
            assertThat(ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrWorkspace(raw))).isEqualTo("full_access")
        }
        listOf(null, "", "   ", "semi", "manual", "workspace", "unknown", "automatic").forEach { raw ->
            assertThat(ExecutionModeCodec.serialize(ExecutionModeCodec.parseOrWorkspace(raw))).isEqualTo("workspace")
        }
    }
}
