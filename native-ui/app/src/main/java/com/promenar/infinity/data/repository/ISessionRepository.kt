package com.promenar.infinity.data.repository

import com.promenar.infinity.data.model.ContextCompactionSource
import com.promenar.infinity.data.model.Session

interface ISessionRepository {
    suspend fun create(session: Session)
    suspend fun updatePartial(id: String, updates: Map<String, Any?>)
    suspend fun delete(id: String)
    suspend fun getById(id: String): Session?
    suspend fun getAll(): List<Session>

    /** 摘要及消息归档必须同笔提交；不支持此能力的仓库不得静默成功。 */
    suspend fun commitContextCompaction(
        sessionId: String,
        summary: String,
        messageIds: List<String>,
        source: ContextCompactionSource,
    ) {
        throw UnsupportedOperationException("会话仓库未实现上下文压缩持久化")
    }
}
