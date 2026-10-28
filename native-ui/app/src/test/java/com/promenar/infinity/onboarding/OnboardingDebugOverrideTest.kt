package com.promenar.infinity.onboarding

import com.promenar.infinity.allowOnboardingEmptyModelsOverride
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDebugOverrideTest {
    @Test
    fun release构建始终拒绝Intent测试覆盖() {
        assertFalse(allowOnboardingEmptyModelsOverride(false, true))
    }

    @Test
    fun debug构建仅接受显式Intent测试覆盖() {
        assertFalse(allowOnboardingEmptyModelsOverride(true, false))
        assertTrue(allowOnboardingEmptyModelsOverride(true, true))
    }
}
