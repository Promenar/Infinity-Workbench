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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        val normalizedPath = normalizeMaterializedPath(materializedPath)
        val physicalFile = resolveRootedFile(physicalRootPath, normalizedPath)
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
            materializedPath = normalizedPath,
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
        val normalizedPath = normalizeMaterializedPath(materializedPath)
        val physicalDir = resolveRootedFile(physicalRootPath, normalizedPath)
        if (!physicalDir.mkdirs() && !physicalDir.isDirectory) {
            throw IllegalStateException("无法创建工作区目录: $normalizedPath")
        }
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            parentUuid = parentUuid,
            name = name,
            hash = "",
            isDirectory = true,
            physicalRootPath = physicalRootPath,
            materializedPath = normalizedPath,
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

        val srcFile = resolveRootedFile(entry.physicalRootPath, originalMatPath)
        val dstFile = resolveRootedFile(entry.physicalRootPath, recycleMatPath)
        moveFileOrThrow(srcFile, dstFile)

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

        val srcFile = resolveRootedFile(entry.physicalRootPath, entry.materializedPath)
        val dstFile = resolveRootedFile(entry.physicalRootPath, originalMatPath)
        moveFileOrThrow(srcFile, dstFile)

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
                val childFile = resolveRootedFile(child.physicalRootPath, child.materializedPath)
                deleteIfExistsOrThrow(child, childFile)
                dao.deleteByUuid(child.uuid)
            }
        }

        val physicalFile = resolveRootedFile(entry.physicalRootPath, entry.materializedPath)
        deleteIfExistsOrThrow(entry, physicalFile)
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
                joinMaterializedPath(newParent.materializedPath, entry.name)
            } else {
                "/${entry.name}"
            }

            val srcFile = resolveRootedFile(entry.physicalRootPath, entry.materializedPath)
            val dstFile = resolveRootedFile(entry.physicalRootPath, newMatPath)
            moveFileOrThrow(srcFile, dstFile)

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
        val existing = dao.getByRootAndMaterializedPath(entry.physicalRootPath, recycleMatPath)
        if (existing != null) return existing.uuid

        val dirUuid = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dir = resolveRootedFile(entry.physicalRootPath, recycleMatPath)
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("无法创建回收站目录")
        }
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

    override suspend fun resetAllRAGStatus() = withContext(Dispatchers.IO) {
        dao.resetAllRAGStatus()
    }

    private fun normalizeMaterializedPath(path: String): String {
        val trimmed = path.trim()
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun resolveRootedFile(rootPath: String, materializedPath: String): File {
        val root = File(rootPath).canonicalFile
        val relativePath = normalizeMaterializedPath(materializedPath)
            .trimStart('/', '\\')
        val target = File(root, relativePath).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("工作区路径越界: $materializedPath")
        }
        return target
    }

    private fun joinMaterializedPath(parentPath: String, name: String): String {
        val cleanParent = normalizeMaterializedPath(parentPath).trimEnd('/')
        return if (cleanParent.isEmpty()) "/$name" else "$cleanParent/$name"
    }

    private fun moveFileOrThrow(srcFile: File, dstFile: File) {
        if (!srcFile.exists()) {
            throw IllegalStateException("源文件不存在: ${srcFile.path}")
        }
        if (dstFile.exists()) {
            throw IllegalStateException("目标路径已存在: ${dstFile.path}")
        }
        dstFile.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw IllegalStateException("无法创建目标目录: ${parent.path}")
            }
        }
        try {
            Files.move(srcFile.toPath(), dstFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(srcFile.toPath(), dstFile.toPath())
        }
    }

    private fun deleteIfExistsOrThrow(entry: FileEntry, physicalFile: File) {
        if (!physicalFile.exists()) return
        val deleted = if (entry.isDirectory) {
            physicalFile.deleteRecursively()
        } else {
            physicalFile.delete()
        }
        if (!deleted) {
            throw IllegalStateException("无法删除工作区文件: ${entry.materializedPath}")
        }
    }
}
