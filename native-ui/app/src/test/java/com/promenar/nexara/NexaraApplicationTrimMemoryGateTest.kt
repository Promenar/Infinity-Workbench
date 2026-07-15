package com.promenar.nexara

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class NexaraApplicationTrimMemoryGateTest {
    private val source = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/NexaraApplication.kt")
    ).toString(Charsets.UTF_8)

    @Test
    fun `onTrimMemory 在能力不可用的发行变体下经门禁访问引擎而不触发 lazy requireAvailable`() {
        val onTrimMemory = functionBody("override fun onTrimMemory(level: Int)")

        // trim 路径必须复用本地推理能力门禁 createIfAvailable。
        assertThat(onTrimMemory).contains("localInferenceRuntimeGate.createIfAvailable")
        // 不得在门禁之外裸访问引擎工厂，否则能力不可用时会进入 lazy requireAvailable 抛出并崩溃。
        assertThat(onTrimMemory).doesNotContain("localInferenceEngine.unloadModel")
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val brace = source.indexOf('{', start)
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(brace + 1, index)
            }
        }
        error("函数未闭合: $signature")
    }
}
