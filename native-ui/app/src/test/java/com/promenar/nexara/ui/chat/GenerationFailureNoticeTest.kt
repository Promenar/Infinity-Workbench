package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationFailureCodec
import com.promenar.nexara.ui.common.status.UiStatusNotice
import java.io.File
import org.junit.jupiter.api.Test

class GenerationFailureNoticeTest {

    @Test
    fun `十个稳定失败码只映射对应资源`() {
        val expected = mapOf(
            GenerationFailureCode.NETWORK to R.string.generation_failure_network,
            GenerationFailureCode.AUTH to R.string.generation_failure_auth,
            GenerationFailureCode.RATE_LIMIT to R.string.generation_failure_rate_limit,
            GenerationFailureCode.QUOTA to R.string.generation_failure_quota,
            GenerationFailureCode.TIMEOUT to R.string.generation_failure_timeout,
            GenerationFailureCode.INVALID_REQUEST to R.string.generation_failure_invalid_request,
            GenerationFailureCode.SERVER to R.string.generation_failure_server,
            GenerationFailureCode.BUSY to R.string.generation_failure_busy,
            GenerationFailureCode.PERSISTENCE to R.string.generation_failure_persistence,
            GenerationFailureCode.UNKNOWN to R.string.generation_failure_unknown,
        )

        val actual = GenerationFailureCode.entries.associateWith { code ->
            GenerationFailureNotice.template(
                GenerationFailureNotice.from(GenerationFailure(code)),
            ).resourceId
        }

        assertThat(actual).containsExactlyEntriesIn(expected)
    }

    @Test
    fun `限流只在正数重试秒数存在时使用带参数资源`() {
        val withRetry = GenerationFailureNotice.template(
            GenerationFailureNotice.from(GenerationFailure.rateLimited(37)),
        )
        val withoutRetry = GenerationFailureNotice.template(
            GenerationFailureNotice.from(GenerationFailure.rateLimited(null)),
        )
        val invalidRetry = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    GenerationFailureCode.RATE_LIMIT,
                    mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to "-1"),
                ),
            ),
        )

        assertThat(withRetry.resourceId).isEqualTo(R.string.generation_failure_rate_limit_retry)
        assertThat(withRetry.args).containsExactly(37)
        assertThat(withoutRetry.resourceId).isEqualTo(R.string.generation_failure_rate_limit)
        assertThat(withoutRetry.args).isEmpty()
        assertThat(invalidRetry.resourceId).isEqualTo(R.string.generation_failure_rate_limit)
        assertThat(invalidRetry.args).isEmpty()
    }

    @Test
    fun `诊断信息异常原因和任意裸字符串都不会进入展示参数`() {
        val secretTechnical = "provider=internal session=secret-session"
        val secretCause = IllegalStateException("raw upstream exception")
        val resolved = GenerationFailureNotice.template(
            GenerationFailureNotice.from(
                GenerationFailure(
                    code = GenerationFailureCode.SERVER,
                    formatArgs = mapOf("unexpected" to "do-not-display"),
                    technical = secretTechnical,
                    cause = secretCause,
                ),
            ),
        )
        val rawFallback = GenerationFailureNotice.template(
            GenerationFailureNotice.fromPersisted("裸露的旧错误 provider-internal"),
        )

        assertThat(resolved.resourceId).isEqualTo(R.string.generation_failure_server)
        assertThat(resolved.args).isEmpty()
        assertThat(resolved.args.joinToString()).doesNotContain(secretTechnical)
        assertThat(resolved.args.joinToString()).doesNotContain(secretCause.message!!)
        assertThat(rawFallback.resourceId).isEqualTo(R.string.generation_failure_unknown)
        assertThat(rawFallback.args).isEmpty()
    }

    @Test
    fun `持久化信封可恢复而非信封损坏信封和fixture稳定码安全回落`() {
        val envelope = GenerationFailureCodec.encode(GenerationFailure.rateLimited(12))

        assertThat(
            GenerationFailureNotice.template(
                GenerationFailureNotice.fromPersisted(envelope),
            ),
        ).isEqualTo(
            com.promenar.nexara.ui.common.status.ResolvedStatus(
                R.string.generation_failure_rate_limit_retry,
                listOf(12),
            ),
        )

        listOf(
            null,
            "",
            "NETWORK",
            "@nexara/failure:NOT_A_REAL_CODE",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=%ZZ",
            "@nexara/failure:RATE_LIMIT?",
            "@nexara/failure:RATE_LIMIT?technical=provider-private",
            "@nexara/failure:RATE_LIMIT?retryAfterSeconds=1&retryAfterSeconds=2",
        ).forEach { raw ->
            val resolved = GenerationFailureNotice.template(
                GenerationFailureNotice.fromPersisted(raw),
            )
            assertThat(resolved.resourceId).isEqualTo(R.string.generation_failure_unknown)
            assertThat(resolved.args).isEmpty()
        }
    }

    @Test
    fun `未知notice code由安全通用资源兜底`() {
        val resolved = GenerationFailureNotice.template(
            UiStatusNotice(
                severity = com.promenar.nexara.ui.common.status.NoticeSeverity.Error,
                code = "generation.failure.future_code",
                formatArgs = listOf("internal-code"),
                technical = "internal-technical",
            ),
        )

        assertThat(resolved.resourceId).isEqualTo(R.string.generation_failure_unknown)
        assertThat(resolved.args).isEmpty()
    }

    @Test
    fun `中英文生成失败资源key完全一致`() {
        val english = resourceFile("values/strings.xml").readText()
        val chinese = resourceFile("values-zh-rCN/strings.xml").readText()
        val generationKey = Regex("<string name=\"(generation_failure_[^\"]+)\"")
        val englishKeys = generationKey.findAll(english).map { it.groupValues[1] }.toSet()
        val chineseKeys = generationKey.findAll(chinese).map { it.groupValues[1] }.toSet()

        assertThat(englishKeys).containsExactlyElementsIn(REQUIRED_RESOURCE_KEYS)
        assertThat(chineseKeys).containsExactlyElementsIn(englishKeys)
    }

    private fun resourceFile(relativePath: String): File {
        val root = File(System.getProperty("user.dir") ?: ".")
        val appRoot = if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        return appRoot.resolve("src/main/res/$relativePath")
    }

    private companion object {
        val REQUIRED_RESOURCE_KEYS = setOf(
            "generation_failure_network",
            "generation_failure_auth",
            "generation_failure_rate_limit",
            "generation_failure_rate_limit_retry",
            "generation_failure_quota",
            "generation_failure_timeout",
            "generation_failure_invalid_request",
            "generation_failure_server",
            "generation_failure_busy",
            "generation_failure_persistence",
            "generation_failure_unknown",
        )
    }
}
