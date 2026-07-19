package com.promenar.nexara.ui.welcome

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.SupportState
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OnboardingModelPolicyTest {
    @Test
    fun `unknown 只进入同 provider 候选而不直接进入最终可用`() {
        val models = listOf(
            model("unknown", type = "unknown"),
            model("other-unknown", type = "unknown", providerId = "other-provider"),
        )

        assertThat(onboardingModelCandidates(models, "provider").map { it.id })
            .containsExactly("unknown")
        assertThat(eligibleOnboardingModels(models, "provider")).isEmpty()
    }

    @Test
    fun `candidate 验证成功后才进入最终可用`() = runTest {
        val source = model("vendor-unknown", type = "unknown")

        assertThat(onboardingModelCandidates(listOf(source), "provider")).containsExactly(source)
        assertThat(eligibleOnboardingModels(listOf(source), "provider")).isEmpty()

        val verified = verifyOnboardingModelCandidate(source) { true }

        assertThat(eligibleOnboardingModels(listOf(verified!!), "provider"))
            .containsExactly(verified)
    }

    @Test
    fun `非文本类型没有明确支持时不进入候选或最终可用`() {
        val models = listOf(
            model("embedding", type = "embedding", capabilities = listOf("chat")),
            model("rerank", type = "rerank", capabilities = listOf("chat")),
            model("audio", type = "audio", capabilities = listOf("chat")),
            model("image", type = "image", capabilities = listOf("chat")),
            model("video", type = "video", capabilities = listOf("chat")),
        )

        assertThat(onboardingModelCandidates(models, "provider")).isEmpty()
        assertThat(eligibleOnboardingModels(models, "provider")).isEmpty()
    }

    @Test
    fun `chat reasoning 与显式支持可进入候选和最终可用`() {
        val models = listOf(
            model("chat", type = "CHAT"),
            model("reasoning", type = "reasoning"),
            model("supported", type = "image", chatEndpointCompatible = SupportState.SUPPORTED),
        )

        assertThat(onboardingModelCandidates(models, "provider")).containsExactlyElementsIn(models).inOrder()
        assertThat(eligibleOnboardingModels(models, "provider")).containsExactlyElementsIn(models).inOrder()
    }
}

private fun model(
    id: String,
    type: String,
    providerId: String = "provider",
    capabilities: List<String> = emptyList(),
    chatEndpointCompatible: SupportState = SupportState.UNKNOWN,
) = ModelInfo(
    name = id,
    id = id,
    remoteModelId = id,
    description = "",
    enabled = false,
    type = type,
    capabilities = capabilities,
    providerId = providerId,
    chatEndpointCompatible = chatEndpointCompatible,
)
