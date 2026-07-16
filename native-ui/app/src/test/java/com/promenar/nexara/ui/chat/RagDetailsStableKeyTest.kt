package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.KgEdge
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.chat.components.citationStableKey
import com.promenar.nexara.ui.chat.components.kgPathStableKey
import com.promenar.nexara.ui.chat.components.ragReferenceStableKey
import com.promenar.nexara.ui.chat.components.stableOccurrenceKeys
import org.junit.Test

class RagDetailsStableKeyTest {
    @Test
    fun `空 id 文档在前插和重排后保持同一 key`() {
        val first = reference("/docs/first.md", "first content")
        val second = reference("/docs/second.md", "second content")
        val inserted = reference("/docs/inserted.md", "inserted content")
        val before = listOf(first, second).associate { it.source to ragReferenceStableKey(it) }
        val after = listOf(inserted, second, first).associate { it.source to ragReferenceStableKey(it) }

        assertThat(after[first.source]).isEqualTo(before[first.source])
        assertThat(after[second.source]).isEqualTo(before[second.source])
        assertThat(ragReferenceStableKey(first.copy())).isEqualTo(before[first.source])
    }

    @Test
    fun `同一文档块的分数和排名变化不改变 key`() {
        val reference = reference("/docs/stable.md", "stable content")
        val changedRanking = reference.copy(
            score = 0.42f,
            rerankScore = 0.99f,
            rankChange = -3,
        )

        assertThat(ragReferenceStableKey(changedRanking))
            .isEqualTo(ragReferenceStableKey(reference))
    }

    @Test
    fun `引用在前插和重排后保持同一 key`() {
        val first = citation("https://example.com/first", "First")
        val second = citation("https://example.com/second", "Second")
        val inserted = citation("https://example.com/inserted", "Inserted")
        val before = listOf(first, second).associate { it.url to citationStableKey(it) }
        val after = listOf(second, inserted, first).associate { it.url to citationStableKey(it) }

        assertThat(after[first.url]).isEqualTo(before[first.url])
        assertThat(after[second.url]).isEqualTo(before[second.url])
        assertThat(citationStableKey(first.copy())).isEqualTo(before[first.url])
    }

    @Test
    fun `知识路径在前插和重排后保持同一 key`() {
        val first = path("first")
        val second = path("second")
        val inserted = path("inserted")
        val before = listOf(first, second).associate { it.queryKeywords.first() to kgPathStableKey(it) }
        val after = listOf(inserted, first, second).associate { it.queryKeywords.first() to kgPathStableKey(it) }

        assertThat(after["first"]).isEqualTo(before["first"])
        assertThat(after["second"]).isEqualTo(before["second"])
        assertThat(kgPathStableKey(first.copy())).isEqualTo(before["first"])
    }

    @Test
    fun `完全重复项保留且非同项前插不改变 occurrence key`() {
        val before = stableOccurrenceKeys(listOf("same", "same", "other"))
        val after = stableOccurrenceKeys(listOf("inserted", "same", "same", "other"))

        assertThat(before).hasSize(3)
        assertThat(before.toSet()).hasSize(3)
        assertThat(after.drop(1)).containsExactlyElementsIn(before).inOrder()
    }

    private fun reference(source: String, content: String) = RagReference(
        id = "",
        source = source,
        content = content,
        score = 0.9f,
        documentId = source,
        chunkIndex = 1,
    )

    private fun citation(url: String, title: String) = Citation(
        title = title,
        url = url,
        source = "Test source",
        snippet = "Test snippet",
    )

    private fun path(identity: String): KgPath {
        val source = KgNode("$identity-source", "$identity source", "concept")
        val target = KgNode("$identity-target", "$identity target", "concept")
        return KgPath(
            queryKeywords = listOf(identity),
            nodes = listOf(source, target),
            edges = listOf(KgEdge(source.id, target.id, "relates-to")),
            reasoning = "$identity reasoning",
        )
    }
}
