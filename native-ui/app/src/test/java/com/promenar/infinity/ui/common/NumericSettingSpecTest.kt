package com.promenar.infinity.ui.common

import org.junit.Assert.*
import org.junit.Test

class NumericSettingSpecTest {
    private val tokens = NumericSettingSpec(
        min = 0.0, max = 10_000_000.0, step = 10_000.0,
        presets = listOf(NumericSettingSpec.Preset(0.0), NumericSettingSpec.Preset(100_000.0)),
        defaultValue = 0.0, unlimitedValue = 0.0,
    )

    @Test fun parsesAndAlignsTokenBudget() {
        assertEquals(120_000.0, tokens.parseCustom(" 123456 ").getOrThrow(), 0.0)
        assertEquals(130_000.0, tokens.parseCustom("125000").getOrThrow(), 0.0)
    }

    @Test fun invalidOrOutOfRangeInputIsNeverSilentlySubmitted() {
        listOf("", "x", "NaN", "Infinity", "1e6", "1,000", "-1", "10000001").forEach {
            assertTrue(it, tokens.parseCustom(it).isFailure)
        }
    }

    @Test fun clampHonoursBoundsAndStep() {
        assertEquals(0.0, tokens.clamp(-100.0), 0.0)
        assertEquals(10_000_000.0, tokens.clamp(20_000_000.0), 0.0)
        assertEquals(100_000.0, tokens.clamp(104_999.0), 0.0)
    }

    @Test fun unlimitedPresetDoesNotWidenCustomRange() {
        val turns = NumericSettingSpec(1.0, 100.0, presets = listOf(NumericSettingSpec.Preset(0.0)),
            defaultValue = 50.0, unlimitedValue = 0.0)
        assertTrue(turns.parseCustom("0").isFailure)
        assertEquals(0.0, turns.clamp(0.0), 0.0)
        assertTrue(turns.isUnlimited(0.0))
        assertFalse(turns.isUnlimited(1.0))
    }

    @Test fun decimalsHaveStablePrecisionAndLocaleIndependentParsing() {
        val spec = NumericSettingSpec(0.0, 1.0, step = 0.05, presets = emptyList(), defaultValue = 0.95,
            kind = NumericSettingKind.Continuous, decimalPlaces = 2)
        assertEquals(0.35, spec.parseCustom("0.333333333").getOrThrow(), 1e-10)
        assertEquals("0.95", spec.format(0.95000000001))
        assertEquals("1.00", spec.format(1.0))
        assertTrue(spec.parseCustom("0,95").isFailure)
    }

    @Test fun selectedPresetHandlesFloatStorageNoiseAndIncludesDefault() {
        val spec = NumericSettingSpec(0.8, 1.4, step = 0.1,
            presets = listOf(NumericSettingSpec.Preset(0.8)), defaultValue = 1.0, decimalPlaces = 1)
        assertEquals(0.8, spec.selectedPreset(0.8f.toDouble())?.value ?: -1.0, 0.0)
        assertEquals(1.0, spec.selectedPreset(1.0)?.value ?: -1.0, 0.0)
        assertNull(spec.selectedPreset(1.1))
        assertEquals("1.0", spec.format(spec.clamp(0.999999999)))
    }

    @Test fun customIntegerUsesIntegralGrid() {
        val spec = NumericSettingSpec(1.0, 20.0, presets = emptyList(), defaultValue = 5.0)
        assertEquals(6.0, spec.parseCustom("5.5").getOrThrow(), 0.0)
        assertEquals("5", spec.format(5.0))
    }

    @Test fun continuousValuesRetainStoragePrecision() {
        val spec = NumericSettingSpec(0.0, 1.0, step = 0.0, presets = emptyList(), defaultValue = 0.5,
            kind = NumericSettingKind.Continuous, decimalPlaces = 2)
        assertEquals(0.123456789, spec.clamp(0.123456789), 0.0)
        assertEquals(0.123456789, spec.parseCustom("0.123456789").getOrThrow(), 0.0)
        assertEquals("0.12", spec.format(0.123456789))
    }
}
