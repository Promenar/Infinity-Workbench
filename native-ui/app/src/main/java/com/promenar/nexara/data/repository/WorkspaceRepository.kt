package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.WorkspaceSeqDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File

class WorkspaceRepository(
    private val dao: FileEntryDao,
    private val seqDao: WorkspaceSeqDao
) : IWorkspaceRepository {

    override fun observeRoots(): Flow<List<FileEntry>> = dao.observeRoots()

    override fun observeChildren(parentUuid: String): Flow<List<FileEntry>> =
        dao.observeChildren(parentUuid)

    override fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>> {
        return flow {
            val rootEntry = dao.getByUuid(workspaceRootUuid)
            if (rootEntry == null) {
                emit(emptyList())
            } else {
                emitAll(dao.observeRecycleBin(rootEntry.physicalRootPath))
            }
        }
    }

    override suspend fun getByUuid(uuid: String): FileEntry? = dao.getByUuid(uuid)

    override suspend fun createFile(
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        physicalRootPath: String,
        materializedPath: String
    ): FileEntry = withContext(Dispatchers.IO) {
        val hash = Sha256Utils.hash(content)
        val physicalFile = File(physicalRootPath, materializedPath)
        physicalFile.parentFile?.mkdirs()
        physicalFile.writeText(content)
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            parentUuid = parentUuid,
            name = name,
            hash = hash,
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            isDirectory = false,
            physicalRootPath = physicalRootPath,
            materializedPath = materializedPath,
            createdAt = now,
            updatedAt = now
        )
        dao.insert(entry)
        entry
    }

    override suspend fun createDirectory(
        uuid: String,
        name: String,
        parentUuid: String?,
        physicalRootPath: String,
        materializedPath: String
    ): FileEntry = withContext(Dispatchers.IO) {
        val physicalDir = File(physicalRootPath, materializedPath)
        physicalDir.mkdirs()
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            parentUuid = parentUuid,
            name = name,
            hash = "",
            isDirectory = true,
            physicalRootPath = physicalRootPath,
            materializedPath = materializedPath,
            createdAt = now,
            updatedAt = now
        )
        dao.insert(entry)
        entry
    }

    override suspend fun moveToRecycleBin(uuid: String) = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(uuid) ?: return@withContext
        if (entry.inRecycleBin) return@withContext

        val originalMatPath = entry.materializedPath
        val recycleMatPath = buildRecycleBinPath(originalMatPath)

        val srcFile = File(entry.physicalRootPath, originalMatPath)
        val dstFile = File(entry.physicalRootPath, recycleMatPath)
        dstFile.parentFile?.mkdirs()
        srcFile.renameTo(dstFile)

        val recycleBinDirUuid = resolveOrCreateRecycleBinDir(entry)
        val now = System.currentTimeMillis()

        dao.update(
            entry.copy(
                inRecycleBin = true,
                recycledAt = now,
                originalParentUuid = entry.parentUuid,
                originalMaterializedPath = originalMatPath,
                parentUuid = recycleBinDirUuid,
                materializedPath = recycleMatPath,
                updatedAt = now
            )
        )

        if (entry.isDirectory) {
            moveSubtreeToRecycleBin(entry, originalMatPath, recycleMatPath, now)
        }
    }

    override suspend fun restoreFromRecycleBin(uuid: String) = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(uuid) ?: return@withContext
        if (!entry.inRecycleBin) return@withContext

        val originalMatPath = entry.originalMaterializedPath ?: return@withContext
        val originalParentUuid = entry.originalParentUuid

        val srcFile = File(entry.physicalRootPath, entry.materializedPath)
        val dstFile = File(entry.physicalRootPath, originalMatPath)
        dstFile.parentFile?.mkdirs()
        srcFile.renameTo(dstFile)

        val now = System.currentTimeMillis()
        dao.update(
            entry.copy(
                inRecycleBin = false,
                recycledAt = null,
                originalParentUuid = null,
                originalMaterializedPath = null,
                parentUuid = originalParentUuid,
                materializedPath = originalMatPath,
                updatedAt = now
            )
        )

        if (entry.isDirectory) {
            restoreSubtreeFromRecycleBin(entry, originalMatPath, now)
        }
    }

    override suspend fun permanentDelete(uuid: String) = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(uuid) ?: return@withContext

        if (entry.isDirectory) {
            val subtree = dao.getSubtree(entry.materializedPath)
            subtree.filter { it.uuid != uuid }.forEach { child ->
                val childFile = File(child.physicalRootPath, child.materializedPath)
                if (childFile.exists()) {
                    if (child.isDirectory) childFile.deleteRecursively()
                    else childFile.delete()
                }
                dao.deleteByUuid(child.uuid)
            }
        }

        val physicalFile = File(entry.physicalRootPath, entry.materializedPath)
        if (physicalFile.exists()) {
            if (entry.isDirectory) physicalFile.deleteRecursively()
            else physicalFile.delete()
        }
        dao.deleteByUuid(uuid)
    }

    override suspend fun emptyRecycleBin(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        val rootEntry = dao.getByUuid(workspaceRootUuid) ?: return@withContext
        val recycleBinEntries = dao.observeRecycleBin(rootEntry.physicalRootPath).first()
        recycleBinEntries.forEach { entry ->
            permanentDelete(entry.uuid)
        }
    }

    override suspend fun updateParent(uuid: String, newParentUuid: String) =
        withContext(Dispatchers.IO) {
            val entry = dao.getByUuid(uuid) ?: return@withContext

            val newParent = dao.getByUuid(newParentUuid)
            val newMatPath = if (newParent != null) {
                "${newParent.materializedPath}/${entry.name}"
            } else {
                "/${entry.name}"
            }

            val srcFile = File(entry.physicalRootPath, entry.materializedPath)
            val dstFile = File(entry.physicalRootPath, newMatPath)
            dstFile.parentFile?.mkdirs()
            srcFile.renameTo(dstFile)

            val now = System.currentTimeMillis()
            dao.update(
                entry.copy(
                    parentUuid = newParentUuid,
                    materializedPath = newMatPath,
                    updatedAt = now
                )
            )

            if (entry.isDirectory) {
                val oldPrefix = entry.materializedPath
                val subtree = dao.getSubtree(oldPrefix)
                subtree.forEach { child ->
                    val childRelativePath = child.materializedPath.removePrefix(oldPrefix)
                    val childNewMatPath = "$newMatPath$childRelativePath"

                    val childSrcFile = File(child.physicalRootPath, child.materializedPath)
                    val childDstFile = File(child.physicalRootPath, childNewMatPath)
                    childDstFile.parentFile?.mkdirs()
                    childSrcFile.renameTo(childDstFile)

                    dao.update(
                        child.copy(
                            materializedPath = childNewMatPath,
                            updatedAt = now
                        )
                    )
                }
            }
        }

    override suspend fun getNextSeqForDate(dateKey: String): Int =
        seqDao.getNextSeqForDate(dateKey)

    private suspend fun resolveOrCreateRecycleBinDir(entry: FileEntry): String {
        val recycleMatPath = "/.recycle_bin"
        val existing = dao.getByMaterializedPath(entry.physicalRootPath + recycleMatPath)
        if (existing != null) return existing.uuid

        val dirUuid = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dir = File(entry.physicalRootPath, recycleMatPath)
        dir.mkdirs()
        dao.insert(
            FileEntry(
                uuid = dirUuid,
                parentUuid = null,
                name = ".recycle_bin",
                hash = "",
                isDirectory = true,
                physicalRootPath = entry.physicalRootPath,
                materializedPath = recycleMatPath,
                createdAt = now,
                updatedAt = now
            )
        )
        return dirUuid
    }

    private fun buildRecycleBinPath(originalMatPath: String): String {
        val matPath = if (originalMatPath.startsWith("/")) originalMatPath else "/$originalMatPath"
        return "/.recycle_bin$matPath"
    }

    private suspend fun moveSubtreeToRecycleBin(
        parentEntry: FileEntry,
        oldPrefix: String,
        newPrefix: String,
        now: Long
    ) {
        val subtree = dao.getSubtree(oldPrefix)
        subtree.forEach { child ->
            val childRelativePath = child.materializedPath.removePrefix(oldPrefix)
            val childNewMatPath = "$newPrefix$childRelativePath"

            val srcFile = File(child.physicalRootPath, child.materializedPath)
            val dstFile = File(child.physicalRootPath, childNewMatPath)
            dstFile.parentFile?.mkdirs()
            srcFile.renameTo(dstFile)

            dao.update(
                child.copy(
                    inRecycleBin = true,
                    recycledAt = now,
                    originalParentUuid = child.parentUuid,
                    originalMaterializedPath = child.materializedPath,
                    materializedPath = childNewMatPath,
                    updatedAt = now
                )
            )
        }
    }

    private suspend fun restoreSubtreeFromRecycleBin(
        parentEntry: FileEntry,
        restoredPrefix: String,
        now: Long
    ) {
        val subtree = dao.getSubtree(parentEntry.materializedPath)
        subtree.forEach { child ->
            val original = child.originalMaterializedPath ?: return@forEach
            val srcFile = File(child.physicalRootPath, child.materializedPath)
            val dstFile = File(child.physicalRootPath, original)
            dstFile.parentFile?.mkdirs()
            srcFile.renameTo(dstFile)

            dao.update(
                child.copy(
                    inRecycleBin = false,
                    recycledAt = null,
                    originalParentUuid = null,
                    originalMaterializedPath = null,
                    materializedPath = original,
                    updatedAt = now
                )
            )
        }
    }
}
