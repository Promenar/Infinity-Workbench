package com.promenar.nexara.onboarding

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import org.junit.Test

class OnboardingCompletionPolicyTest {
    @Test
    fun `partial chunk followed by error or cancel never succeeds`() {
        val partial = assistant(content = "partial", status = null)
        val failed = assistant(content = "partial", status = "error", isError = true)
        val cancelled = assistant(content = "partial", status = "cancelled")

        assertThat(isSuccessfulOnboardingAssistant(partial)).isFalse()
        assertThat(isSuccessfulOnboardingAssistant(failed)).isFalse()
        assertThat(isSuccessfulOnboardingAssistant(cancelled)).isFalse()
    }

    @Test
    fun `only nonblank assistant terminal success succeeds`() {
        assertThat(isSuccessfulOnboardingAssistant(assistant("done", "success"))).isTrue()
        assertThat(isSuccessfulOnboardingAssistant(assistant("", "success"))).isFalse()
        assertThat(
            isSuccessfulOnboardingAssistant(
                Message(id = "user", role = MessageRole.USER, content = "done", status = "success")
            )
        ).isFalse()
    }

    private fun assistant(content: String, status: String?, isError: Boolean = false) = Message(
        id = "assistant",
        role = MessageRole.ASSISTANT,
        content = content,
        status = status,
        isError = isError,
    )
}
