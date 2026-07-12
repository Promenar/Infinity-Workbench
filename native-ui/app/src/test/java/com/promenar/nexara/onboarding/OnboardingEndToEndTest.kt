package com.promenar.nexara.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingEndToEndTest {
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
    fun 首次引导_重建后恢复并且只有成功首聊才完成() {
        var store = OnboardingStateStore(context)
        assertTrue(store.selectLanguage("zh"))
        assertTrue(store.recordProviderSaved("default"))

        // 模拟 Activity 旋转或进程重建：必须恢复到尚未完成的连接检查。
        store = OnboardingStateStore(context)
        assertEquals(OnboardingStep.CONNECTION, store.state.value.step)
        assertEquals("default", store.state.value.providerId)

        assertFalse(store.recordConnectionResult("default", succeeded = false))
        assertEquals(OnboardingStep.CONNECTION, store.state.value.step)
        assertTrue(store.recordConnectionResult("default", succeeded = true))
        assertFalse(store.selectModel(null))
        assertEquals(OnboardingStep.MODEL, store.state.value.step)
        assertTrue(store.selectModel("default::model"))
        assertTrue(store.recordAgentCreated("agent-1", "session-1"))

        assertFalse(store.recordFirstChatResult("session-1", assistantSucceeded = false))
        assertEquals(OnboardingStep.FIRST_CHAT, store.state.value.step)
        assertTrue(store.recordFirstChatResult("session-1", assistantSucceeded = true))
        assertEquals(OnboardingStep.COMPLETED, OnboardingStateStore(context).state.value.step)
    }
}
