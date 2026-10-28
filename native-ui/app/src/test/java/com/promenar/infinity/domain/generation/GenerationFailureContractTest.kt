package com.promenar.infinity.domain.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.GenerationPresentationState
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ProviderResolutionError
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.parser.NormalizedError
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import org.junit.Test

class GenerationFailureContractTest {
    @Test
    fun `codec 仅接受规范信封并拒绝损坏或扩展字段`() {
        val malformed = listOf(
            null,
            "",
            "@infinity/failure:NOT_A_CODE",
            "@infinity/failure:UNKNOWN?",
            "@infinity/failure:UNKNOWN?retryAfterSeconds",
            "@infinity/failure:UNKNOWN?=1",
            "@infinity/failure:UNKNOWN?retryAfterSeconds=",
            "@infinity/failure:UNKNOWN?retryAfterSeconds=1&retryAfterSeconds=2",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=%31",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=1%",
            "@infinity/failure:RATE_LIMIT?technical=provider-secret",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=01",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=-1",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=2147483648",
            "@infinity/failure:SERVER?retryAfterSeconds=5",
            "@infinity/failure:RATE_LIMIT?retryAfterSeconds=5&extra=1",
            "@infinity/failure:RATE_LIMIT?" + "retryAfterSeconds=1&".repeat(40) + "x=1",
            "@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=",
            "@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=needs_login",
            "@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=SECRET",
            "@infinity/failure:TAILNET_UNAVAILABLE?retryAfterSeconds=5",
            "@infinity/failure:NETWORK?tailnetReason=TIMEOUT",
        )

        malformed.forEach { raw ->
            assertThat(GenerationFailureCodec.decode(raw)).isNull()
            assertThat(GenerationFailureCodec.isFailureEnvelope(raw)).isFalse()
        }
    }

    @Test
    fun `tailnet 原因随信封持久化，历史消息可还原登录或重试入口`() {
        GenerationFailure.TAILNET_REASONS.forEach { reason ->
            val failure = GenerationFailure(
                code = GenerationFailureCode.TAILNET_UNAVAILABLE,
                formatArgs = mapOf(GenerationFailure.KEY_TAILNET_REASON to reason, "host" to "gpu.tail1.ts.net"),
                technical = "proxy detail",
            )
            val encoded = GenerationFailureCodec.encode(failure)
            assertThat(encoded).isEqualTo("@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=$reason")
            assertThat(GenerationFailureCodec.decode(encoded)?.formatArgs)
                .containsExactly(GenerationFailure.KEY_TAILNET_REASON, reason)
        }
        val unknownReason = GenerationFailure(
            code = GenerationFailureCode.TAILNET_UNAVAILABLE,
            formatArgs = mapOf(GenerationFailure.KEY_TAILNET_REASON to "gpu.tail1.ts.net"),
        )
        assertThat(GenerationFailureCodec.encode(unknownReason)).isEqualTo("@infinity/failure:TAILNET_UNAVAILABLE")
    }

    @Test
    fun `codec 编码只保留稳定码和白名单类型参数且结果确定`() {
        val marker = "provider-secret-sk-example"
        val failure = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = linkedMapOf(
                "z" to marker,
                GenerationFailure.KEY_RETRY_AFTER_SECONDS to "12",
                "a" to marker,
            ),
            technical = marker,
            cause = IllegalStateException(marker),
        )

        val encoded = GenerationFailureCodec.encode(failure)

        assertThat(encoded)
            .isEqualTo("@infinity/failure:RATE_LIMIT?retryAfterSeconds=12")
        assertThat(encoded).doesNotContain(marker)
        assertThat(GenerationFailureCodec.decode(encoded))
            .isEqualTo(GenerationFailure.rateLimited(12))
    }

    @Test
    fun `默认字符串化不泄漏技术原文异常或 provider 标识`() {
        val marker = "provider-secret-sk-example"
        val cause = IllegalStateException(marker)
        val failure = GenerationFailure(
            code = GenerationFailureCode.NETWORK,
            formatArgs = mapOf("unexpected" to marker),
            technical = marker,
            cause = cause,
        )
        val carriers = listOf(
            failure.toString(),
            GenerationError(failure).toString(),
            GenerationFailedException(failure).message.orEmpty(),
            GenerationFailedException(failure).toString(),
            GenerationEvent.PersistenceFailed(
                persistenceCause = cause,
                originalCause = cause,
                failure = failure,
            ).toString(),
            NormalizedError.Network(marker).toString(),
            StreamChunk.Error(
                code = GenerationFailureCode.SERVER,
                technical = marker,
                message = marker,
                category = marker,
            ).toString(),
            UiStatusNotice(
                severity = NoticeSeverity.Error,
                code = "generation.failure.unknown",
                technical = marker,
            ).toString(),
            UnifiedProviderConfig(
                protocolType = ProtocolType.Generic_OpenAI_Compat,
                baseUrl = "https://$marker.invalid?token=$marker",
                apiKey = marker,
                defaultModel = marker,
                serviceAccountJson = marker,
                projectId = marker,
                location = marker,
            ).toString(),
            GenerationPresentationState(
                taskId = "task",
                sessionId = "session",
                error = failure,
                providerFailure = ProviderResolution.Failure(
                    reason = ProviderResolutionError.MODEL_NOT_FOUND,
                    modelId = marker,
                    providerId = marker,
                ),
            ).toString(),
        )

        carriers.forEach { text -> assertThat(text).doesNotContain(marker) }
    }
}
