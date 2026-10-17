package com.promenar.nexara.ui.common

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

enum class NumericSettingKind { Discrete, Continuous }

/** 数值控件的取值合同；显示精度不参与存储值的量化。 */
data class NumericSettingSpec(
    val min: Double,
    val max: Double,
    val step: Double = 1.0,
    val presets: List<Preset>,
    val defaultValue: Double,
    val unit: String = "",
    val unlimitedValue: Double? = null,
    val kind: NumericSettingKind = NumericSettingKind.Discrete,
    val decimalPlaces: Int = 0,
) {
    data class Preset(val value: Double, val labelRes: Int? = null)

    init {
        require(min.isFinite() && max.isFinite() && min <= max)
        require(step.isFinite() && (step > 0 || step == 0.0 && kind == NumericSettingKind.Continuous))
        require(decimalPlaces in 0..8)
        require(defaultValue.isFinite() && (defaultValue in min..max || isUnlimited(defaultValue)))
        require(unlimitedValue == null || unlimitedValue.isFinite())
        require(presets.all { it.value.isFinite() && (it.value in min..max || isUnlimited(it.value)) })
    }

    val effectivePresets: List<Preset>
        get() = if (presets.any { matches(it.value, defaultValue) }) presets else presets + Preset(defaultValue)

    fun parseCustom(input: String): Result<Double> = runCatching {
        val text = input.trim()
        require(text.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")))
        val value = text.toDouble()
        require(value.isFinite() && value in min..max)
        clamp(value)
    }

    fun clamp(value: Double): Double {
        if (isUnlimited(value)) return requireNotNull(unlimitedValue)
        if (!value.isFinite()) return defaultValue
        val bounded = value.coerceIn(min, max)
        if (step == 0.0) return bounded
        val origin = BigDecimal.valueOf(min)
        val interval = BigDecimal.valueOf(step)
        return BigDecimal.valueOf(bounded).subtract(origin)
            .divide(interval, 0, RoundingMode.HALF_UP).multiply(interval).add(origin)
            .toDouble().coerceIn(min, max)
    }

    fun format(value: Double): String {
        val number = BigDecimal.valueOf(value).setScale(decimalPlaces, RoundingMode.HALF_UP).toPlainString()
        return when (unit) {
            "" -> number
            "%", "×" -> number + unit
            else -> "$number $unit"
        }
    }

    fun selectedPreset(value: Double): Preset? = effectivePresets.firstOrNull { matches(it.value, value) }
    fun isUnlimited(value: Double): Boolean = unlimitedValue?.let { matches(it, value) } ?: false
    fun isDefault(value: Double): Boolean = matches(defaultValue, value)

    private fun matches(first: Double, second: Double): Boolean = abs(first - second) <= 1e-6
}
