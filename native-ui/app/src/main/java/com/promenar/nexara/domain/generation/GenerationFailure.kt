package com.promenar.nexara.domain.generation

/**
 * 生成失败的稳定内部码。
 *
 * - 不依赖 Android / UI，可被 Domain / Data / UI 任意层引用。
 * - 任何自然语言、底层异常文本、provider 返回原文都禁止穿透为该码之外的展示文案；
 *   上层（UI mapper）只应依据 [code] 与 [GenerationFailure.formatArgs] 映射资源。
 */
enum class GenerationFailureCode {
    NETWORK,
    AUTH,
    RATE_LIMIT,
    QUOTA,
    TIMEOUT,
    INVALID_REQUEST,
    SERVER,
    BUSY,
    PERSISTENCE,
    UNKNOWN,
}

/**
 * 结构化生成失败契约。
 *
 * @param code 稳定内部码，是上层映射资源的唯一依据。
 * @param formatArgs 类型化参数（例如 [KEY_RETRY_AFTER_SECONDS]），供 UI 做占位符替换。
 * @param technical 仅供诊断（日志 / 调试）的技术信息；**禁止**作为展示 message 传播到 UI。
 * @param cause 原始异常，仅诊断用途，禁止直接展示 [Throwable.getMessage]。
 */
data class GenerationFailure(
    val code: GenerationFailureCode,
    val formatArgs: Map<String, String> = emptyMap(),
    val technical: String? = null,
    val cause: Throwable? = null,
) {
    /**
     * 限流类失败建议的重试等待秒数；不存在时为 null。
     * 由 [formatArgs] 中的 [KEY_RETRY_AFTER_SECONDS] 派生，避免在上层再解析任意字符串。
     */
    val retryAfterSeconds: Int?
        get() = formatArgs[KEY_RETRY_AFTER_SECONDS]?.toIntOrNull()

    override fun toString(): String = buildString {
        append("GenerationFailure(code=")
        append(code)
        retryAfterSeconds?.takeIf { code == GenerationFailureCode.RATE_LIMIT && it > 0 }?.let {
            append(", retryAfterSeconds=")
            append(it)
        }
        append(')')
    }

    companion object {
        const val KEY_RETRY_AFTER_SECONDS = "retryAfterSeconds"

        fun of(
            code: GenerationFailureCode,
            formatArgs: Map<String, String> = emptyMap(),
            technical: String? = null,
            cause: Throwable? = null,
        ): GenerationFailure = GenerationFailure(code, formatArgs, technical, cause)

        /**
         * 限流 / 429 类失败。重试元数据通过类型化参数携带，不混入自然语言。
         */
        fun rateLimited(
            retryAfterSeconds: Int?,
            technical: String? = null,
            cause: Throwable? = null,
        ): GenerationFailure = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = buildRetryAfterArgs(retryAfterSeconds),
            technical = technical,
            cause = cause,
        )

        fun persistence(technical: String?, cause: Throwable? = null): GenerationFailure =
            GenerationFailure(GenerationFailureCode.PERSISTENCE, emptyMap(), technical, cause)

        fun busy(technical: String? = null): GenerationFailure =
            GenerationFailure(GenerationFailureCode.BUSY, emptyMap(), technical, null)

        fun unknown(technical: String?, cause: Throwable? = null): GenerationFailure =
            GenerationFailure(GenerationFailureCode.UNKNOWN, emptyMap(), technical, cause)

        internal fun buildRetryAfterArgs(retryAfterSeconds: Int?): Map<String, String> =
            if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                mapOf(KEY_RETRY_AFTER_SECONDS to retryAfterSeconds.toString())
            } else {
                emptyMap()
            }
    }
}
