package com.promenar.nexara.ui.welcome

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.catalog.SupportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class OnboardingModelProbeTest {
    @Test
    fun `unknown 模型探测成功只记录聊天端点兼容`() = runTest {
        val source = model(type = "unknown", capabilities = emptyList(), remoteModelId = "mini-max")
        var probedRemoteId: String? = null
        var probeCalls = 0
        val verified = verifyOnboardingModelCandidate(source) {
            probeCalls++
            probedRemoteId = it
            true
        }

        assertThat(verified).isEqualTo(
            source.copy(chatEndpointCompatible = SupportState.SUPPORTED)
        )
        assertThat(probeCalls).isEqualTo(1)
        assertThat(probedRemoteId).isEqualTo("mini-max")
    }

    @Test
    fun `chat 与 reasoning 模型无需 probe 直接标记端点兼容`() = runTest {
        val chatModel = model(id = "chat-001", type = "CHAT")
        val reasoningModel = model(id = "reasoning-001", type = "ReAsOnInG")
        var probeCalls = 0

        val chatVerified = verifyOnboardingModelCandidate(chatModel) { probeCalls++; false }
        val reasoningVerified = verifyOnboardingModelCandidate(reasoningModel) { probeCalls++; false }

        assertThat(chatVerified).isNotNull()
        assertThat(chatVerified!!.chatEndpointCompatible).isEqualTo(SupportState.SUPPORTED)
        assertThat(reasoningVerified).isNotNull()
        assertThat(reasoningVerified!!.chatEndpointCompatible).isEqualTo(SupportState.SUPPORTED)
        assertThat(probeCalls).isEqualTo(0)
    }

    @Test
    fun `已有支持端点标记的模型可直接透传`() = runTest {
        val source = model(
            id = "already-supported",
            type = "unknown",
            chatEndpointCompatible = SupportState.SUPPORTED,
            remoteModelId = "already-supported-remote",
        )

        var probeCalls = 0
        val verified = verifyOnboardingModelCandidate(source) { probeCalls++; false }

        assertThat(verified).isNotNull()
        assertThat(verified).isEqualTo(source)
        assertThat(probeCalls).isEqualTo(0)
    }

    @Test
    fun `未知模型探测失败时返回 null`() = runTest {
        val source = model(id = "vendor-unknown", type = "unknown")
        var probeCalls = 0
        val verified = verifyOnboardingModelCandidate(source) {
            probeCalls++
            false
        }

        assertThat(verified).isNull()
        assertThat(probeCalls).isEqualTo(1)
    }

    @Test
    fun `非文本类型没有明确端点支持时不执行 probe`() = runTest {
        var probeCalls = 0

        listOf("embedding", "rerank", "image", "audio", "video").forEach { type ->
            val verified = verifyOnboardingModelCandidate(model(id = type, type = type)) {
                probeCalls++
                true
            }
            assertThat(verified).isNull()
        }

        assertThat(probeCalls).isEqualTo(0)
    }

    @Test
    fun `端点请求成功返回 true`() = runTest {
        assertThat(runOnboardingEndpointProbe { Unit }).isTrue()
    }

    @Test
    fun `端点请求普通异常返回 false`() = runTest {
        assertThat(runOnboardingEndpointProbe { error("request failed") }).isFalse()
    }

    @Test
    fun `端点请求内部超时返回 false`() = runTest {
        val result = runOnboardingEndpointProbe(timeoutMillis = 10) {
            delay(20)
        }

        assertThat(result).isFalse()
    }

    @Test
    fun `端点请求传播父协程取消`() = runTest {
        val cancellation = CancellationException("parent cancelled")

        try {
            runOnboardingEndpointProbe { throw cancellation }
            throw AssertionError("expected CancellationException")
        } catch (actual: CancellationException) {
            assertThat(actual.message).isEqualTo("parent cancelled")
        }
    }

    @Test
    fun `端点请求不吞掉父协程的外层超时`() = runTest {
        var helperReturned = false
        try {
            withTimeout(10) {
                runOnboardingEndpointProbe(timeoutMillis = 1_000) {
                    delay(100)
                }
                helperReturned = true
            }
            throw AssertionError("expected outer TimeoutCancellationException")
        } catch (_: TimeoutCancellationException) {
            Unit
        }
        assertThat(helperReturned).isFalse()
    }

    private fun model(
        id: String = "unknown",
        type: String,
        capabilities: List<String> = emptyList(),
        providerId: String = "provider",
        remoteModelId: String = id,
        chatEndpointCompatible: SupportState = SupportState.UNKNOWN,
    ) = ModelInfo(
        name = id,
        id = id,
        remoteModelId = remoteModelId,
        description = "",
        enabled = false,
        type = type,
        providerId = providerId,
        capabilities = capabilities,
        chatEndpointCompatible = chatEndpointCompatible,
    )
}
