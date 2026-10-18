package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.PhaseStatus
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.model.RagRetrievalTrace
import org.junit.Test

class RagDetailsInlineSummaryTest {
    @Test
    fun `检索进行中不将内部阶段名作为摘要`() {
        val summary = ragInlineSummary(
            phases = listOf(RagPhase("vector_search", "internal-index-42", PhaseStatus.ACTIVE, 42)),
            referenceCount = 5,
            isComplete = false,
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(RagInlineSource.KNOWLEDGE, RagInlineStatus.RUNNING),
        )
    }

    @Test
    fun `完成检索优先显示实际引用数`() {
        val summary = ragInlineSummary(
            phases = emptyList(),
            referenceCount = 5,
            isComplete = true,
            trace = RagRetrievalTrace(selectedCount = 3),
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(RagInlineSource.KNOWLEDGE, RagInlineStatus.COMPLETED, resultCount = 5),
        )
    }

    @Test
    fun `没有引用时保留过程记录中的命中数`() {
        val summary = ragInlineSummary(
            phases = emptyList(),
            referenceCount = 0,
            isComplete = true,
            trace = RagRetrievalTrace(selectedCount = 4),
        )

        assertThat(summary.resultCount).isEqualTo(4)
    }

    @Test
    fun `未命中是零条结果而非检索失败`() {
        val summary = ragInlineSummary(
            phases = emptyList(),
            referenceCount = 0,
            isComplete = true,
            trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY),
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(RagInlineSource.KNOWLEDGE, RagInlineStatus.COMPLETED, resultCount = 0),
        )
    }

    @Test
    fun `ready阶段完成时已可显示检索结果`() {
        val summary = ragInlineSummary(
            phases = listOf(RagPhase("ready", "ready", PhaseStatus.DONE, 100)),
            referenceCount = 2,
            isComplete = false,
        )

        assertThat(summary.status).isEqualTo(RagInlineStatus.COMPLETED)
        assertThat(summary.resultCount).isEqualTo(2)
    }

    @Test
    fun `只有网页引用时摘要显示网页结果`() {
        val summary = ragInlineSummary(
            phases = emptyList(),
            referenceCount = 0,
            citationCount = 3,
            isComplete = true,
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(RagInlineSource.WEB, RagInlineStatus.COMPLETED, resultCount = 3),
        )
    }

    @Test
    fun `混合来源与知识图谱不会被误标成仅网页检索`() {
        val withKnowledge = ragInlineSummary(
            phases = emptyList(), referenceCount = 2, citationCount = 3, isComplete = true,
        )
        val withGraph = ragInlineSummary(
            phases = emptyList(), referenceCount = 0, knowledgePathCount = 1,
            citationCount = 3, isComplete = true,
        )

        assertThat(withKnowledge.source).isEqualTo(RagInlineSource.KNOWLEDGE)
        assertThat(withKnowledge.resultCount).isEqualTo(2)
        assertThat(withGraph.source).isEqualTo(RagInlineSource.KNOWLEDGE)
        assertThat(withGraph.resultCount).isEqualTo(1)
    }

    @Test
    fun `实际知识引用和图谱路径计数优先于过期未命中轨迹`() {
        val summary = ragInlineSummary(
            phases = emptyList(), referenceCount = 2, knowledgePathCount = 3,
            isComplete = true,
            trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY, selectedCount = 0),
        )
        assertThat(summary.resultCount).isEqualTo(5)
    }

    @Test
    fun `没有实际结果时不可用未选范围及失败均隐藏轨迹中的结果数`() {
        listOf(
            RagRetrievalTrace.OUTCOME_UNAVAILABLE,
            RagRetrievalTrace.OUTCOME_NO_SCOPE,
            RagRetrievalTrace.OUTCOME_FAILED,
            "unknown-outcome",
        ).forEach { outcome ->
            val summary = ragInlineSummary(
                phases = emptyList(), referenceCount = 0, isComplete = true,
                trace = RagRetrievalTrace(outcome = outcome, selectedCount = 3),
            )

            assertThat(summary).isEqualTo(
                RagInlineSummary(RagInlineSource.KNOWLEDGE, RagInlineStatus.FAILED),
            )
        }
    }

    @Test
    fun `知识库检索失败时独立图谱命中仍显示结果并提示部分失败`() {
        listOf(RagRetrievalTrace.OUTCOME_FAILED, RagRetrievalTrace.OUTCOME_UNAVAILABLE).forEach { outcome ->
            val summary = ragInlineSummary(
                phases = emptyList(), referenceCount = 0, knowledgePathCount = 2, isComplete = true,
                trace = RagRetrievalTrace(outcome = outcome),
            )

            assertThat(summary).isEqualTo(
                RagInlineSummary(
                    RagInlineSource.KNOWLEDGE, RagInlineStatus.COMPLETED,
                    resultCount = 2, partialFailure = true,
                ),
            )
        }
    }

    @Test
    fun `知识库不可用时网页引用显示网页命中并提示部分失败`() {
        val summary = ragInlineSummary(
            phases = emptyList(), referenceCount = 0, citationCount = 3, isComplete = true,
            trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_UNAVAILABLE, selectedCount = 8),
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(RagInlineSource.WEB, RagInlineStatus.COMPLETED, resultCount = 3, partialFailure = true),
        )
    }

    @Test
    fun `部分失败的知识库计数合并实际引用与图谱路径但不混入网页引用`() {
        val summary = ragInlineSummary(
            phases = emptyList(), referenceCount = 2, knowledgePathCount = 3,
            citationCount = 4, isComplete = true,
            trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED, selectedCount = 50),
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(
                RagInlineSource.KNOWLEDGE, RagInlineStatus.COMPLETED,
                resultCount = 5, partialFailure = true,
            ),
        )
    }

    @Test
    fun `知识库未命中但网页有结果时显示实际网页结果且不虚报失败`() {
        val summary = ragInlineSummary(
            phases = emptyList(), referenceCount = 0, citationCount = 3, isComplete = true,
            trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_EMPTY),
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(RagInlineSource.WEB, RagInlineStatus.COMPLETED, resultCount = 3),
        )
    }

    @Test
    fun `检索尚未完成时保留运行态而不提前宣称部分成功`() {
        val summary = ragInlineSummary(
            phases = listOf(RagPhase("kg_retrieval", "kg_retrieval", PhaseStatus.ACTIVE, 60)),
            referenceCount = 0, knowledgePathCount = 2, isComplete = false,
            trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED),
        )

        assertThat(summary).isEqualTo(
            RagInlineSummary(RagInlineSource.KNOWLEDGE, RagInlineStatus.RUNNING),
        )
    }
}
