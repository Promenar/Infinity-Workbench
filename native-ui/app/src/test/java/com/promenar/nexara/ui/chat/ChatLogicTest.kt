package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.SessionOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {

    @Test
    fun testRagOptionsSplitting() {
        val options = RagOptions()
        val sessionRagOnly = options.copy(enableMemory = true, isGlobal = false)
        assertTrue(sessionRagOnly.enableMemory)
        assertFalse(sessionRagOnly.isGlobal)

        val globalRetrieval = options.copy(enableMemory = true, isGlobal = true)
        assertTrue(globalRetrieval.enableMemory)
        assertTrue(globalRetrieval.isGlobal)
    }

    @Test
    fun testChatStoreReactivity() = runBlocking {
        val store = ChatStore()
        val sessionId = "test-session"
        val initialSession = Session(id = sessionId, agentId = "test-agent", options = SessionOptions(fontSize = 13))
        
        store.update { it.copy(sessions = listOf(initialSession)) }
        
        assertEquals(13, store.getSession(sessionId)?.options?.fontSize)
        
        store.updateSession(sessionId) { s ->
            s.copy(options = s.options.copy(fontSize = 16))
        }
        
        val updatedSession = store.getSession(sessionId)
        assertEquals(16, updatedSession?.options?.fontSize)
        
        val state = store.state.first()
        assertEquals(16, state.sessions.find { it.id == sessionId }?.options?.fontSize)
    }
}
