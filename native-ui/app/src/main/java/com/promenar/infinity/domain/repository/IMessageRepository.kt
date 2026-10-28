package com.promenar.infinity.domain.repository

import com.promenar.infinity.domain.model.Message
import com.promenar.infinity.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow

interface IMessageRepository {
    fun observeBySession(sessionId: String): Flow<List<Message>>
    suspend fun send(sessionId: String, content: String, role: MessageRole): Message
    suspend fun appendContent(messageId: String, chunk: String)
    suspend fun delete(id: String)
}
