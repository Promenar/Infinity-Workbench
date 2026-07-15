package com.promenar.nexara.domain.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.generation.GenerationPresentationState
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import com.promenar.nexara.data.remote.UnifiedProviderConfig
import com.promenar.nexara.data.remote.parser.NormalizedError
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.protocol.StreamChunk
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.UiStatusNotice
import org.junit.Test

class GenerationFailureContractTest {
    @Test
    fun `codec 仅接受规范信封并拒绝损坏或扩展字段`() {
        val malformed = listOf(
            null,
            "",
            "@nexara/failure:NOT_A_CODE",
            "@nexara/failure:UNKNOWN?",
            "@nexara/failure:UNKNOWN?retryAfterSeconds",
            "@nexara/failure:UNKNOWN?=1",
            "@nexara/failure:UNKNOWN?retryAfterSeconds=",
            "@nexara/failure:UNKNOWN?retryAfterSeconds=1&retryAfterSeconds=2",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=%31",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=1%",
            "@nexara/failure:RATE_LIMIT?technical=provider-secret",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=01",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=-1",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=2147483648",
            "@nexara/failure:SERVER?retryAfterSeconds=5",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=5&extra=1",
            "@nexara/failure:RATE_LIMIT?" + "retryAfterSeconds=1&".repeat(40) + "x=1",
        )

        malformed.forEach { raw ->
            assertThat(GenerationFailureCodec.decode(raw)).isNull()
            assertThat(GenerationFailureCodec.isFailureEnvelope(raw)).isFalse()
        }
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
            .isEqualTo("@nexara/failure:RATE_LIMIT?retryAfterSeconds=12")
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
