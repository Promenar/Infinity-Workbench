package com.promenar.nexara.data.local.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalInferenceRuntimeGateTest {
    @Test
    fun `启动链具有独立的本地推理能力门`() {
        val unavailable = LocalInferenceRuntimeGate(isAvailable = false)

        assertThat(
            unavailable.startupModelPath(
                localModelsEnabled = true,
                autoLoadEnabled = true,
                persistedModelPath = "/restored/model.gguf",
            ),
        ).isNull()
        assertThrows(IllegalStateException::class.java) {
            unavailable.requireAvailable()
        }
    }

    @Test
    fun `能力可用时仍要求两个开关同时开启且路径非空`() {
        val available = LocalInferenceRuntimeGate(isAvailable = true)

        assertThat(available.startupModelPath(true, true, "/models/a.gguf"))
            .isEqualTo("/models/a.gguf")
        assertThat(available.startupModelPath(false, true, "/models/a.gguf")).isNull()
        assertThat(available.startupModelPath(true, false, "/models/a.gguf")).isNull()
        assertThat(available.startupModelPath(true, true, " ")).isNull()
    }
}
