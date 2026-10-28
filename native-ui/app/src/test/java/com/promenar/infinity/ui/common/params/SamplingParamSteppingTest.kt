package com.promenar.infinity.ui.common.params

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.params.SamplingParam
import org.junit.Test

class SamplingParamSteppingTest {

    @Test
    fun `未设置时加减都从起点开始，之后按步长移动并对齐网格`() {
        assertThat(stepSamplingValue(SamplingParam.TEMPERATURE, null, 1)).isEqualTo(1.0)
        assertThat(stepSamplingValue(SamplingParam.TEMPERATURE, null, -1)).isEqualTo(1.0)
        assertThat(stepSamplingValue(SamplingParam.TEMPERATURE, 0.7, 1)).isEqualTo(0.75)
        assertThat(stepSamplingValue(SamplingParam.TEMPERATURE, 0.7, -1)).isEqualTo(0.65)
        // 手输的 0.72 不在网格上：加到 0.75，减到 0.70。
        assertThat(stepSamplingValue(SamplingParam.TEMPERATURE, 0.72, 1)).isEqualTo(0.75)
        assertThat(stepSamplingValue(SamplingParam.TEMPERATURE, 0.72, -1)).isEqualTo(0.7)
        assertThat(stepSamplingValue(SamplingParam.PRESENCE_PENALTY, 0.0, -1)).isEqualTo(-0.1)
        assertThat(stepSamplingValue(SamplingParam.TOP_K, 40.0, 1)).isEqualTo(41.0)
    }

    @Test
    fun `最大输出按 2 的幂翻倍或减半，并停在范围两端`() {
        assertThat(stepSamplingValue(SamplingParam.MAX_TOKENS, 4096.0, 1)).isEqualTo(8192.0)
        assertThat(stepSamplingValue(SamplingParam.MAX_TOKENS, 4096.0, -1)).isEqualTo(2048.0)
        assertThat(stepSamplingValue(SamplingParam.MAX_TOKENS, 5000.0, 1)).isEqualTo(8192.0)
        assertThat(stepSamplingValue(SamplingParam.MAX_TOKENS, 5000.0, -1)).isEqualTo(4096.0)
        assertThat(stepSamplingValue(SamplingParam.MAX_TOKENS, 256.0, -1)).isEqualTo(256.0)
        assertThat(stepSamplingValue(SamplingParam.MAX_TOKENS, 262_144.0, 1)).isEqualTo(262_144.0)
        assertThat(canStepSamplingValue(SamplingParam.MAX_TOKENS, 256.0, -1)).isFalse()
        assertThat(canStepSamplingValue(SamplingParam.TEMPERATURE, 2.0, 1)).isFalse()
        assertThat(canStepSamplingValue(SamplingParam.TEMPERATURE, null, -1)).isTrue()
    }

    @Test
    fun `输入解析拒绝格式错误、越界与整数参数的小数`() {
        assertThat(parseSamplingInput(SamplingParam.TEMPERATURE, " 0.7 ")).isEqualTo(0.7)
        assertThat(parseSamplingInput(SamplingParam.TEMPERATURE, ".5")).isEqualTo(0.5)
        assertThat(parseSamplingInput(SamplingParam.TEMPERATURE, "2.5")).isNull()
        assertThat(parseSamplingInput(SamplingParam.TEMPERATURE, "abc")).isNull()
        assertThat(parseSamplingInput(SamplingParam.FREQUENCY_PENALTY, "-1.5")).isEqualTo(-1.5)
        assertThat(parseSamplingInput(SamplingParam.TOP_K, "20.5")).isNull()
        assertThat(parseSamplingInput(SamplingParam.MAX_TOKENS, "100")).isNull()
    }

    @Test
    fun `显示写法：小数两位，最大输出整 K 写作 K`() {
        assertThat(formatSamplingValue(SamplingParam.TEMPERATURE, 0.7)).isEqualTo("0.70")
        assertThat(formatSamplingValue(SamplingParam.MAX_TOKENS, 8192.0)).isEqualTo("8K")
        assertThat(formatSamplingValue(SamplingParam.MAX_TOKENS, 5000.0)).isEqualTo("5000")
        assertThat(formatSamplingInput(SamplingParam.MAX_TOKENS, 8192.0)).isEqualTo("8192")
        assertThat(formatSamplingValue(SamplingParam.TOP_K, 40.0)).isEqualTo("40")
    }
}
