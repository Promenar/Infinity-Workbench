package com.promenar.nexara.data.worker

import com.promenar.nexara.data.local.db.NexaraDatabase
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.time.Duration.Companion.days

object RecycleBinCleanupWorker {

    suspend fun cleanup(db: NexaraDatabase) {
        val dao = db.fileEntryDao()
        val cutoff = System.currentTimeMillis() - 30.days.inWholeMilliseconds

        val roots = dao.observeRoots().first()
        for (root in roots) {
            val recycledFiles = dao.observeRecycleBin(root.physicalRootPath).first()
            val staleFiles = recycledFiles.filter { (it.recycledAt ?: 0) < cutoff }
            for (file in staleFiles) {
                dao.deleteByUuid(file.uuid)
                val physicalFile = File(file.physicalRootPath, file.materializedPath)
                if (physicalFile.exists()) {
                    if (file.isDirectory) physicalFile.deleteRecursively()
                    else physicalFile.delete()
                }
            }
        }
    }
}
