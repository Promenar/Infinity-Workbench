package com.promenar.nexara.ui.common

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
        val before = RagOptions(enableMemory = false, isGlobal = false, enableDocs = false,
            enableRerank = false, enableKnowledgeGraph = false, queryRewriteCount = 4)
        val expected = listOf(before.copy(enableMemory = true), before.copy(isGlobal = true),
            before.copy(enableDocs = true), before.copy(enableRerank = true), before.copy(enableKnowledgeGraph = true))
        SessionRetrievalToggle.entries.forEachIndexed { index, toggle ->
            assertEquals(expected[index], before.withRetrievalToggle(toggle, true))
        }
    }

    @Test fun memoryGateTakesPrecedenceOverGlobalScope() {
        assertEquals(SessionMemorySource.Disabled, RagOptions(enableMemory = false, isGlobal = true).memorySource())
        assertEquals(SessionMemorySource.Disabled, RagOptions(enableMemory = false, isGlobal = false).memorySource())
        assertEquals(SessionMemorySource.Session, RagOptions(enableMemory = true, isGlobal = false).memorySource())
        assertEquals(SessionMemorySource.Global, RagOptions(enableMemory = true, isGlobal = true).memorySource())
    }
}
