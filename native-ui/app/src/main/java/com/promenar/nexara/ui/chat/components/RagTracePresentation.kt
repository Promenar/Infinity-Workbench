package com.promenar.nexara.ui.chat.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.promenar.nexara.R
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagReferenceKind
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.RagRetrievalTrace
import com.promenar.nexara.ui.common.ScopeResource
import com.promenar.nexara.ui.common.labelRes
import kotlin.math.roundToInt

/**
 * 待本地化的一段文字：资源 ID 加格式参数。[quantity] 非空时 [resId] 是复数资源；
 * 参数本身也可以是 [RagText]（如档位名称），解析时先行本地化。
 */
data class RagText(val resId: Int, val args: List<Any> = emptyList(), val quantity: Int? = null)

private fun plural(@PluralsRes resId: Int, count: Int) = RagText(resId, listOf(count), quantity = count)

/** 检索结果的观感等级：命中、未命中（中性），以及未能检索（需要用户处理）。 */
enum class RagOutcomeTone { HIT, NEUTRAL, ATTENTION }

internal fun RagRetrievalTrace.isReranked(): Boolean =
    rerank == RagRetrievalTrace.RERANK_API || rerank == RagRetrievalTrace.RERANK_LLM

internal fun ragOutcomeTone(trace: RagRetrievalTrace): RagOutcomeTone = when (trace.outcome) {
    RagRetrievalTrace.OUTCOME_HIT -> RagOutcomeTone.HIT
    RagRetrievalTrace.OUTCOME_EMPTY -> RagOutcomeTone.NEUTRAL
    else -> RagOutcomeTone.ATTENTION
}

/** 检索卡上的摘要分段：是否命中、命中几条、重排是否生效。 */
internal fun ragCardSummary(trace: RagRetrievalTrace, referenceCount: Int): List<RagText> = when (trace.outcome) {
    RagRetrievalTrace.OUTCOME_HIT -> buildList {
        val count = referenceCount.takeIf { it > 0 } ?: trace.selectedCount
        add(RagText(R.string.chat_rag_summary_hit, listOf(count)))
        when {
            trace.isReranked() -> add(RagText(R.string.chat_rag_summary_reranked))
            trace.rerank == RagRetrievalTrace.RERANK_FAILED -> add(RagText(R.string.chat_rag_summary_rerank_failed))
        }
    }
    RagRetrievalTrace.OUTCOME_EMPTY -> listOf(RagText(R.string.chat_rag_summary_empty))
    RagRetrievalTrace.OUTCOME_UNAVAILABLE -> listOf(RagText(R.string.chat_rag_summary_unavailable))
    RagRetrievalTrace.OUTCOME_NO_SCOPE -> listOf(RagText(R.string.chat_rag_summary_no_scope))
    else -> listOf(RagText(R.string.chat_rag_summary_failed))
}

/** 详情页顶部的检索过程说明；每个元素是一行，行内为分段。 */
internal fun ragTraceLines(trace: RagRetrievalTrace): List<List<RagText>> = buildList {
    when (trace.outcome) {
        RagRetrievalTrace.OUTCOME_HIT, RagRetrievalTrace.OUTCOME_EMPTY -> {
            add(
                listOf(
                    RagText(R.string.rag_trace_recall, listOf(trace.recallCount)),
                    RagText(R.string.rag_trace_selected, listOf(trace.selectedCount)),
                    RagText(R.string.rag_trace_sources, listOf(trace.memoryCount, trace.documentCount)),
                ),
            )
            add(
                listOf(
                    RagText(
                        if (trace.scoring == RagRetrievalTrace.SCORING_HYBRID) {
                            R.string.rag_trace_scoring_hybrid
                        } else {
                            R.string.rag_trace_scoring_vector
                        },
                    ),
                ),
            )
            add(rerankLine(trace))
            if (trace.outcome == RagRetrievalTrace.OUTCOME_EMPTY) add(listOf(RagText(R.string.rag_trace_empty_hint)))
        }
        RagRetrievalTrace.OUTCOME_UNAVAILABLE -> add(listOf(RagText(R.string.rag_trace_unavailable_hint)))
        RagRetrievalTrace.OUTCOME_NO_SCOPE -> add(listOf(RagText(R.string.rag_trace_no_scope_hint)))
        else -> add(listOf(RagText(R.string.rag_trace_failed_hint)))
    }
    trace.memoryScope?.let { scope ->
        add(buildList {
            add(RagText(R.string.rag_trace_scope_memory, listOf(RagText(scope.labelRes(ScopeResource.Memory)))))
            trace.memorySessionCount?.let { add(plural(R.plurals.rag_trace_reach_sessions, it)) }
            // 跨助手档才需要说明助手数；其余档位只涉及本助手。
            if (scope == ConversationScope.ALL_AGENTS) trace.memoryAgentCount?.let { add(plural(R.plurals.rag_trace_reach_agents, it)) }
        })
    }
    trace.graphScope?.let { add(listOf(RagText(R.string.rag_trace_scope_graph, listOf(RagText(it.labelRes(ScopeResource.Graph)))))) }
    trace.documentScope?.let { add(listOf(RagText(R.string.rag_trace_scope_documents, listOf(RagText(it.labelRes()))))) }
    if (trace.jitExtractionCount > 0) {
        add(buildList {
            add(RagText(R.string.rag_trace_jit, listOf(trace.jitExtractionCount)))
            trace.jitModel?.takeIf(String::isNotBlank)?.let { add(RagText(R.string.rag_trace_jit_model, listOf(it.substringAfter("::", it)))) }
        })
    }
    if (trace.searchTimeMs > 0) add(listOf(RagText(R.string.rag_trace_time, listOf(trace.searchTimeMs))))
}

