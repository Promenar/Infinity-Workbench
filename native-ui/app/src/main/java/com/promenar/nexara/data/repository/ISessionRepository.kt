package com.promenar.nexara.data.repository

import com.promenar.nexara.data.model.Session

interface ISessionRepository {
    suspend fun create(session: Session)
    suspend fun updatePartial(id: String, updates: Map<String, Any?>)
    suspend fun delete(id: String)
    suspend fun getById(id: String): Session?
    suspend fun getAll(): List<Session>
}
