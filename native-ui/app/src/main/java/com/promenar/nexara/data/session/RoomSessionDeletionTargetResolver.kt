package com.promenar.nexara.data.session

import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.repository.SecureWorkspaceFileOps
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** 将 Session 对工作区的数据库认领解析为唯一、可验证的物理根。 */
class RoomSessionDeletionTargetResolver(
    private val database: NexaraDatabase,
    private val workspaceParent: Path,
    private val verifyIdentity: (Path, String) -> Unit = { path, identity ->
        SecureWorkspaceFileOps().ensureRoot(path, initializeIdentity = false, expectedIdentity = identity)
        Unit
    },
) {
    suspend fun resolve(sessionId: String): SessionDeletionTarget? {
        val session = database.sessionDao().getById(sessionId) ?: return null
        val rootUuid = session.workspaceRootUuid?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("会话缺少工作区根 UUID")
        val root = database.fileEntryDao().getAnyStateByUuidForLifecycle(rootUuid, rootUuid)
            ?: throw SecurityException("会话工作区根记录不存在")
        check(root.uuid == root.workspaceRootUuid && root.parentUuid == null && root.isDirectory &&
            root.materializedPath == "/" && !root.inRecycleBin) { "会话工作区根记录无效" }
        val declared = session.workspacePath?.takeIf { it.isNotBlank() }
            ?: throw SecurityException("会话缺少工作区路径")
        // 审计缺陷 P1-10：DB 存的路径可能是 /data/data/<pkg>（applicationInfo.dataDir），
        // 而运行时 filesDir 是 /data/user/0/<pkg>（同一目录的 symlink 别名），字符串前缀比对会误判越界。
        // 先拒绝显式符号链接，再对两侧做 canonical 归一后比对（真越界的符号链接归一后仍会落在父目录外）。
        val declaredFile = File(declared).absoluteFile
        if (Files.isSymbolicLink(declaredFile.toPath())) {
            throw SecurityException("会话工作区根为符号链接")
        }
        val path = declaredFile.canonicalFile.toPath().normalize()
        val expectedParent = workspaceParent.toFile().canonicalFile.toPath().normalize()
        if (path.parent != expectedParent || !Files.isDirectory(path)) {
            throw SecurityException("会话工作区根不在应用专用父目录或类型无效")
        }
        if (File(root.physicalRootPath).canonicalFile.toPath().normalize() != path) {
            throw SecurityException("会话与根记录的物理路径不一致")
        }
        if (database.fileEntryDao().countOtherSessionsForRoot(rootUuid, sessionId) != 0 ||
            database.fileEntryDao().countOtherSessionsForPhysicalRoot(path.toFile().canonicalPath, sessionId) != 0) {
            throw SecurityException("工作区根并非当前会话唯一所有")
        }
        val identity = root.hash.takeIf { it.isNotBlank() }
            ?: throw SecurityException("工作区根缺少身份")
        verifyIdentity(path, identity)
        val files = database.fileEntryDao().getAllStatesByWorkspaceRootForCleanup(rootUuid)
        check(files.count { it.uuid == rootUuid } == 1) { "工作区根记录不唯一" }
        validateVersionPaths(path, rootUuid)
        return SessionDeletionTarget(
            sessionId = sessionId,
            workspaceRootUuid = rootUuid,
            physicalRoot = path,
            rootIdentity = identity,
            fileUuids = files.map { it.uuid }.distinct(),
        )
    }

    private suspend fun validateVersionPaths(root: Path, rootUuid: String) {
        val allowed = root.resolve(".nexara_versions").resolve(rootUuid).normalize()
        database.fileVersionDao().getByRoot(rootUuid).forEach { version ->
            // 版本快照路径同样可能以 /data/data 别名形式落库，canonical 归一后再比对
            val snapshot = File(version.contentPath).canonicalFile.toPath().normalize()
            if (!snapshot.startsWith(allowed)) {
                throw SecurityException("版本快照路径越出当前工作区")
            }
        }
    }
}
