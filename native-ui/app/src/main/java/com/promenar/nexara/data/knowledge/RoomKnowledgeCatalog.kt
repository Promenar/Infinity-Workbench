package com.promenar.nexara.data.knowledge

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.nexara.data.local.db.Converters
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.model.ConversationScope
import com.promenar.nexara.data.model.DocumentScope
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.rag.ConversationReach
import com.promenar.nexara.data.rag.DocumentReach
import com.promenar.nexara.data.rag.RetrievalScopeResolver
import com.promenar.nexara.data.rag.documentTitleFromVectorMetadata
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.workspace.WorkspaceDefaultFileDestination
import com.promenar.nexara.data.workspace.workspaceFilePathPresenter
import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只查询当前数据库元数据与有效向量，不创建工作区、不触发索引。 */
class RoomKnowledgeCatalog(
    private val database: NexaraDatabase,
    private val externalAccess: ExternalWorkspaceAccess,
) : KnowledgeCatalogSource {
    private val resolver = RetrievalScopeResolver(database)

    override suspend fun knowledgeBases(session: Session, options: RagOptions): List<KnowledgeBaseSummary> = withContext(Dispatchers.IO) {
        if (!options.enableDocs || options.effectiveDocumentScope == DocumentScope.WORKSPACE) return@withContext emptyList()
        val reach = resolver.resolve(session.id, options).documents
        val documents = indexedDocuments(reach)
        roots().filter { root -> reach == DocumentReach.All || root.uuid in (reach as DocumentReach.Scoped).workspaceRootUuids }
            .map { root -> KnowledgeBaseSummary(root.uuid, root.title, documents.count { it.rootUuid == root.uuid }, root.isDefault) }
    }

    override suspend fun snapshot(session: Session, options: RagOptions): KnowledgeCatalogSnapshot = withContext(Dispatchers.IO) {
        val scope = resolver.resolve(session.id, options)
        val documents = if (options.enableDocs) indexedDocuments(scope.documents) else emptyList()
        val rootNames = rootNames()
        val knowledgeBases = roots().associateBy { it.uuid }
        val mounts = externalAccess.visible(session.id)
        val groups = documents.groupBy { it.rootUuid }.map { (uuid, entries) ->
            val knowledgeBase = knowledgeBases[uuid]
            KnowledgeCatalogGroup(
                rootUuid = uuid,
                title = knowledgeBase?.title ?: rootNames[uuid] ?: "其它已索引文档",
                documentCount = entries.size,
                knowledgeBase = knowledgeBase != null,
                mounted = knowledgeBase != null && mounts.any {
                    it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE && it.sourceId == uuid && it.readOnly
                },
            )
        }.sortedWith(compareByDescending<KnowledgeCatalogGroup> { it.knowledgeBase }.thenBy { it.title }.thenBy { it.rootUuid })
        val history = if (options.enableMemory) {
            val stats = resolver.stats(scope.memory)
            KnowledgeCatalogHistory(options.effectiveMemoryScope, stats.sessionCount, indexedHistorySessionCount(scope.memory))
        } else null
        KnowledgeCatalogSnapshot(groups, documents.map { KnowledgeCatalogDocument(it.id, it.title, it.updatedAt) }, history)
    }

    /** 与向量检索使用相同范围和有效性条件；只做 EXISTS，不读取 embedding BLOB。 */
    override fun hasIndexedMaterial(session: Session, options: RagOptions): Boolean {
        if (options.enableDocs && indexedDocumentsExist(synchronousDocumentReach(session, options))) return true
        if (!options.enableMemory) return false
        val agentId = sessionAgentId(session.id)
        val reach = when {
            agentId == null || options.effectiveMemoryScope == ConversationScope.SESSION -> ConversationReach.Session(session.id)
            options.effectiveMemoryScope == ConversationScope.AGENT -> ConversationReach.Agent(agentId)
            else -> ConversationReach.SharedAgents(agentId, privateAgents(agentId))
        }
        return indexedHistorySessionCount(reach) > 0
    }

    override suspend fun documentLocation(sessionId: String, reference: RagReference): KnowledgeDocumentLocation? = withContext(Dispatchers.IO) {
        val documentId = reference.documentId?.takeIf(String::isNotBlank) ?: return@withContext null
        val fileUuid = database.query(SimpleSQLiteQuery(
            "SELECT file_uuid FROM vectors WHERE doc_id = ? AND file_uuid IS NOT NULL AND stale = 0 LIMIT 1",
            arrayOf(documentId),
        )).use { if (it.moveToFirst()) it.getString(0) else documentId }
        val file = database.fileEntryDao().findActiveByUuid(fileUuid)
        val title = file?.name ?: reference.source.removePrefix("文档: ").ifBlank { documentId }
        if (file == null || file.isDirectory) return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.SNIPPET_ONLY)
        val relativePath = runCatching { ExternalFolderPaths.normalize(file.materializedPath) }.getOrNull()
        if (relativePath == null || relativePath == "/") return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.SNIPPET_ONLY)
        val session = database.sessionDao().getById(sessionId)
        if (session != null && file.workspaceRootUuid == session.workspaceRootUuid) {
            val path = workspaceFilePathPresenter(SessionRunMode.fromStored(session.runMode)).present(
                WorkspaceDefaultFileDestination.SESSION_PRIVATE,
                relativePath,
                privatePrimary = session.workspaceId == null && session.worktreeDirectory == null,
            )
            return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.WORKSPACE, file.workspaceRootUuid, path)
        }
        val knowledgeBase = roots().firstOrNull { it.uuid == file.workspaceRootUuid }
            ?: return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.SNIPPET_ONLY, file.workspaceRootUuid)
        val mount = externalAccess.visible(sessionId).firstOrNull {
            it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE && it.sourceId == file.workspaceRootUuid && it.readOnly && it.unavailableReason == null
        }
        KnowledgeDocumentLocation(
            documentId, title,
            if (mount == null) KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED else KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED,
            file.workspaceRootUuid,
            mount?.let { ExternalMountPaths.virtualPath(it, relativePath) },
            knowledgeBase.title,
        )
    }

    private fun indexedDocuments(reach: DocumentReach): List<IndexedDocument> {
        val condition = documentCondition(reach)
        return database.query(SimpleSQLiteQuery(
            "SELECT MIN(v.doc_id), f.workspace_root_uuid, f.name, MAX(COALESCE(f.updated_at, v.updated_at, v.created_at)), v.metadata " +
                "FROM vectors v LEFT JOIN workspace_files f ON f.uuid = COALESCE(v.file_uuid, v.doc_id) " +
                "WHERE $ACTIVE_DOCUMENT AND ${condition.sql} GROUP BY COALESCE(f.uuid, v.doc_id)",
            condition.args.toTypedArray(),
        )).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                add(IndexedDocument(id, cursor.getString(1), cursor.getString(2) ?: documentTitleFromVectorMetadata(cursor.getString(4)) ?: id, cursor.getLong(3)))
            }
        } }
    }

    private fun indexedDocumentsExist(reach: DocumentReach): Boolean {
        val condition = documentCondition(reach)
        return database.query(SimpleSQLiteQuery(
            "SELECT 1 FROM vectors v LEFT JOIN workspace_files f ON f.uuid = COALESCE(v.file_uuid, v.doc_id) WHERE $ACTIVE_DOCUMENT AND ${condition.sql} LIMIT 1",
            condition.args.toTypedArray(),
        )).use { it.moveToFirst() }
    }

    private fun documentCondition(reach: DocumentReach): SqlCondition = when (reach) {
        DocumentReach.All -> SqlCondition("1")
        is DocumentReach.Scoped -> {
            val roots = reach.workspaceRootUuids.toList()
            val docs = reach.documentIds.toList()
            val conditions = buildList {
                if (roots.isNotEmpty()) add("(f.in_recycle_bin = 0 AND f.workspace_root_uuid IN (${placeholders(roots.size)}))")
                if (docs.isNotEmpty()) add("v.doc_id IN (${placeholders(docs.size)})")
            }
            SqlCondition(if (conditions.isEmpty()) "0" else conditions.joinToString(" OR ", "(", ")"), roots + docs)
        }
    }

    /** 同步广告入口复用解析器的根、显式文件与文件夹展开规则。 */
    private fun synchronousDocumentReach(session: Session, options: RagOptions): DocumentReach {
        if (options.effectiveDocumentScope == DocumentScope.ALL_INDEXED) return DocumentReach.All
        val rootIds = linkedSetOf<String>()
        database.query(SimpleSQLiteQuery("SELECT workspace_root_uuid FROM sessions WHERE id = ?", arrayOf(session.id))).use {
            if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank)?.let(rootIds::add)
        }
        if (options.effectiveDocumentScope == DocumentScope.KNOWLEDGE_BASE) rootIds += roots().map { it.uuid }
        val docs = options.activeDocIds.filterTo(linkedSetOf(), String::isNotBlank)
        val folders = options.activeFolderIds.filter(String::isNotBlank).distinct().take(RetrievalScopeResolver.MAX_SCOPED_DOCUMENTS)
        if (folders.isNotEmpty()) database.query(SimpleSQLiteQuery(
            "WITH RECURSIVE tree(id, root) AS (" +
                "SELECT uuid, workspace_root_uuid FROM workspace_files WHERE uuid IN (${placeholders(folders.size)}) AND in_recycle_bin = 0 " +
                "UNION SELECT w.uuid, w.workspace_root_uuid FROM workspace_files w JOIN tree t ON w.parent_uuid = t.id AND w.workspace_root_uuid = t.root WHERE w.in_recycle_bin = 0) " +
                "SELECT w.uuid FROM workspace_files w JOIN tree t ON w.uuid = t.id WHERE w.is_directory = 0 LIMIT ${RetrievalScopeResolver.MAX_SCOPED_DOCUMENTS}",
            folders.toTypedArray(),
        )).use { while (it.moveToNext()) docs += it.getString(0) }
        return DocumentReach.Scoped(rootIds, docs.take(RetrievalScopeResolver.MAX_SCOPED_DOCUMENTS).toSet())
    }

    private fun indexedHistorySessionCount(reach: ConversationReach): Int {
        val condition = when (reach) {
            is ConversationReach.Session -> SqlCondition("s.id = ?", listOf(reach.sessionId))
            is ConversationReach.Agent -> SqlCondition("s.agent_id = ?", listOf(reach.agentId))
            is ConversationReach.SharedAgents -> if (reach.privateAgentIds.isEmpty()) SqlCondition("1") else
                SqlCondition("(s.agent_id = ? OR s.agent_id NOT IN (${placeholders(reach.privateAgentIds.size)}))", listOf(reach.agentId) + reach.privateAgentIds)
        }
        return database.query(SimpleSQLiteQuery(
            "SELECT COUNT(DISTINCT s.id) FROM vectors v JOIN sessions s ON s.id = v.session_id " +
                "LEFT JOIN workspace_files f ON f.uuid = COALESCE(v.file_uuid, v.doc_id) " +
                "WHERE v.doc_id IS NULL AND v.stale = 0 AND $ACTIVE_FILE AND $HISTORY_TYPE AND $HISTORY_TURN_PRESENT AND ${condition.sql}",
            condition.args.toTypedArray(),
        )).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun sessionAgentId(sessionId: String): String? = database.query(SimpleSQLiteQuery(
        "SELECT agent_id FROM sessions WHERE id = ?", arrayOf(sessionId),
    )).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun privateAgents(currentAgent: String): Set<String> = database.query(SimpleSQLiteQuery(
        "SELECT id, retrieval_config FROM agents WHERE id != ?", arrayOf(currentAgent),
    )).use { cursor -> buildSet {
        while (cursor.moveToNext()) {
            val shared = runCatching { Converters.toRetrievalConfig(cursor.getString(1))?.memoryPolicy?.shareWithOtherAgents != false }.getOrDefault(false)
            if (!shared) add(cursor.getString(0))
        }
    } }

    private fun roots(): List<Root> = database.query(SimpleSQLiteQuery(
        "SELECT workspace_root_uuid, title, id FROM sessions WHERE agent_id = ? AND workspace_root_uuid IS NOT NULL AND workspace_root_uuid != '' " +
            "ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, title, id",
        arrayOf(RetrievalScopeResolver.KNOWLEDGE_BASE_AGENT_ID, DEFAULT_KNOWLEDGE_SESSION),
    )).use { cursor -> buildList {
        val seen = hashSetOf<String>()
        while (cursor.moveToNext()) {
            val uuid = cursor.getString(0)
            if (seen.add(uuid)) add(Root(uuid, cursor.getString(1), cursor.getString(2) == DEFAULT_KNOWLEDGE_SESSION))
        }
    } }

    private fun rootNames(): Map<String, String> = database.query(SimpleSQLiteQuery(
        "SELECT s.workspace_root_uuid, COALESCE(w.display_name, root.name, '会话工作区') FROM sessions s " +
            "LEFT JOIN workspaces w ON w.id = s.workspace_id " +
            "LEFT JOIN workspace_files root ON root.uuid = s.workspace_root_uuid AND root.in_recycle_bin = 0 " +
            "WHERE s.workspace_root_uuid IS NOT NULL ORDER BY s.updated_at DESC, s.id",
    )).use { cursor -> buildMap {
        while (cursor.moveToNext()) {
            val uuid = cursor.getString(0)
            if (uuid !in this) put(uuid, cursor.getString(1))
        }
    } }

    private data class Root(val uuid: String, val title: String, val isDefault: Boolean)
    private data class IndexedDocument(val id: String, val rootUuid: String?, val title: String, val updatedAt: Long)
    private data class SqlCondition(val sql: String, val args: List<String> = emptyList())

    private companion object {
        // 与 RagWorkspaceProvisioner 的默认知识库会话身份一致。
        const val DEFAULT_KNOWLEDGE_SESSION = "__nexara_rag_workspace__"
        const val ACTIVE_FILE = "((v.file_uuid IS NOT NULL AND f.uuid IS NOT NULL AND f.in_recycle_bin = 0) OR " +
            "(v.file_uuid IS NULL AND (f.uuid IS NULL OR f.in_recycle_bin = 0)))"
        const val ACTIVE_DOCUMENT = "v.doc_id IS NOT NULL AND v.stale = 0 AND $ACTIVE_FILE"
        const val HISTORY_TYPE = "(substr(COALESCE(v.metadata, ''), 1, 16) = '{\"type\":\"memory\"' OR " +
            "substr(COALESCE(v.metadata, ''), 1, 17) = '{\"type\":\"summary\"' OR substr(COALESCE(v.metadata, ''), 1, 15) = '{\"type\":\"state\"')"
        const val HISTORY_TURN_PRESENT = "((v.start_message_id IS NULL OR EXISTS (SELECT 1 FROM messages m WHERE m.id = v.start_message_id)) " +
            "AND (v.end_message_id IS NULL OR EXISTS (SELECT 1 FROM messages m WHERE m.id = v.end_message_id)))"
        fun placeholders(size: Int): String = List(size) { "?" }.joinToString()
    }
}
