package com.promenar.infinity.ui.chat.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.promenar.infinity.R
import com.promenar.infinity.data.knowledge.KnowledgeDocumentLocationKind
import com.promenar.infinity.data.knowledge.KnowledgeSearchData
import com.promenar.infinity.data.knowledge.KnowledgeSearchHit
import kotlinx.serialization.json.Json

/** 检索详情中知识检索工具的补充信息：查询词，以及按引用 id 对应的编号与读取路径。 */
@Immutable
data class KnowledgeSearchDetails(
    val query: String,
    val hits: Map<String, KnowledgeSearchHit>,
)

internal fun KnowledgeSearchData.toDetails(): KnowledgeSearchDetails =
    KnowledgeSearchDetails(query = query, hits = results.associateBy { it.referenceId })

private val knowledgeSearchJson = Json { ignoreUnknownKeys = true }

/** 工具结果的结构化数据；超出预算时 data 为空、旧版本或损坏的数据解析失败，均返回 null 由界面降级。 */
internal fun parseKnowledgeSearchData(raw: String?): KnowledgeSearchData? =
    raw?.takeIf(String::isNotBlank)?.let { runCatching { knowledgeSearchJson.decodeFromString<KnowledgeSearchData>(it) }.getOrNull() }

/** 读取路径行：[isPath] 为 true 时 [text] 是可读取的路径，否则 [messageRes] 说明为什么不能读取全文。 */
internal data class KnowledgeHitPathLine(val isPath: Boolean, val text: String = "", @param:StringRes val messageRes: Int = 0)

internal fun knowledgeHitPathLine(hit: KnowledgeSearchHit): KnowledgeHitPathLine? = when (hit.pathType) {
    null -> null
    KnowledgeDocumentLocationKind.WORKSPACE, KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED ->
        hit.path?.takeIf(String::isNotBlank)?.let { KnowledgeHitPathLine(isPath = true, text = it) }
    KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED ->
        KnowledgeHitPathLine(isPath = false, messageRes = R.string.knowledge_search_path_unmounted)
    KnowledgeDocumentLocationKind.SNIPPET_ONLY ->
        KnowledgeHitPathLine(isPath = false, messageRes = R.string.knowledge_search_path_snippet_only)
}

internal const val KNOWLEDGE_SEARCH_QUERY_TAG = "RAG_DETAILS_KNOWLEDGE_QUERY"
internal const val KNOWLEDGE_SEARCH_PATH_TAG = "RAG_DETAILS_KNOWLEDGE_PATH"
