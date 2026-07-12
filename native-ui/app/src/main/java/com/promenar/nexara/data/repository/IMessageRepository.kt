package com.promenar.nexara.data.repository

import com.promenar.nexara.data.model.Message

class MessagePersistenceException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface IMessageRepository {
    suspend fun insert(message: Message, sessionId: String)
    suspend fun updatePartial(messageId: String, updates: Map<String, Any?>)
    suspend fun delete(messageId: String)
    suspend fun deleteInSession(sessionId: String, messageId: String): Boolean {
        delete(messageId)
        return true
    }
    suspend fun deleteBySessionId(sessionId: String)
    suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long)
    suspend fun getById(messageId: String): Message?
    suspend fun getBySession(sessionId: String): List<Message>
    suspend fun updateVectorizationStatus(messageId: String, status: String, isArchived: Boolean?)
}
