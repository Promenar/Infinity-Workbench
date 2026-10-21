package com.promenar.nexara.ui.chat.components

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope
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
    fun `详情说明列出实际执行的范围、覆盖的会话与助手，以及临时抽取`() {
        val scoped = hit.copy(
            memoryScope = ConversationScope.ALL_AGENTS,
            graphScope = ConversationScope.AGENT,
            documentScope = DocumentScope.KNOWLEDGE_BASE,
            memorySessionCount = 12,
            memoryAgentCount = 3,
            jitExtractionCount = 1,
            jitModel = "provider::glm-flash",
        )
        val lines = ragTraceLines(scoped)
        assertThat(lines).containsAtLeast(
            listOf(
                RagText(R.string.rag_trace_scope_memory, listOf(RagText(R.string.memory_scope_all_agents))),
                RagText(R.plurals.rag_trace_reach_sessions, listOf(12), quantity = 12),
                RagText(R.plurals.rag_trace_reach_agents, listOf(3), quantity = 3),
            ),
            listOf(RagText(R.string.rag_trace_scope_graph, listOf(RagText(R.string.graph_scope_agent)))),
            listOf(RagText(R.string.rag_trace_scope_documents, listOf(RagText(R.string.document_scope_knowledge_base)))),
            listOf(RagText(R.string.rag_trace_jit, listOf(1)), RagText(R.string.rag_trace_jit_model, listOf("glm-flash"))),
        ).inOrder()
        // 本助手档只涉及一个助手，不重复说明助手数；未参与的资源与未调用的临时抽取不出现。
        val agentOnly = ragTraceLines(hit.copy(memoryScope = ConversationScope.AGENT, memorySessionCount = 4, memoryAgentCount = 1))
        assertThat(agentOnly).contains(listOf(
            RagText(R.string.rag_trace_scope_memory, listOf(RagText(R.string.memory_scope_agent))),
            RagText(R.plurals.rag_trace_reach_sessions, listOf(4), quantity = 4),
        ))
        assertThat(agentOnly.flatten().map { it.resId }).containsNoneOf(
            R.string.rag_trace_scope_graph, R.string.rag_trace_scope_documents, R.string.rag_trace_jit,
        )
    }

    @Test
    fun `检索卡摘要显示范围档位，下一行按来源计数并标出临时抽取`() {
        assertThat(ragScopeSegment(hit.copy(memoryScope = ConversationScope.SESSION, documentScope = DocumentScope.WORKSPACE)))
            .isEqualTo(RagText(R.string.chat_rag_scope_segment, listOf(RagText(R.string.memory_scope_session))))
        assertThat(ragScopeSegment(hit.copy(documentScope = DocumentScope.ALL_INDEXED)))
            .isEqualTo(RagText(R.string.chat_rag_scope_segment, listOf(RagText(R.string.document_scope_all))))
        assertThat(ragScopeSegment(hit)).isNull()

        val references = listOf(
            RagReference(id = "m1", kind = RagReferenceKind.MEMORY),
            RagReference(id = "s1", kind = RagReferenceKind.SUMMARY),
            RagReference(id = "d1", documentId = "doc", kind = RagReferenceKind.DOCUMENT),
            RagReference(id = "legacy-doc", documentId = "doc-2"),
        )
        assertThat(ragSourceBreakdown(references, kgPathCount = 3, trace = hit.copy(jitExtractionCount = 1))).containsExactly(
            RagText(R.string.chat_rag_breakdown_memory, listOf(2)),
            RagText(R.string.chat_rag_breakdown_documents, listOf(2)),
            RagText(R.string.chat_rag_breakdown_graph, listOf(3)),
            RagText(R.string.chat_rag_breakdown_jit, listOf(1)),
        ).inOrder()
        assertThat(ragSourceBreakdown(emptyList(), kgPathCount = 0, trace = hit)).isEmpty()
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
