package com.promenar.nexara.data.worker

import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.data.repository.WorkspaceDeletionTransaction
import kotlin.time.Duration.Companion.days

object RecycleBinCleanupWorker {

    data class RecoveryAttention(
        val failedRoots: List<String> = emptyList(),
        val failedTombstones: Map<String, List<String>> = emptyMap(),
    )

    suspend fun recoverPendingDeletions(
        db: NexaraDatabase,
        repositoryFactory: (NexaraDatabase) -> WorkspaceRepository = { database ->
            WorkspaceRepository(
                database.fileEntryDao(),
                database.workspaceSeqDao(),
                deleteCommitter = WorkspaceDeletionTransaction(database)::delete,
            )
        },
    ): RecoveryAttention {
        val dao = db.fileEntryDao()
        val repository = repositoryFactory(db)
        val failedRoots = mutableListOf<String>()
        val failedTombstones = mutableMapOf<String, List<String>>()
        dao.getAllWorkspaceRootsForMaintenance().forEach { root ->
            try {
                val report = repository.cleanupPendingTombstones(root.uuid)
                if (report.attentionTokens.isNotEmpty()) {
                    failedTombstones[root.uuid] = report.attentionTokens
                }
            } catch (_: Exception) {
                failedRoots += root.uuid
            }
        }
        return RecoveryAttention(failedRoots, failedTombstones)
    }

    suspend fun cleanup(db: NexaraDatabase) {
        val dao = db.fileEntryDao()
        val cutoff = System.currentTimeMillis() - 30.days.inWholeMilliseconds

        val repository = WorkspaceRepository(
            dao,
            db.workspaceSeqDao(),
            deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
        )
        val roots = dao.getAllWorkspaceRootsForMaintenance()
        for (root in roots) {
            repository.cleanupStaleRecycleBin(root.uuid, cutoff)
        }
    }
}
