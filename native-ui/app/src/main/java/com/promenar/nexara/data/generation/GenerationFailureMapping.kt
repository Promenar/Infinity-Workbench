package com.promenar.nexara.data.generation

import com.promenar.nexara.data.remote.parser.ErrorNormalizer
import com.promenar.nexara.domain.generation.GenerationFailedException
import com.promenar.nexara.domain.generation.GenerationFailure

/**
 * 从异常中恢复结构化 [GenerationFailure]：
 * - 若是 runner 抛出的 [GenerationFailedException]，直接复用其已分类的 failure（保留 code/类型化参数）；
 * - 否则交由 [ErrorNormalizer] 重新分类，保证任意底层异常最终都落入稳定内部码。
 *
 * 注意：technical / cause 仅诊断；调用方禁止把它们当作展示文案传播。
 */
internal fun Throwable.failureOrUnknown(): GenerationFailure =
    if (this is GenerationFailedException) {
        failure
    } else {
        ErrorNormalizer.normalize(this).toFailure(cause = this)
    }
