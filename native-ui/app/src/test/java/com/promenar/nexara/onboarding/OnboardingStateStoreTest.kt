package com.promenar.nexara.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingStateStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `固定步骤只能在前置成功后推进且失败不越级`() {
        val store = OnboardingStateStore(context)

        assertThat(store.state.value.step).isEqualTo(OnboardingStep.LANGUAGE)
        assertThat(store.recordProviderSaved("provider")).isFalse()
        assertThat(store.selectLanguage("zh")).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        assertThat(store.recordConnectionResult("provider", succeeded = true)).isFalse()
        assertThat(store.recordProviderSaved("provider")).isTrue()
        assertThat(store.recordConnectionResult("provider", succeeded = false)).isFalse()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.CONNECTION)
        assertThat(store.recordConnectionResult("other", succeeded = true)).isFalse()
        assertThat(store.recordConnectionResult("provider", succeeded = true)).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.MODEL)
    }

    @Test
    fun `无模型时保持阻断且仅有效模型可推进到Agent`() {
        val store = OnboardingStateStore(context)
        assertThat(store.selectLanguage("en")).isTrue()
        assertThat(store.recordProviderSaved("provider")).isTrue()
        assertThat(store.recordConnectionResult("provider", succeeded = true)).isTrue()
        assertThat(store.selectModel(null)).isFalse()
        assertThat(store.selectModel("  ")).isFalse()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.MODEL)
        assertThat(store.selectModel("provider::model")).isTrue()
        assertThat(store.state.value.modelId).isEqualTo("provider::model")
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.AGENT)
    }

    @Test
    fun `首聊只有目标会话最终成功态才完成`() {
        val store = OnboardingStateStore(context)
        assertThat(store.selectLanguage("en")).isTrue()
        assertThat(store.recordProviderSaved("provider")).isTrue()
        assertThat(store.recordConnectionResult("provider", succeeded = true)).isTrue()
        assertThat(store.selectModel("provider::model")).isTrue()
        assertThat(store.recordAgentCreated("agent", "session")).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.FIRST_CHAT)

        assertThat(store.recordFirstChatResult("other", assistantSucceeded = true)).isFalse()
        assertThat(store.recordFirstChatResult("session", assistantSucceeded = false)).isFalse()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.FIRST_CHAT)
        assertThat(store.recordFirstChatResult("session", assistantSucceeded = true)).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.COMPLETED)
        assertThat(context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
            .getBoolean("has_shown_welcome", false)).isTrue()
    }

    @Test
    fun `模型缺失时不能创建Agent且可返回连接重试`() {
        val store = OnboardingStateStore(context)
        assertThat(store.selectLanguage("zh")).isTrue()
        assertThat(store.recordProviderSaved("provider")).isTrue()
        assertThat(store.recordConnectionResult("provider", succeeded = true)).isTrue()
        assertThat(store.recordAgentCreated("agent", "session")).isFalse()
        assertThat(store.returnToConnection()).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.CONNECTION)
    }

    @Test
    fun `旋转或进程重建从最后成功检查点恢复`() {
        val first = OnboardingStateStore(context)
        assertThat(first.selectLanguage("zh")).isTrue()
        assertThat(first.recordProviderSaved("provider-id")).isTrue()

        val restored = OnboardingStateStore(context)

        assertThat(restored.state.value).isEqualTo(
            OnboardingState(
                step = OnboardingStep.CONNECTION,
                languageCode = "zh",
                providerId = "provider-id",
            )
        )
    }

    @Test
    fun `历史已完成欢迎页迁移为完成态`() {
        context.getSharedPreferences("nexara_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("has_shown_welcome", true).commit()

        assertThat(OnboardingStateStore(context).state.value.step)
            .isEqualTo(OnboardingStep.COMPLETED)
    }
}
