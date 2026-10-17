package com.promenar.nexara.ui.common

import com.promenar.nexara.data.model.RagOptions

internal enum class SessionRetrievalToggle { Memory, GlobalMemory, Documents, Rerank, KnowledgeGraph }
internal enum class SessionMemorySource { Disabled, Session, Global }

internal fun RagOptions.withRetrievalToggle(toggle: SessionRetrievalToggle, enabled: Boolean): RagOptions = when (toggle) {
    SessionRetrievalToggle.Memory -> copy(enableMemory = enabled)
    SessionRetrievalToggle.GlobalMemory -> copy(isGlobal = enabled)
    SessionRetrievalToggle.Documents -> copy(enableDocs = enabled)
    SessionRetrievalToggle.Rerank -> copy(enableRerank = enabled)
    SessionRetrievalToggle.KnowledgeGraph -> copy(enableKnowledgeGraph = enabled)
}

internal fun RagOptions.memorySource(): SessionMemorySource = when {
    !enableMemory -> SessionMemorySource.Disabled
    isGlobal -> SessionMemorySource.Global
    else -> SessionMemorySource.Session
}
