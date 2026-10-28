package com.promenar.infinity.ui.common

import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions

internal enum class SessionRetrievalToggle { Memory, Documents, Rerank, KnowledgeGraph }

internal fun RagOptions.withRetrievalToggle(toggle: SessionRetrievalToggle, enabled: Boolean): RagOptions = when (toggle) {
    SessionRetrievalToggle.Memory -> copy(enableMemory = enabled)
    SessionRetrievalToggle.Documents -> copy(enableDocs = enabled)
    SessionRetrievalToggle.Rerank -> copy(enableRerank = enabled)
    SessionRetrievalToggle.KnowledgeGraph -> copy(enableKnowledgeGraph = enabled)
}

// 写入任一档位时三类一并落为显式值，旧会话的 isGlobal 从此不再参与解析，避免另两类随旧字段回落。
internal fun RagOptions.withMemoryScope(scope: ConversationScope): RagOptions = withExplicitScopes().copy(memoryScope = scope)

internal fun RagOptions.withGraphScope(scope: ConversationScope): RagOptions = withExplicitScopes().copy(graphScope = scope)

internal fun RagOptions.withDocumentScope(scope: DocumentScope): RagOptions = withExplicitScopes().copy(documentScope = scope)

private fun RagOptions.withExplicitScopes(): RagOptions = copy(
    isGlobal = false,
    memoryScope = effectiveMemoryScope,
    graphScope = effectiveGraphScope,
    documentScope = effectiveDocumentScope,
)
