package com.promenar.nexara.data.worker

import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.repository.WorkspaceRepository
import kotlin.time.Duration.Companion.days

object RecycleBinCleanupWorker {

    suspend fun cleanup(db: NexaraDatabase) {
        val dao = db.fileEntryDao()
        val cutoff = System.currentTimeMillis() - 30.days.inWholeMilliseconds

        val repository = WorkspaceRepository(dao, db.workspaceSeqDao())
        val roots = dao.getAllWorkspaceRootsForMaintenance()
        for (root in roots) {
            repository.cleanupStaleRecycleBin(root.uuid, cutoff)
        }
    }
}
