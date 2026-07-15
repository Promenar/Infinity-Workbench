package com.promenar.nexara.domain.generation

/**
 * 结构化失败与持久化 String 列之间的稳定编解码。
 *
 * 背景：当前 Room schema 中消息错误仍为单一 `errorMessage: String?` 列，本批次不迁移 schema。
 * 为满足“禁止把 Throwable.message 或协议原文写入历史 errorMessage”，同时保证上层可回映射，
 * 这里定义一个稳定、机器可读、**不含自然语言**的信封格式：
 *
 *   `@nexara/failure:CODE?k1=v1&k2=v2`
 *
 * - 仅写入 [GenerationFailure.code] 与 [GenerationFailure.formatArgs]；
 * - 严禁写入 [GenerationFailure.technical] / [GenerationFailure.cause]（仅诊断，不入库展示列）；
 * - 下一批次的 UI mapper 识别该前缀后映射资源，未识别或未知串一律回落到通用安全文案。
 */
object GenerationFailureCodec {

    const val ENVELOPE_PREFIX = "@nexara/failure:"

    private const val MAX_ENVELOPE_LENGTH = 128
    private val codeByName = GenerationFailureCode.entries.associateBy { it.name }

    /**
     * 是否为结构化失败信封。UI mapper 用它在“信封 → 资源”与“未知串 → 通用安全文案”之间分流。
     */
    fun isFailureEnvelope(raw: String?): Boolean {
        return decode(raw) != null
    }

    /**
     * 编码为持久化字符串。technical / cause 永不入此串。
     */
    fun encode(failure: GenerationFailure): String {
        val builder = StringBuilder(ENVELOPE_PREFIX.length + 16)
        builder.append(ENVELOPE_PREFIX)
        builder.append(failure.code.name)
        val allowedArgs = allowedArgs(failure)
        if (allowedArgs.isNotEmpty()) {
            builder.append('?')
            builder.append(
                allowedArgs.toSortedMap().entries.joinToString(separator = "&") { (key, value) ->
                    "$key=$value"
                },
            )
        }
        return builder.toString()
    }

    /**
     * 反解为结构化失败；无法识别时返回 null（调用方应回落到 [GenerationFailure.unknown]）。
     * 反解结果只含 code + formatArgs，绝不会凭空产生 technical / cause。
     */
    fun decode(raw: String?): GenerationFailure? {
        if (raw.isNullOrEmpty() || raw.length > MAX_ENVELOPE_LENGTH) return null
        if (!raw.startsWith(ENVELOPE_PREFIX)) return null
        val withoutPrefix = raw.substring(ENVELOPE_PREFIX.length)
        val questionIdx = withoutPrefix.indexOf('?')
        val codeName = if (questionIdx < 0) withoutPrefix else withoutPrefix.substring(0, questionIdx)
        val code = codeByName[codeName] ?: return null
        val decoded = if (questionIdx < 0) {
            GenerationFailure(code = code)
        } else {
            val query = withoutPrefix.substring(questionIdx + 1)
            if (query.isEmpty() || query.contains('&') || query.count { it == '=' } != 1) return null
            val (key, value) = query.split('=', limit = 2)
            if (key != GenerationFailure.KEY_RETRY_AFTER_SECONDS) return null
            if (code != GenerationFailureCode.RATE_LIMIT) return null
            if (value.isEmpty() || value.any { !it.isDigit() }) return null
            val seconds = value.toIntOrNull()?.takeIf { it > 0 } ?: return null
            if (seconds.toString() != value) return null
            GenerationFailure.rateLimited(seconds)
        }
        return decoded.takeIf { encode(it) == raw }
    }

    private fun allowedArgs(failure: GenerationFailure): Map<String, String> {
        if (failure.code != GenerationFailureCode.RATE_LIMIT) return emptyMap()
        val seconds = failure.retryAfterSeconds?.takeIf { it > 0 } ?: return emptyMap()
        return mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to seconds.toString())
    }
}
