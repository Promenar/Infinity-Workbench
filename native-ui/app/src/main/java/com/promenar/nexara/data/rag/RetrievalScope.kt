package com.promenar.nexara.data.rag

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope
import com.promenar.nexara.data.model.RagOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 对话记忆或图谱对话证据可读取的会话集合，按会话所属助手判定。 */
sealed interface ConversationReach {
    data class Session(val sessionId: String) : ConversationReach

    data class Agent(val agentId: String) : ConversationReach

    /** 本助手，以及未设为私密的其他助手。 */
    data class SharedAgents(val agentId: String, val privateAgentIds: Set<String>) : ConversationReach

    fun allows(sessionId: String?, sessionAgentId: String?): Boolean = when (this) {
        is Session -> sessionId == this.sessionId
        is Agent -> sessionAgentId == agentId
        is SharedAgents -> sessionAgentId != null && (sessionAgentId == agentId || sessionAgentId !in privateAgentIds)
    }
}

/** 文档检索可读取的范围。 */
sealed interface DocumentReach {
    data object All : DocumentReach

    /** 指定工作区根下的文件，以及显式选择（含已展开文件夹）的文档。 */
    data class Scoped(val workspaceRootUuids: Set<String>, val documentIds: Set<String>) : DocumentReach
}

data class ResolvedRetrievalScope(
    val sessionId: String,
    val memory: ConversationReach,
    val graph: ConversationReach,
    val documents: DocumentReach,
)

/**
 * 把会话检索选项解析为可执行的范围。选项应已按助手上限收紧（见 AgentMemoryPolicy.clamp）。
 * 向量检索、关键词检索与图谱可见性都从这里取范围，三条路径的结果因此一致。
 */
class RetrievalScopeResolver(private val database: NexaraDatabase) {
    suspend fun resolve(sessionId: String, options: RagOptions): ResolvedRetrievalScope = withContext(Dispatchers.IO) {
        val session = database.sessionDao().getById(sessionId)
        val agentId = session?.agentId
        var privateAgents: Set<String>? = null
        suspend fun privateAgents(): Set<String> = privateAgents ?: database.agentDao().getAll()
            .filter { it.id != agentId && it.retrievalConfig?.memoryPolicy?.shareWithOtherAgents == false }
            .mapTo(linkedSetOf()) { it.id }
            .also { privateAgents = it }

        suspend fun reach(scope: ConversationScope): ConversationReach = when {
            agentId == null || scope == ConversationScope.SESSION -> ConversationReach.Session(sessionId)
            scope == ConversationScope.AGENT -> ConversationReach.Agent(agentId)
            else -> ConversationReach.SharedAgents(agentId, privateAgents())
        }

        val documentScope = options.effectiveDocumentScope
        val documents = if (documentScope == DocumentScope.ALL_INDEXED) {
            DocumentReach.All
        } else {
            val roots = linkedSetOf<String>()
            session?.workspaceRootUuid?.takeIf(String::isNotBlank)?.let(roots::add)
            if (documentScope == DocumentScope.KNOWLEDGE_BASE) roots += knowledgeBaseRoots()
            DocumentReach.Scoped(roots, explicitDocuments(options))
        }
        ResolvedRetrievalScope(
            sessionId = sessionId,
            memory = reach(options.effectiveMemoryScope),
            graph = reach(options.effectiveGraphScope),
            documents = documents,
        )
    }

    private fun knowledgeBaseRoots(): List<String> = database.query(
        SimpleSQLiteQuery(
            "SELECT DISTINCT workspace_root_uuid FROM sessions WHERE agent_id = ? AND workspace_root_uuid IS NOT NULL AND workspace_root_uuid != ''",
            arrayOf(KNOWLEDGE_BASE_AGENT_ID),
        ),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** 显式文档与文件夹下的全部文件；文件夹在这里展开，下游不再区分。 */
    private fun explicitDocuments(options: RagOptions): Set<String> {
        val documents = options.activeDocIds.filterTo(linkedSetOf(), String::isNotBlank)
        val folders = options.activeFolderIds.filter(String::isNotBlank).distinct().take(MAX_SCOPED_DOCUMENTS)
        if (folders.isNotEmpty()) {
            val sql = "WITH RECURSIVE tree(id, root) AS (" +
                "SELECT uuid, workspace_root_uuid FROM workspace_files WHERE uuid IN (${folders.joinToString { "?" }}) AND in_recycle_bin = 0 " +
                "UNION SELECT w.uuid, w.workspace_root_uuid FROM workspace_files w JOIN tree t ON w.parent_uuid = t.id AND w.workspace_root_uuid = t.root WHERE w.in_recycle_bin = 0) " +
                "SELECT w.uuid FROM workspace_files w JOIN tree t ON w.uuid = t.id WHERE w.is_directory = 0 LIMIT $MAX_SCOPED_DOCUMENTS"
            database.query(SimpleSQLiteQuery(sql, folders.toTypedArray())).use { cursor ->
                while (cursor.moveToNext()) documents += cursor.getString(0)
            }
        }
        return documents.take(MAX_SCOPED_DOCUMENTS).toSet()
    }

    companion object {
        const val KNOWLEDGE_BASE_AGENT_ID = "__system__"

        /** 显式文档以绑定参数传入 SQL，限制数量以免超过 SQLite 参数上限。 */
        const val MAX_SCOPED_DOCUMENTS = 500
    }
}

/** 按范围读取对话记忆或摘要向量（doc_id 为空的行）。 */
internal suspend fun VectorDao.conversationRows(reach: ConversationReach, type: String): List<VectorEntity> = when (reach) {
    is ConversationReach.Session -> getConversationBySession(type, reach.sessionId)
    is ConversationReach.Agent -> getConversationByAgent(type, reach.agentId)
    is ConversationReach.SharedAgents -> getConversationBySharedAgents(type, reach.agentId, reach.privateAgentIds.toList())
}

internal suspend fun VectorDao.conversationFts(reach: ConversationReach, query: String): List<VectorEntity> = when (reach) {
    is ConversationReach.Session -> searchFtsConversationBySession(query, reach.sessionId)
    is ConversationReach.Agent -> searchFtsConversationByAgent(query, reach.agentId)
    is ConversationReach.SharedAgents -> searchFtsConversationBySharedAgents(query, reach.agentId, reach.privateAgentIds.toList())
}

internal suspend fun VectorDao.documentRows(reach: DocumentReach): List<VectorEntity> = when (reach) {
    DocumentReach.All -> getAllDocuments()
    is DocumentReach.Scoped -> if (reach.isEmpty) emptyList()
        else getDocumentsInScope(reach.workspaceRootUuids.toList(), reach.documentIds.toList())
}

internal suspend fun VectorDao.documentFts(reach: DocumentReach, query: String): List<VectorEntity> = when (reach) {
    DocumentReach.All -> searchFtsDocuments(query)
    is DocumentReach.Scoped -> if (reach.isEmpty) emptyList()
        else searchFtsDocumentsInScope(query, reach.workspaceRootUuids.toList(), reach.documentIds.toList())
}

private val DocumentReach.Scoped.isEmpty: Boolean
    get() = workspaceRootUuids.isEmpty() && documentIds.isEmpty()
