package com.promenar.nexara.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingLanguageSelectionCoordinatorTest {
    private lateinit var context: Context

    @Before
    fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `reentrant second selection cannot overwrite first locale`() {
        val persisted = mutableListOf("zh")
        lateinit var coordinator: OnboardingLanguageSelectionCoordinator
        coordinator = OnboardingLanguageSelectionCoordinator(
            stateStore = OnboardingStateStore(context),
            currentLanguage = { persisted.last() },
            persistLanguage = { language -> persisted += language; true },
            applyLanguage = { assertThat(coordinator.select("zh")).isFalse() },
        )

        assertThat(coordinator.select("en")).isTrue()
        assertThat(persisted.last()).isEqualTo("en")
    }

    @Test
    fun `checkpoint rejection rolls locale back to previous value`() {
        val store = OnboardingStateStore(context)
        assertThat(store.selectLanguage("en")).isTrue()
        var persisted = "en"
        val coordinator = OnboardingLanguageSelectionCoordinator(
            stateStore = store,
            currentLanguage = { persisted },
            persistLanguage = { language -> persisted = language; true },
            applyLanguage = { error("checkpoint失败时不得应用语言") },
        )

        assertThat(coordinator.select("zh")).isFalse()
        assertThat(persisted).isEqualTo("en")
        assertThat(store.state.value.languageCode).isEqualTo("en")
    }
}
