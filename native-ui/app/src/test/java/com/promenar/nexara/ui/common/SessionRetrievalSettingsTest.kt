package com.promenar.nexara.ui.common

import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope
import com.promenar.nexara.data.model.RagOptions
import org.junit.Assert.*
import org.junit.Test

class SessionRetrievalSettingsTest {
    @Test fun fieldUpdatesComposeWithoutLosingEarlierChanges() {
        var current = RagOptions(memoryLimit = 9, docLimit = 12, enableHybridSearch = true)
        val first: (RagOptions) -> RagOptions = { it.withRetrievalToggle(SessionRetrievalToggle.Memory, false) }
        val second: (RagOptions) -> RagOptions = { it.withRetrievalToggle(SessionRetrievalToggle.Documents, false) }
        current = first(current)
        current = second(current)
        assertFalse(current.enableMemory)
        assertFalse(current.enableDocs)
        assertTrue(current.enableRerank)
        assertEquals(9, current.memoryLimit)
        assertEquals(12, current.docLimit)
        assertEquals(true, current.enableHybridSearch)
    }

    @Test fun eachToggleChangesOnlyItsOwnField() {
        val before = RagOptions(enableMemory = false, enableDocs = false,
            enableRerank = false, enableKnowledgeGraph = false, queryRewriteCount = 4)
        val expected = listOf(before.copy(enableMemory = true), before.copy(enableDocs = true),
            before.copy(enableRerank = true), before.copy(enableKnowledgeGraph = true))
        SessionRetrievalToggle.entries.forEachIndexed { index, toggle ->
            assertEquals(expected[index], before.withRetrievalToggle(toggle, true))
        }
    }

    @Test fun changingOneScopeFreezesTheOthersFromLegacyGlobalFlag() {
        val legacy = RagOptions(isGlobal = true)
        val updated = legacy.withMemoryScope(ConversationScope.ALL_AGENTS)
        assertFalse(updated.isGlobal)
        assertEquals(ConversationScope.ALL_AGENTS, updated.memoryScope)
        // 图谱与文档保持旧字段解析出的档位，不因 isGlobal 清零而回落为“仅本会话”。
        assertEquals(ConversationScope.AGENT, updated.graphScope)
        assertEquals(DocumentScope.KNOWLEDGE_BASE, updated.documentScope)

        assertEquals(ConversationScope.SESSION, legacy.withGraphScope(ConversationScope.SESSION).effectiveGraphScope)
        assertEquals(DocumentScope.ALL_INDEXED, legacy.withDocumentScope(DocumentScope.ALL_INDEXED).effectiveDocumentScope)
        assertEquals(ConversationScope.AGENT, legacy.withDocumentScope(DocumentScope.ALL_INDEXED).effectiveMemoryScope)
    }
}
