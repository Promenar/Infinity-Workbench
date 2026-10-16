package com.promenar.nexara.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.RagMetadata
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagReferenceKind
import com.promenar.nexara.data.model.RagRetrievalTrace
import com.promenar.nexara.data.model.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Test

class RagTracePresentationTest {
    private val hit = RagRetrievalTrace(
        outcome = RagRetrievalTrace.OUTCOME_HIT,
        recallCount = 12,
        selectedCount = 5,
        scoring = RagRetrievalTrace.SCORING_HYBRID,
        rerank = RagRetrievalTrace.RERANK_API,
        rerankModel = "bge-reranker",
        searchTimeMs = 850,
        rerankTimeMs = 320,
        memoryCount = 2,
        documentCount = 3,
    )

    @Test
    fun `卡片摘要区分命中、重排、未命中与未能检索`() {
        assertThat(ragCardSummary(hit, 5)).containsExactly(
            RagText(R.string.chat_rag_summary_hit, listOf(5)),
            RagText(R.string.chat_rag_summary_reranked),
        ).inOrder()
        assertThat(ragCardSummary(hit.copy(rerank = RagRetrievalTrace.RERANK_OFF), 5))
            .containsExactly(RagText(R.string.chat_rag_summary_hit, listOf(5)))
        assertThat(ragCardSummary(hit.copy(rerank = RagRetrievalTrace.RERANK_FAILED), 5).last().resId)
            .isEqualTo(R.string.chat_rag_summary_rerank_failed)
        assertThat(ragCardSummary(hit.copy(outcome = RagRetrievalTrace.OUTCOME_EMPTY), 0).single().resId)
            .isEqualTo(R.string.chat_rag_summary_empty)
        assertThat(ragCardSummary(hit.copy(outcome = RagRetrievalTrace.OUTCOME_UNAVAILABLE), 0).single().resId)
            .isEqualTo(R.string.chat_rag_summary_unavailable)
        assertThat(ragOutcomeTone(hit)).isEqualTo(RagOutcomeTone.HIT)
        assertThat(ragOutcomeTone(hit.copy(outcome = RagRetrievalTrace.OUTCOME_EMPTY))).isEqualTo(RagOutcomeTone.NEUTRAL)
        assertThat(ragOutcomeTone(hit.copy(outcome = RagRetrievalTrace.OUTCOME_FAILED))).isEqualTo(RagOutcomeTone.ATTENTION)
    }

    @Test
    fun `详情说明列出召回采用、排序依据、重排方式与用时`() {
        assertThat(ragTraceLines(hit)).containsExactly(
            listOf(
                RagText(R.string.rag_trace_recall, listOf(12)),
                RagText(R.string.rag_trace_selected, listOf(5)),
                RagText(R.string.rag_trace_sources, listOf(2, 3)),
            ),
            listOf(RagText(R.string.rag_trace_scoring_hybrid)),
            listOf(
                RagText(R.string.rag_trace_rerank_api, listOf("bge-reranker")),
                RagText(R.string.rag_trace_rerank_time, listOf(320L)),
            ),
            listOf(RagText(R.string.rag_trace_time, listOf(850L))),
        ).inOrder()
        assertThat(ragTraceLines(hit.copy(outcome = RagRetrievalTrace.OUTCOME_EMPTY)))
            .contains(listOf(RagText(R.string.rag_trace_empty_hint)))
    }

    @Test
    fun `单条引用展示来源类型、召回分、重排分与重排前名次`() {
        val reference = RagReference(
            id = "r",
            score = 0.6f,
            rerankScore = 0.91f,
            rankChange = 2,
            recallRank = 3,
            kind = RagReferenceKind.DOCUMENT,
        )

        assertThat(ragReferenceParts(reference, RagRetrievalTrace.SCORING_VECTOR)).containsExactly(
            RagText(R.string.rag_details_kind_document),
            RagText(R.string.rag_details_score_similarity, listOf(60)),
            RagText(R.string.rag_details_score_rerank_value, listOf(91)),
            RagText(R.string.rag_details_rank_from_up, listOf(3, 2)),
        ).inOrder()
        assertThat(ragReferenceParts(reference.copy(rankChange = 0), RagRetrievalTrace.SCORING_HYBRID))
            .containsAtLeast(
                RagText(R.string.rag_details_score_fused, listOf(60)),
                RagText(R.string.rag_details_rank_unchanged),
            )
    }

    @Test
    fun `旧版检索元数据缺少轨迹字段时仍可读取`() {
        val legacy = """{"chunkCount":2,"totalTokens":10,"retrievalTimeMs":0}"""

        val decoded = json.decodeFromString<RagMetadata>(legacy)

        assertThat(decoded.trace).isNull()
        val roundTrip = json.decodeFromString<RagMetadata>(json.encodeToString(decoded.copy(trace = hit)))
        assertThat(roundTrip.trace).isEqualTo(hit)
    }
}
