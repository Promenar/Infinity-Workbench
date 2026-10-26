package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.remote.ModelTimeoutKind
import com.promenar.nexara.data.remote.ModelTimeoutSettings
import org.junit.Test

class ModelTimeoutSpecTest {
    @Test
    fun `每类超时的默认值与设置默认值一致且在预设里`() {
        val defaults = ModelTimeoutSettings()
        ModelTimeoutKind.entries.forEach { kind ->
            val spec = modelTimeoutSpec(kind, "秒")
            assertThat(spec.defaultValue).isEqualTo(defaults.seconds(kind).toDouble())
            assertThat(spec.presets.map { it.value }).contains(spec.defaultValue)
        }
    }

    @Test
    fun `自定义输入限制在允许范围内并显示单位`() {
        val spec = modelTimeoutSpec(ModelTimeoutKind.LLM, "秒")
        assertThat(spec.parseCustom("900").getOrNull()).isEqualTo(900.0)
        assertThat(spec.parseCustom("4").isFailure).isTrue()
        assertThat(spec.parseCustom("1801").isFailure).isTrue()
        assertThat(spec.format(300.0)).isEqualTo("300 秒")
    }

    @Test
    fun `对话与生图用分钟级预设，向量与重排用十秒级预设`() {
        assertThat(modelTimeoutSpec(ModelTimeoutKind.IMAGE, "s").presets.map { it.value.toInt() })
            .containsExactly(60, 120, 300, 600).inOrder()
        assertThat(modelTimeoutSpec(ModelTimeoutKind.RERANK, "s").presets.map { it.value.toInt() })
            .containsExactly(15, 30, 60, 120).inOrder()
    }
}
