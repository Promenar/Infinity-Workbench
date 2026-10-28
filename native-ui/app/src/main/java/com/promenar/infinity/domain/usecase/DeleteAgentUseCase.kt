package com.promenar.infinity.domain.usecase

import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.domain.repository.IAgentRepository

/** 删除智能体前关闭它的会话入口，记录及关联数据仍由仓库负责删除。 */
class DeleteAgentUseCase(
    private val agents: IAgentRepository,
    private val sessions: ISessionRepository,
) {
    private val sessionClosers = mutableMapOf<Any, (Set<String>) -> Unit>()
    private val pendingClosedSessions = mutableSetOf<String>()

    /** 宿主在其 ViewModel 生命周期内保留关闭回调，编辑页覆盖首页时同样可用。 */
    fun registerSessionCloser(close: (Set<String>) -> Unit): () -> Unit {
        val owner = Any()
        if (pendingClosedSessions.isNotEmpty()) {
            close(pendingClosedSessions.toSet())
            pendingClosedSessions.clear()
        }
        sessionClosers[owner] = close
        return { sessionClosers.remove(owner) }
    }

    suspend operator fun invoke(agentId: String) {
        val ids = sessions.getAll().filter { it.agentId == agentId }.mapTo(hashSetOf()) { it.id }
        // 编辑页覆盖或宿主重建后，新的宿主也必须清理已保存的会话路由。
        pendingClosedSessions += ids
        sessionClosers.values.toList().forEach { it(ids) }
        agents.delete(agentId)
    }
}
