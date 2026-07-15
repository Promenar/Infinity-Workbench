package com.promenar.nexara.domain.generation

/**
 * 由生成 runner 抛出的结构化失败信号。
 *
 * 用于在保持 [kotlinx.coroutines.CancellationException] 语义的前提下，把已分类的
 * [GenerationFailure]（含 code、类型化参数）一路透传给 coordinator，避免 coordinator
 * 只能拿到 [Throwable.message] 再二次猜测。
 *
 * 该异常本身不承载自然语言展示文案；展示由 coordinator / 上层依据 [failure] 映射。
 */
class GenerationFailedException(
    val failure: GenerationFailure,
) : RuntimeException("Generation failed: ${failure.code.name}", failure.cause) {
    override fun toString(): String = "GenerationFailedException(code=${failure.code})"
}