/** 检索卡摘要中的范围段：优先说明对话记忆档位，未检索记忆时说明文档档位。 */
internal fun ragScopeSegment(trace: RagRetrievalTrace?): RagText? {
    val label = trace?.memoryScope?.labelRes(ScopeResource.Memory) ?: trace?.documentScope?.labelRes() ?: return null
    return RagText(R.string.chat_rag_scope_segment, listOf(RagText(label)))
}

/**
 * 检索卡摘要下一行：按来源计数（对话记忆、文档、图谱路径），并标出图谱临时抽取次数。
 * 引用按是否关联文档区分来源，会话摘要计入记忆。没有任何条目时为空。
 */
internal fun ragSourceBreakdown(references: List<RagReference>, kgPathCount: Int, trace: RagRetrievalTrace?): List<RagText> = buildList {
    val documents = references.count { it.documentId != null || it.kind == RagReferenceKind.DOCUMENT }
    val memory = references.size - documents
    if (memory > 0) add(RagText(R.string.chat_rag_breakdown_memory, listOf(memory)))
    if (documents > 0) add(RagText(R.string.chat_rag_breakdown_documents, listOf(documents)))
    if (kgPathCount > 0) add(RagText(R.string.chat_rag_breakdown_graph, listOf(kgPathCount)))
    val jit = trace?.jitExtractionCount ?: 0
    if (jit > 0) add(RagText(R.string.chat_rag_breakdown_jit, listOf(jit)))
}

private fun rerankLine(trace: RagRetrievalTrace): List<RagText> = when (trace.rerank) {
    RagRetrievalTrace.RERANK_API -> listOf(
        RagText(R.string.rag_trace_rerank_api, listOf(trace.rerankModel ?: "—")),
        RagText(R.string.rag_trace_rerank_time, listOf(trace.rerankTimeMs)),
    )
    RagRetrievalTrace.RERANK_LLM -> listOf(
        RagText(R.string.rag_trace_rerank_llm, listOf(trace.rerankModel ?: "—")),
        RagText(R.string.rag_trace_rerank_time, listOf(trace.rerankTimeMs)),
    )
    RagRetrievalTrace.RERANK_SKIPPED -> listOf(RagText(R.string.rag_trace_rerank_skipped))
    RagRetrievalTrace.RERANK_FAILED -> listOf(RagText(R.string.rag_trace_rerank_failed))
    else -> listOf(RagText(R.string.rag_trace_rerank_off))
}

/** 单条引用的分数与名次说明：来源类型、召回分、重排分，以及重排前后的名次。 */
internal fun ragReferenceParts(reference: RagReference, scoring: String?): List<RagText> = buildList {
    when (reference.kind) {
        RagReferenceKind.MEMORY -> add(RagText(R.string.rag_details_kind_memory))
        RagReferenceKind.DOCUMENT -> add(RagText(R.string.rag_details_kind_document))
        RagReferenceKind.SUMMARY -> add(RagText(R.string.rag_details_kind_summary))
    }
    if (reference.score > 0f) {
        add(
            RagText(
                if (scoring == RagRetrievalTrace.SCORING_HYBRID) {
                    R.string.rag_details_score_fused
                } else {
                    R.string.rag_details_score_similarity
                },
                listOf(percent(reference.score)),
            ),
        )
    }
    reference.rerankScore?.let { add(RagText(R.string.rag_details_score_rerank_value, listOf(percent(it)))) }
    val change = reference.rankChange
    val recallRank = reference.recallRank
    if (change != null && recallRank != null) {
        add(
            when {
                change > 0 -> RagText(R.string.rag_details_rank_from_up, listOf(recallRank, change))
                change < 0 -> RagText(R.string.rag_details_rank_from_down, listOf(recallRank, -change))
                else -> RagText(R.string.rag_details_rank_unchanged)
            },
        )
    }
}

private fun percent(value: Float): Int = (value.coerceIn(0f, 1f) * 100).roundToInt()
