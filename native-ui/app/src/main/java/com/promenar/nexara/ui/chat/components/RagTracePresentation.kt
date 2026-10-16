package com.promenar.nexara.ui.chat.components

import androidx.annotation.StringRes
import com.promenar.nexara.R
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagReferenceKind
import com.promenar.nexara.data.model.RagRetrievalTrace
import kotlin.math.roundToInt

/** 待本地化的一段文字：资源 ID 加格式参数。 */
data class RagText(@StringRes val resId: Int, val args: List<Any> = emptyList())

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
    if (trace.searchTimeMs > 0) add(listOf(RagText(R.string.rag_trace_time, listOf(trace.searchTimeMs))))
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
