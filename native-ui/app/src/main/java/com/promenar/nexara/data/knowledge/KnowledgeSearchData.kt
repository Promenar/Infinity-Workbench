package com.promenar.nexara.data.knowledge

import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.RagUsage
import kotlinx.serialization.Serializable

@Serializable
data class KnowledgeSearchHit(
    val referenceId: String,
    val citationId: String,
    val alreadyInContext: Boolean = false,
    val pathType: KnowledgeDocumentLocationKind? = null,
    val path: String? = null,
    val requiresAccess: Boolean = false,
)

/** references 与 kgPaths 直接供现有检索详情读取，results 按引用 id 补充展示信息。 */
@Serializable
data class KnowledgeSearchData(
    val query: String,
    val sources: List<String> = emptyList(),
    val disabledSources: List<String> = emptyList(),
    val references: List<RagReference> = emptyList(),
    val results: List<KnowledgeSearchHit> = emptyList(),
    val kgPaths: List<KgPath> = emptyList(),
    val empty: Boolean = false,
    val limitReached: Boolean = false,
    val usage: RagUsage? = null,
)
