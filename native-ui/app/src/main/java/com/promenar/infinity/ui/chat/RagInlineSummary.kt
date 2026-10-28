package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.PhaseStatus
import com.promenar.infinity.data.model.RagPhase
import com.promenar.infinity.data.model.RagRetrievalTrace

internal enum class RagInlineSource { KNOWLEDGE, WEB }

internal enum class RagInlineStatus { RUNNING, COMPLETED, FAILED }

internal data class RagInlineSummary(
    val source: RagInlineSource,
    val status: RagInlineStatus,
    val resultCount: Int? = null,
    val partialFailure: Boolean = false,
)

/**
 * 知识库摘要按实际引用条数与图谱路径条数相加，网页摘要只统计网页引用。
 * 引用与图谱路径是不同类型的条目，按各自列表长度计数，不按内容去重。
 * 混合结果优先展示知识库数量，网页引用保留在详情页；过程轨迹只补足没有实际知识库条目的成功计数。
 */
internal fun ragInlineSummary(
    phases: List<RagPhase>,
    referenceCount: Int,
    isComplete: Boolean,
    knowledgePathCount: Int = 0,
    citationCount: Int = 0,
    trace: RagRetrievalTrace? = null,
): RagInlineSummary {
    val actualKnowledgeCount = referenceCount.coerceAtLeast(0) + knowledgePathCount.coerceAtLeast(0)
    val actualWebCount = citationCount.coerceAtLeast(0)
    val ready = isComplete || phases.any { it.id == "ready" && it.status == PhaseStatus.DONE }
    val traceHasKnowledgeResults = trace?.outcome == RagRetrievalTrace.OUTCOME_HIT && trace.selectedCount > 0
    val source = if (
        actualKnowledgeCount == 0 && actualWebCount > 0 && !traceHasKnowledgeResults &&
        (ready || trace == null)
    ) {
        RagInlineSource.WEB
    } else {
        RagInlineSource.KNOWLEDGE
    }
    if (!ready) return RagInlineSummary(source, RagInlineStatus.RUNNING)
    // 未配置嵌入模型或没有检索范围时检索未执行，不算失败来源。
    val hasFailedSource = trace != null && !trace.isSkipped &&
        trace.outcome !in setOf(RagRetrievalTrace.OUTCOME_HIT, RagRetrievalTrace.OUTCOME_EMPTY)
    if (hasFailedSource && actualKnowledgeCount == 0 && actualWebCount == 0) {
        return RagInlineSummary(source, RagInlineStatus.FAILED)
    }
    val count = when {
        source == RagInlineSource.WEB -> actualWebCount
        actualKnowledgeCount > 0 -> actualKnowledgeCount
        trace?.outcome == RagRetrievalTrace.OUTCOME_EMPTY -> 0
        else -> trace?.selectedCount ?: 0
    }
    return RagInlineSummary(
        source = source,
        status = RagInlineStatus.COMPLETED,
        resultCount = count.coerceAtLeast(0),
        partialFailure = hasFailedSource,
    )
}
