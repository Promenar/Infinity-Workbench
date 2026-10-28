package com.promenar.infinity.data.knowledge

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ConversationScope
import org.junit.jupiter.api.Test

class KnowledgeCatalogTest {
    @Test
    fun `无实际索引时不渲染资料目录`() {
        val empty = KnowledgeCatalogSnapshot(
            groups = listOf(KnowledgeCatalogGroup("kb", "资料", 0)),
            history = KnowledgeCatalogHistory(ConversationScope.ALL_AGENTS, 50, 0),
        )
        assertThat(renderKnowledgeCatalog(empty)).isEmpty()
    }

    @Test
    fun `文档标题共享三十个限额并按最近更新排序`() {
        val docs = (1..40).map { KnowledgeCatalogDocument("$it", "标题$it", it.toLong()) }
        val catalog = renderKnowledgeCatalog(KnowledgeCatalogSnapshot(
            groups = listOf(KnowledgeCatalogGroup("kb", "知识库", 20, true, true), KnowledgeCatalogGroup("ws", "工作区", 20)),
            documents = docs,
        ))
        assertThat(Regex("文档《").findAll(catalog).count()).isEqualTo(30)
        assertThat(catalog.indexOf("标题40》")).isLessThan(catalog.indexOf("标题39》"))
        assertThat(catalog).doesNotContain("标题10》")
        assertThat(catalog).contains("另有 10 个已索引文档未列出标题")
        assertThat(catalog).contains("已挂载")
    }

    @Test
    fun `长标题和多来源不能突破总预算且隐私历史不含会话标题`() {
        val catalog = renderKnowledgeCatalog(KnowledgeCatalogSnapshot(
            groups = (1..60).map { KnowledgeCatalogGroup("$it", "名称".repeat(200), 1) },
            documents = (1..60).map { KnowledgeCatalogDocument("$it", "文档名".repeat(200), it.toLong()) },
            history = KnowledgeCatalogHistory(ConversationScope.ALL_AGENTS, 8, 2),
        ))
        assertThat(catalog.length).isAtMost(1500)
        assertThat(catalog).contains("历史对话：全部可见智能体，8 个会话")
        assertThat(catalog).contains("另有")
        assertThat(Regex("文档《").findAll(catalog).count()).isAtMost(30)
    }

    @Test
    fun `只有有效历史索引时目录只描述范围与数量`() {
        val catalog = renderKnowledgeCatalog(KnowledgeCatalogSnapshot(history = KnowledgeCatalogHistory(ConversationScope.AGENT, 3, 1)))
        assertThat(catalog).isEqualTo("可检索资料：0 个已索引文档\n历史对话：本智能体，3 个会话")
    }
}
