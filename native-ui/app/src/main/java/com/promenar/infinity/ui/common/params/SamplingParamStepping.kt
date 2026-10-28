package com.promenar.infinity.ui.common.params

import com.promenar.infinity.data.model.params.SamplingParam
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor

/** 各参数的取值范围、加减步长与快捷值；[start] 是未设置时第一次加减的起点，不代表模型默认。 */
data class SamplingParamRange(
    val min: Double,
    val max: Double,
    val start: Double,
    /** 0 表示按 2 的幂翻倍或减半（最大输出）。 */
    val step: Double,
    val decimals: Int,
    val presets: List<Double>,
) {
    fun contains(value: Double): Boolean = value in min..max
}

fun samplingParamRange(param: SamplingParam): SamplingParamRange = when (param) {
    SamplingParam.TEMPERATURE -> SamplingParamRange(0.0, 2.0, 1.0, 0.05, 2, listOf(0.2, 0.7, 1.0, 1.5))
    SamplingParam.TOP_P -> SamplingParamRange(0.0, 1.0, 1.0, 0.05, 2, listOf(0.5, 0.8, 0.95, 1.0))
    SamplingParam.TOP_K -> SamplingParamRange(1.0, 500.0, 40.0, 1.0, 0, listOf(10.0, 20.0, 40.0, 100.0))
    SamplingParam.MAX_TOKENS -> SamplingParamRange(256.0, 262_144.0, 4096.0, 0.0, 0,
        listOf(4096.0, 8192.0, 16_384.0, 32_768.0, 65_536.0))
    SamplingParam.REPETITION_PENALTY -> SamplingParamRange(0.5, 2.0, 1.0, 0.05, 2, listOf(1.0, 1.05, 1.1, 1.2))
    SamplingParam.FREQUENCY_PENALTY, SamplingParam.PRESENCE_PENALTY ->
        SamplingParamRange(-2.0, 2.0, 0.0, 0.1, 2, listOf(-0.5, 0.0, 0.5, 1.0))
}

/**
 * 加减一步。未设置时取起点；不在步长网格上的值先对齐到相邻的网格点。
 * 最大输出按 2 的幂翻倍或减半，便于在 256 到 262144 之间快速移动。
 */
fun stepSamplingValue(param: SamplingParam, current: Double?, direction: Int): Double {
    val range = samplingParamRange(param)
    if (current == null) return range.start
    val next = if (range.step == 0.0) {
        val exponent = kotlin.math.log2(current)
        val target = if (direction > 0) floor(exponent + 1e-9) + 1 else ceil(exponent - 1e-9) - 1
        Math.pow(2.0, target)
    } else {
        val units = current / range.step
        val target = if (direction > 0) floor(units + 1e-9) + 1 else ceil(units - 1e-9) - 1
        target * range.step
    }
    return roundSamplingValue(param, next.coerceIn(range.min, range.max))
}

fun canStepSamplingValue(param: SamplingParam, current: Double?, direction: Int): Boolean {
    val range = samplingParamRange(param)
    return current == null || if (direction > 0) current < range.max else current > range.min
}

fun roundSamplingValue(param: SamplingParam, value: Double): Double =
    BigDecimal.valueOf(value).setScale(samplingParamRange(param).decimals, RoundingMode.HALF_UP).toDouble()

/** 输入框里的写法：小数参数固定两位，整数参数不带分隔符。 */
fun formatSamplingInput(param: SamplingParam, value: Double): String =
    BigDecimal.valueOf(value).setScale(samplingParamRange(param).decimals, RoundingMode.HALF_UP).toPlainString()

/** 行尾与快捷值的写法：最大输出的整 K 值写作“8K”。 */
fun formatSamplingValue(param: SamplingParam, value: Double): String {
    if (param == SamplingParam.MAX_TOKENS && value >= 1024 && value % 1024 == 0.0) return "${(value / 1024).toLong()}K"
    return formatSamplingInput(param, value)
}

/** 解析输入；格式不对或超出范围时返回 null。 */
fun parseSamplingInput(param: SamplingParam, text: String): Double? {
    val trimmed = text.trim()
    if (!trimmed.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
    val range = samplingParamRange(param)
    val value = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() && range.contains(it) } ?: return null
    if (range.decimals == 0 && value % 1.0 != 0.0) return null
    return roundSamplingValue(param, value)
}
