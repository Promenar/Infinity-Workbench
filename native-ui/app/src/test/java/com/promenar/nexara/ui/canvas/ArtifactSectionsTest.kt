package com.promenar.nexara.ui.canvas

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.artifact.ArtifactKind
import com.promenar.nexara.data.artifact.ArtifactOrigin
import com.promenar.nexara.data.artifact.ArtifactSummary
import org.junit.Test

class ArtifactSectionsTest {
    private val items = listOf(
        item("d1", ArtifactKind.DOCUMENT), item("p1", ArtifactKind.PROTOTYPE), item("g1", ArtifactKind.PAGE),
        item("i1", ArtifactKind.IMAGE), item("g2", ArtifactKind.PAGE), item("p2", ArtifactKind.PROTOTYPE),
    )

    @Test fun `全部视图原型在前，其余按页面、文档、图片、其它分组并保持原顺序，空组不出现`() {
        val view = artifactSections(items, ArtifactFilter.ALL, candidateCount = 2)
        assertThat(view.prototypes.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(view.sections.map { it.kind }).containsExactly(ArtifactKind.PAGE, ArtifactKind.DOCUMENT, ArtifactKind.IMAGE).inOrder()
        assertThat(view.sections.first().items.map { it.id }).containsExactly("g1", "g2").inOrder()
        assertThat(view.showCandidates).isTrue()
        assertThat(view.empty).isFalse()
    }

    @Test fun `分类视图只含该类，候选只在全部视图出现`() {
        val pages = artifactSections(items, ArtifactFilter.PAGE, candidateCount = 2)
        assertThat(pages.prototypes).isEmpty()
        assertThat(pages.sections.map { it.kind }).containsExactly(ArtifactKind.PAGE)
        assertThat(pages.showCandidates).isFalse()
        assertThat(artifactSections(items, ArtifactFilter.PROTOTYPE, 0).sections).isEmpty()
        assertThat(artifactSections(items, ArtifactFilter.OTHER, 0).empty).isTrue()
    }

    @Test fun `筛选项只列出有内容的分类，全部始终在首位；已选分类清空后回到全部`() {
        assertThat(artifactFilters(items).map { it.first })
            .containsExactly(ArtifactFilter.ALL, ArtifactFilter.PROTOTYPE, ArtifactFilter.PAGE, ArtifactFilter.DOCUMENT, ArtifactFilter.IMAGE).inOrder()
        assertThat(artifactFilters(items).first().second).isEqualTo(6)
        assertThat(effectiveArtifactFilter(ArtifactFilter.OTHER, items)).isEqualTo(ArtifactFilter.ALL)
        assertThat(effectiveArtifactFilter(ArtifactFilter.PAGE, items)).isEqualTo(ArtifactFilter.PAGE)
    }

    private fun item(id: String, kind: ArtifactKind) = ArtifactSummary(
        id = id, kind = kind, origin = ArtifactOrigin.CANVAS, title = id, createdAt = 0, updatedAt = 0,
        currentVersion = 1, createdByMessageId = "m",
    )
}
