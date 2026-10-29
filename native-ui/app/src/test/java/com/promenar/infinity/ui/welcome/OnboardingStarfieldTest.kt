package com.promenar.infinity.ui.welcome

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnboardingStarfieldTest {
    @Test
    fun `星空约 140 颗，三档亮度都有且暗星最多，同一种子结果相同`() {
        val stars = onboardingStars()

        assertThat(stars).hasSize(OnboardingStarCount)
        assertThat(OnboardingStarCount).isEqualTo(140)
        val byTier = stars.groupingBy { it.tier }.eachCount()
        assertThat(byTier.keys).containsExactly(0, 1, 2)
        assertThat(byTier.getValue(0)).isGreaterThan(byTier.getValue(1))
        assertThat(byTier.getValue(1)).isGreaterThan(byTier.getValue(2))
        assertThat(stars.all { it.x in 0f..1f && it.y in 0f..1f }).isTrue()
        assertThat(onboardingStars().map { it.x to it.y }).isEqualTo(stars.map { it.x to it.y })
    }

    @Test
    fun `漂移极慢、亮星稍快，出右边后从左边回来`() {
        val dim = OnboardingStar(x = 0.5f, y = 0.5f, tier = 0, phase = 0f, speed = 1f, warm = false)
        val bright = OnboardingStar(x = 0.5f, y = 0.5f, tier = 2, phase = 0f, speed = 1f, warm = false)

        assertThat(onboardingStarX(dim, 0f)).isEqualTo(0.5f)
        // 一分钟只移动屏宽的十分之一左右。
        assertThat(onboardingStarX(dim, 60f) - 0.5f).isWithin(1e-4f).of(0.12f)
        assertThat(onboardingStarX(bright, 60f) - 0.5f).isGreaterThan(onboardingStarX(dim, 60f) - 0.5f)
        val wrapped = onboardingStarX(OnboardingStar(0.99f, 0f, 0, 0f, 1f, false), 10f)
        assertThat(wrapped).isAtLeast(0f)
        assertThat(wrapped).isLessThan(0.02f)
    }
}
