package com.promenar.nexara.ui.welcome

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.settings.ModelInfo
import org.junit.Test
import kotlinx.coroutines.test.runTest

class OnboardingModelPolicyTest {
    @Test
    fun `reasoning model with chat capability remains selectable`() {
        val models = listOf(
            model("reasoning", type = "reasoning", capabilities = listOf("chat", "reasoning")),
            model("legacy-reasoning", type = "reasoning"),
        )

        assertThat(eligibleOnboardingModels(models, "provider").map { it.id })
            .containsExactly("reasoning", "legacy-reasoning")
            .inOrder()
    }

    @Test
    fun `embedding rerank and other provider models are excluded`() {
        val models = listOf(
            model("chat", type = "chat"),
            model("embedding", type = "embedding", capabilities = listOf("embedding")),
            model("rerank", type = "rerank", capabilities = listOf("rerank")),
            model("audio", type = "audio", capabilities = listOf("chat")),
            model("image", type = "image", capabilities = listOf("chat")),
            model("other", type = "chat", providerId = "other-provider"),
        )

        assertThat(eligibleOnboardingModels(models, "provider").map { it.id })
            .containsExactly("chat")
    }

    @Test
    fun `未知 MiniMax 模型探测成功后升级为 chat 并可推进`() = runTest {
        val candidate = model("MiniMax-M3", type = "unknown")

        assertThat(eligibleOnboardingModels(listOf(candidate), "provider")).containsExactly(candidate)
        val verified = verifyOnboardingModelCandidate(candidate) { remoteId -> remoteId == "MiniMax-M3" }

        assertThat(verified?.type).isEqualTo("chat")
        assertThat(verified?.capabilities).contains("chat")
    }

    @Test
    fun `未知非 chat 模型探测失败时不得升级或推进`() = runTest {
        val candidate = model("vendor-unknown", type = "unknown")

        assertThat(verifyOnboardingModelCandidate(candidate) { false }).isNull()
    }

    private fun model(
        id: String,
        type: String,
        capabilities: List<String> = emptyList(),
        providerId: String = "provider",
    ) = ModelInfo(
        name = id,
        id = id,
        description = "",
        enabled = false,
        type = type,
        capabilities = capabilities,
        providerId = providerId,
    )
}
