package com.promenar.nexara.data.local.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalInferenceRuntimeGateTest {
    @Test
    fun `能力不可用时可选依赖不会被求值`() {
        var factoryCalls = 0

        val value = LocalInferenceRuntimeGate(isAvailable = false).createIfAvailable {
            factoryCalls += 1
            "engine"
        }

        assertThat(value).isNull()
        assertThat(factoryCalls).isEqualTo(0)
    }

    @Test
    fun `能力可用时可选依赖只求值一次`() {
        var factoryCalls = 0

        val value = LocalInferenceRuntimeGate(isAvailable = true).createIfAvailable {
            factoryCalls += 1
            "engine"
        }

        assertThat(value).isEqualTo("engine")
        assertThat(factoryCalls).isEqualTo(1)
    }

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

    @Test
    fun `内存压力清理在能力不可用时绝不求值引擎工厂也不执行清理`() {
        var factoryEvaluations = 0
        var cleanupExecutions = 0
        val engineFactory: () -> String = {
            factoryEvaluations += 1
            "engine"
        }

        // 与 NexaraApplication.onTrimMemory 相同的门禁形态：createIfAvailable { ... }?.let { 清理 }
        val engine = LocalInferenceRuntimeGate(isAvailable = false)
            .createIfAvailable(engineFactory)

        assertThat(engine).isNull()
        engine?.let {
            cleanupExecutions += 1
        }

        assertThat(factoryEvaluations).isEqualTo(0)
        assertThat(cleanupExecutions).isEqualTo(0)
    }

    @Test
    fun `内存压力清理在能力可用时求值引擎工厂并执行清理`() {
        var factoryEvaluations = 0
        var cleanupExecutions = 0
        val engineFactory: () -> String = {
            factoryEvaluations += 1
            "engine"
        }

        val engine = LocalInferenceRuntimeGate(isAvailable = true)
            .createIfAvailable(engineFactory)

        assertThat(engine).isEqualTo("engine")
        engine?.let {
            cleanupExecutions += 1
        }

        assertThat(factoryEvaluations).isEqualTo(1)
        assertThat(cleanupExecutions).isEqualTo(1)
    }
}
