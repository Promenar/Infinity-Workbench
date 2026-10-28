package com.promenar.infinity.ui.canvas

import com.promenar.infinity.data.artifact.ArtifactKind
import com.promenar.infinity.data.artifact.ArtifactSummary

/** 产物页顶部的分类筛选。 */
internal enum class ArtifactFilter(val kind: ArtifactKind?) {
    ALL(null),
    PROTOTYPE(ArtifactKind.PROTOTYPE),
    PAGE(ArtifactKind.PAGE),
    DOCUMENT(ArtifactKind.DOCUMENT),
    IMAGE(ArtifactKind.IMAGE),
    OTHER(ArtifactKind.OTHER),
}

internal data class ArtifactSection(val kind: ArtifactKind, val items: List<ArtifactSummary>)

internal data class ArtifactSectionsView(
    /** 原型以卡片网格单独呈现。 */
    val prototypes: List<ArtifactSummary>,
    val sections: List<ArtifactSection>,
    val showCandidates: Boolean,
) {
    val empty: Boolean get() = prototypes.isEmpty() && sections.isEmpty() && !showCandidates
}

/** 原型之后各组的顺序。 */
private val SECTION_ORDER = listOf(ArtifactKind.PAGE, ArtifactKind.DOCUMENT, ArtifactKind.IMAGE, ArtifactKind.OTHER)

internal fun artifactSections(items: List<ArtifactSummary>, filter: ArtifactFilter, candidateCount: Int): ArtifactSectionsView {
    fun shown(kind: ArtifactKind) = filter.kind == null || filter.kind == kind
    return ArtifactSectionsView(
        prototypes = items.filter { it.kind == ArtifactKind.PROTOTYPE && shown(ArtifactKind.PROTOTYPE) },
        sections = SECTION_ORDER.filter(::shown)
            .map { kind -> ArtifactSection(kind, items.filter { it.kind == kind }) }
            .filter { it.items.isNotEmpty() },
        showCandidates = filter == ArtifactFilter.ALL && candidateCount > 0,
    )
}

/** 有内容的筛选项及其数量，“全部”始终在首位。 */
internal fun artifactFilters(items: List<ArtifactSummary>): List<Pair<ArtifactFilter, Int>> =
    ArtifactFilter.entries.mapNotNull { filter ->
        val count = filter.kind?.let { kind -> items.count { it.kind == kind } } ?: items.size
        (filter to count).takeIf { filter == ArtifactFilter.ALL || count > 0 }
    }

/** 已选分类没有内容时回到“全部”。 */
internal fun effectiveArtifactFilter(selected: ArtifactFilter, items: List<ArtifactSummary>): ArtifactFilter =
    selected.takeIf { filter -> filter.kind == null || items.any { it.kind == filter.kind } } ?: ArtifactFilter.ALL
