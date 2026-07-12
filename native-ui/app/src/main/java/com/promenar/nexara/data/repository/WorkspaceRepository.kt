package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.WorkspaceSeqDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

class WorkspaceRepository(
    private val dao: FileEntryDao,
    private val seqDao: WorkspaceSeqDao,
    private val defaultWorkspaceParent: File? = null,
) : IWorkspaceRepository {

    override suspend fun ensureSessionRoot(sessionId: String): FileEntry = withContext(Dispatchers.IO) {
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val path = session.workspacePath?.takeIf { it.isNotBlank() }
            ?: defaultWorkspaceParent?.let { parent ->
                File(parent, Sha256Utils.hash(sessionId)).path
            }
            ?: throw IllegalStateException("Session workspace path is missing: $sessionId")
        ensureSessionRootInternal(sessionId, path)
    }

    override suspend fun ensureSessionRoot(
        sessionId: String,
        physicalRootPath: String,
    ): FileEntry = withContext(Dispatchers.IO) {
        ensureSessionRootInternal(sessionId, physicalRootPath)
    }

    private suspend fun ensureSessionRootInternal(sessionId: String, physicalRootPath: String): FileEntry {
        val physicalRoot = File(physicalRootPath).canonicalFile
        if (!physicalRoot.mkdirs() && !physicalRoot.isDirectory) {
            throw IllegalStateException("无法创建 Session 工作区: ${physicalRoot.path}")
        }
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        session.workspacePath?.takeIf { it.isNotBlank() }?.let { declaredPath ->
            if (File(declaredPath).canonicalFile != physicalRoot) {
                throw SecurityException("Session workspace path cannot be changed")
            }
        }
        session.workspaceRootUuid?.let { existingUuid ->
            if (dao.countOtherSessionsForRoot(existingUuid, sessionId) != 0) {
                throw SecurityException("工作区根被多个 Session 共享")
            }
            val existing = dao.getByUuid(existingUuid, existingUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            if (File(existing.physicalRootPath).canonicalFile != physicalRoot) {
                throw SecurityException("Session workspace root path cannot be changed")
            }
            return existing
        }

        val rootUuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        return dao.ensureSessionRoot(
            candidate = FileEntry(
                uuid = rootUuid,
                workspaceRootUuid = rootUuid,
                parentUuid = null,
                name = physicalRoot.name.ifBlank { "workspace" },
                hash = "",
                isDirectory = true,
                physicalRootPath = physicalRoot.path,
                materializedPath = "/",
                createdAt = now,
                updatedAt = now,
            ),
            sessionId = sessionId,
        ).also { claimed ->
            if (File(claimed.physicalRootPath).canonicalFile != physicalRoot) {
                throw SecurityException("Concurrent workspace root path mismatch")
            }
        }
    }

    override fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRoots(workspaceRootUuid)

    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank() || parentUuid.isBlank()) emptyFlow()
        else dao.observeChildren(workspaceRootUuid, parentUuid)

    override fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRecycleBin(workspaceRootUuid)

    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? =
        if (workspaceRootUuid.isBlank() || uuid.isBlank()) null else dao.getByUuid(workspaceRootUuid, uuid)

    override suspend fun getByMaterializedPath(
        workspaceRootUuid: String,
        materializedPath: String,
    ): FileEntry? = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        dao.getByMaterializedPath(workspaceRootUuid, validateMaterializedPath(root, materializedPath))
    }

    override suspend fun getSubtree(
        workspaceRootUuid: String,
        materializedPathPrefix: String,
        inRecycleBin: Boolean,
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        dao.getSubtree(
            workspaceRootUuid,
            validateMaterializedPath(root, materializedPathPrefix),
            inRecycleBin,
        )
    }

    override fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.searchByName(workspaceRootUuid, query)

    override suspend fun createFileInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("文件名与路径不一致")
        val physicalFile = resolveRootedFile(root.physicalRootPath, normalizedPath)
        if (dao.getByRootAndMaterializedPath(workspaceRootUuid, normalizedPath) != null || physicalFile.exists()) {
            throw IllegalStateException("工作区文件已存在: $normalizedPath")
        }
        physicalFile.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) error("无法创建工作区目录") }
        physicalFile.writeText(content)
        val now = System.currentTimeMillis()
        FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = Sha256Utils.hash(content),
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        ).also { entry ->
            try {
                dao.insertAbort(entry)
            } catch (failure: Throwable) {
                physicalFile.delete()
                throw failure
            }
        }
    }

    override suspend fun createDirectoryInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("目录名与路径不一致")
        val physicalDir = resolveRootedFile(root.physicalRootPath, normalizedPath)
        if (dao.getByRootAndMaterializedPath(workspaceRootUuid, normalizedPath) != null || physicalDir.exists()) {
            throw IllegalStateException("工作区目录已存在: $normalizedPath")
        }
        if (!physicalDir.mkdirs() && !physicalDir.isDirectory) error("无法创建工作区目录: $normalizedPath")
        val now = System.currentTimeMillis()
        FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = "",
            isDirectory = true,
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        ).also { entry ->
            try {
                dao.insertAbort(entry)
            } catch (failure: Throwable) {
                physicalDir.delete()
                throw failure
            }
        }
    }

    override suspend fun moveToRecycleBin(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withContext
        if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可回收")
        if (entry.inRecycleBin) return@withContext
        val originalPath = entry.materializedPath
        val recyclePath = "/.recycle_bin${normalizeMaterializedPath(originalPath)}"
        moveFileOrThrow(
            resolveRootedFile(entry.physicalRootPath, originalPath),
            resolveRootedFile(entry.physicalRootPath, recyclePath),
        )
        val recycleParent = resolveOrCreateRecycleBinDir(entry)
        val now = System.currentTimeMillis()
        dao.update(entry.copy(
            inRecycleBin = true,
            recycledAt = now,
            originalParentUuid = entry.parentUuid,
            originalMaterializedPath = originalPath,
            parentUuid = recycleParent,
            materializedPath = recyclePath,
            updatedAt = now,
        ))
        if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, originalPath, false)
                .filterNot { it.uuid == uuid }
                .forEach { child ->
                    val childPath = recyclePath + child.materializedPath.removePrefix(originalPath)
                    dao.update(child.copy(
                        inRecycleBin = true,
                        recycledAt = now,
                        originalParentUuid = child.parentUuid,
                        originalMaterializedPath = child.materializedPath,
                        materializedPath = childPath,
                        updatedAt = now,
                    ))
                }
        }
    }

    override suspend fun restoreFromRecycleBin(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withContext
        if (!entry.inRecycleBin) return@withContext
        val originalPath = entry.originalMaterializedPath ?: return@withContext
        val originalParent = entry.originalParentUuid
        if (originalParent != null && dao.getByUuid(workspaceRootUuid, originalParent) == null) {
            throw SecurityException("原父目录不属于当前工作区")
        }
        moveFileOrThrow(
            resolveRootedFile(entry.physicalRootPath, entry.materializedPath),
            resolveRootedFile(entry.physicalRootPath, originalPath),
        )
        val now = System.currentTimeMillis()
        dao.update(entry.copy(
            inRecycleBin = false,
            recycledAt = null,
            originalParentUuid = null,
            originalMaterializedPath = null,
            parentUuid = originalParent,
            materializedPath = originalPath,
            updatedAt = now,
        ))
        if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, true)
                .filterNot { it.uuid == uuid }
                .forEach { child ->
                    val childOriginal = child.originalMaterializedPath ?: return@forEach
                    dao.update(child.copy(
                        inRecycleBin = false,
                        recycledAt = null,
                        originalParentUuid = null,
                        originalMaterializedPath = null,
                        materializedPath = childOriginal,
                        updatedAt = now,
                    ))
                }
        }
    }

    override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) {
        val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withContext
        if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可删除")
        val children = if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, entry.inRecycleBin)
                .filterNot { it.uuid == uuid }
                .sortedByDescending { it.materializedPath.length }
        } else emptyList()
        deleteIfExistsOrThrow(entry, resolveRootedFile(entry.physicalRootPath, entry.materializedPath))
        children.forEach { child -> dao.deleteByUuid(workspaceRootUuid, child.uuid) }
        dao.deleteByUuid(workspaceRootUuid, uuid)
    }

    override suspend fun emptyRecycleBin(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        dao.observeRecycleBin(workspaceRootUuid).first()
            .filter { it.parentUuid == null || it.parentUuid == workspaceRootUuid || it.name != ".recycle_bin" }
            .forEach { permanentDelete(workspaceRootUuid, it.uuid) }
    }

    override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String) =
        withContext(Dispatchers.IO) {
            val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withContext
            val parent = dao.getByUuid(workspaceRootUuid, newParentUuid)
                ?: throw SecurityException("目标父目录不属于当前工作区")
            if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("目标父节点不可用")
            if (entry.uuid == workspaceRootUuid || newParentUuid == uuid ||
                parent.materializedPath.startsWith(entry.materializedPath.trimEnd('/') + "/")) {
                throw SecurityException("不允许形成循环目录关系")
            }
            val newPath = joinMaterializedPath(parent.materializedPath, entry.name)
            moveFileOrThrow(
                resolveRootedFile(entry.physicalRootPath, entry.materializedPath),
                resolveRootedFile(entry.physicalRootPath, newPath),
            )
            val now = System.currentTimeMillis()
            val oldPrefix = entry.materializedPath
            dao.update(entry.copy(parentUuid = newParentUuid, materializedPath = newPath, updatedAt = now))
            if (entry.isDirectory) {
                dao.getSubtree(workspaceRootUuid, oldPrefix, entry.inRecycleBin)
                    .filterNot { it.uuid == uuid }
                    .forEach { child ->
                        dao.update(child.copy(
                            materializedPath = newPath + child.materializedPath.removePrefix(oldPrefix),
                            updatedAt = now,
                        ))
                    }
            }
        }

    override suspend fun rename(workspaceRootUuid: String, uuid: String, newName: String) =
        withContext(Dispatchers.IO) {
            validateName(newName)
            val entry = dao.getByUuid(workspaceRootUuid, uuid) ?: return@withContext
            if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可重命名")
            val parent = entry.parentUuid?.let { dao.getByUuid(workspaceRootUuid, it) }
                ?: throw SecurityException("父目录不属于当前工作区")
            val newPath = joinMaterializedPath(parent.materializedPath, newName)
            if (dao.getByRootAndMaterializedPath(workspaceRootUuid, newPath) != null) {
                throw IllegalStateException("目标名称已存在")
            }
            moveFileOrThrow(
                resolveRootedFile(entry.physicalRootPath, entry.materializedPath),
                resolveRootedFile(entry.physicalRootPath, newPath),
            )
            val now = System.currentTimeMillis()
            val oldPrefix = entry.materializedPath
            dao.update(entry.copy(name = newName, materializedPath = newPath, updatedAt = now))
            if (entry.isDirectory) {
                dao.getSubtree(workspaceRootUuid, oldPrefix, entry.inRecycleBin)
                    .filterNot { it.uuid == uuid }
                    .forEach { child ->
                        dao.update(child.copy(
                            materializedPath = newPath + child.materializedPath.removePrefix(oldPrefix),
                            updatedAt = now,
                        ))
                    }
            }
        }

    override suspend fun getNextSeqForDate(dateKey: String): Int = seqDao.getNextSeqForDate(dateKey)

    override suspend fun resetAllRAGStatus(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        requireRoot(workspaceRootUuid)
        dao.resetAllRAGStatus(workspaceRootUuid)
    }

    private suspend fun requireRoot(workspaceRootUuid: String): FileEntry {
        if (workspaceRootUuid.isBlank()) throw SecurityException("workspaceRootUuid 不能为空")
        return dao.getByUuid(workspaceRootUuid, workspaceRootUuid)
            ?.takeIf { it.isDirectory && it.parentUuid == null && it.workspaceRootUuid == workspaceRootUuid }
            ?: throw SecurityException("工作区根不存在或无效")
    }

    private suspend fun requireParent(root: FileEntry, parentUuid: String?) {
        val parentId = parentUuid ?: throw SecurityException("非根节点必须指定父目录")
        val parent = dao.getByUuid(root.uuid, parentId)
            ?: throw SecurityException("父目录不属于当前工作区")
        if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("父节点不是可用目录")
    }

    private suspend fun resolveOrCreateRecycleBinDir(entry: FileEntry): String {
        dao.getByRootAndMaterializedPath(entry.workspaceRootUuid, "/.recycle_bin")?.let { return it.uuid }
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dir = resolveRootedFile(entry.physicalRootPath, "/.recycle_bin")
        if (!dir.mkdirs() && !dir.isDirectory) error("无法创建回收站目录")
        dao.insertAbort(FileEntry(
            uuid = uuid,
            workspaceRootUuid = entry.workspaceRootUuid,
            parentUuid = entry.workspaceRootUuid,
            name = ".recycle_bin",
            hash = "",
            isDirectory = true,
            physicalRootPath = entry.physicalRootPath,
            materializedPath = "/.recycle_bin",
            inRecycleBin = true,
            createdAt = now,
            updatedAt = now,
        ))
        return uuid
    }

    private fun validateMaterializedPath(root: FileEntry, path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || '\u0000' in trimmed) throw SecurityException("工作区路径无效")
        val rootPath = File(root.physicalRootPath).canonicalFile.path
        if (trimmed == rootPath || trimmed.startsWith(rootPath + File.separator) ||
            Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed) || trimmed.startsWith("\\\\")) {
            throw SecurityException("禁止使用物理绝对路径")
        }
        val segments = trimmed.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) throw SecurityException("工作区路径越界: $path")
        if (segments.firstOrNull() in setOf(".nexara_versions", ".recycle_bin")) {
            throw SecurityException("工作区路径属于系统保留目录")
        }
        return "/" + segments.joinToString("/")
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
            name in setOf(".nexara_versions", ".recycle_bin")) {
            throw SecurityException("工作区名称无效")
        }
    }

    private fun normalizeMaterializedPath(path: String): String = "/" + path.replace('\\', '/').trim('/')
    private fun resolveRootedFile(rootPath: String, materializedPath: String): File {
        val root = File(rootPath).canonicalFile
        val rootNioPath = root.toPath()
        val rawTargetPath = rootNioPath.resolve(materializedPath.trimStart('/', '\\')).normalize()
        if (rawTargetPath != rootNioPath && !rawTargetPath.startsWith(rootNioPath)) {
            throw SecurityException("工作区路径越界: $materializedPath")
        }
        var cursor = rootNioPath
        rootNioPath.relativize(rawTargetPath).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw SecurityException("工作区路径包含软链接: $materializedPath")
            }
        }
        val target = rawTargetPath.toFile().canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("工作区路径越界: $materializedPath")
        }
        return target
    }

    private fun joinMaterializedPath(parentPath: String, name: String): String =
        normalizeMaterializedPath(parentPath).trimEnd('/') + "/" + name

    private fun moveFileOrThrow(source: File, target: File) {
        if (!source.exists()) error("源文件不存在: ${source.path}")
        if (target.exists()) error("目标路径已存在: ${target.path}")
        target.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) error("无法创建目标目录") }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun deleteIfExistsOrThrow(entry: FileEntry, file: File) {
        if (!file.exists()) return
        val deleted = if (entry.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) error("无法删除工作区文件: ${entry.materializedPath}")
    }

    // Task3B 完成 root 透传前，旧入口全部失败关闭，绝不回退全局查询。
    override fun observeRoots(): Flow<List<FileEntry>> = emptyFlow()
    override fun observeChildren(parentUuid: String): Flow<List<FileEntry>> = emptyFlow()
    override suspend fun getByUuid(uuid: String): FileEntry? = null
    override suspend fun createFile(uuid: String, name: String, content: String, parentUuid: String?, physicalRootPath: String, materializedPath: String): FileEntry =
        throw SecurityException("必须显式指定 workspaceRootUuid")
    override suspend fun createDirectory(uuid: String, name: String, parentUuid: String?, physicalRootPath: String, materializedPath: String): FileEntry =
        throw SecurityException("必须显式指定 workspaceRootUuid")
    override suspend fun moveToRecycleBin(uuid: String) = throw SecurityException("必须显式指定 workspaceRootUuid")
    override suspend fun restoreFromRecycleBin(uuid: String) = throw SecurityException("必须显式指定 workspaceRootUuid")
    override suspend fun permanentDelete(uuid: String) = throw SecurityException("必须显式指定 workspaceRootUuid")
    override suspend fun updateParent(uuid: String, newParentUuid: String) = throw SecurityException("必须显式指定 workspaceRootUuid")
    override suspend fun resetAllRAGStatus() = throw SecurityException("必须显式指定 workspaceRootUuid")
}
