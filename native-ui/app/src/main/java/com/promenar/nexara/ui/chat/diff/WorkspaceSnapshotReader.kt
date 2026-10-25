package com.promenar.nexara.ui.chat.diff

import com.promenar.nexara.data.repository.WorkspaceFileTooLargeException
import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.workspace.SessionPrivateWorkspacePaths
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.manager.skills.PrivateWorkspaceTransferBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次读取的结果；[sha256] 为原始字节的哈希，用于核对写入前快照是否就是被覆盖的版本。 */
data class CapturedSnapshot(val snapshot: FileSnapshot, val sha256: String? = null)

/** 读取会话私有工作区物理根中的文件字节；默认走与跨工作区传输相同的安全读取路径。 */
fun interface PrivateWorkspaceReader {
    suspend fun read(workspace: IWorkspaceRepository, rootUuid: String, path: String, maxBytes: Long): ByteArray

    companion object {
        val Default = PrivateWorkspaceReader { workspace, rootUuid, path, maxBytes ->
            PrivateWorkspaceTransferBackend(workspace, rootUuid).read(path, maxBytes)
        }
    }
}

/**
 * 按工具面向模型的虚拟路径读取文件内容快照，只读、不改变工作区状态。
 * - [rooted] 为真（会话绑定自选目录工作区）或路径位于 `/mnt/…`、`/skills` 时经 [mounts] 的目录后端读取；
 * - 否则读取会话私有工作区（Room 树），定位用 [namespace]、读取用 [privateWorkspace] 的物理根。
 *
 * [namespace] 应与执行工具时使用的仓库一致（挂载时为带挂载命名空间的仓库），保证路径解析与工具相同。
 * 所有读取都在 [Dispatchers.IO]；任何失败都转为 [FileSnapshot.Unavailable]，不会向工具执行抛出异常（取消除外）。
 */
class WorkspaceSnapshotReader(
    private val namespace: IWorkspaceRepository,
    private val privateWorkspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess?,
    private val sessionId: String,
    private val workspaceRootUuid: String,
    private val rooted: Boolean,
    private val maxBytes: Long = FileChangeSummaries.SNAPSHOT_MAX_BYTES,
    private val privateReader: PrivateWorkspaceReader = PrivateWorkspaceReader.Default,
) {
    private fun external(path: String) = rooted || ExternalMountPaths.isMountedPath(path)

    suspend fun read(path: String): CapturedSnapshot = withContext(Dispatchers.IO) {
        try {
            val entry = namespace.getByMaterializedPath(workspaceRootUuid, path)
                ?: return@withContext CapturedSnapshot(FileSnapshot.Missing)
            if (entry.isDirectory) return@withContext CapturedSnapshot(FileSnapshot.Directory)
            if (entry.sizeBytes > maxBytes) return@withContext CapturedSnapshot(FileSnapshot.TooLarge)
            val privateRelative = SessionPrivateWorkspacePaths.relative(path)
            val bytes = if (privateRelative != null) {
                val root = privateWorkspace.ensureSessionRoot(sessionId)
                privateReader.read(privateWorkspace, root.uuid, privateRelative, maxBytes)
            } else if (external(path)) {
                (mounts ?: return@withContext CapturedSnapshot(FileSnapshot.Unavailable)).read(sessionId, path, maxBytes)
            } else {
                privateReader.read(privateWorkspace, workspaceRootUuid, path, maxBytes)
            }
            CapturedSnapshot(FileChangeSummaries.decode(bytes), Sha256Utils.hash(bytes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ExternalFolderException) {
            CapturedSnapshot(if (failure.code == "TOO_LARGE") FileSnapshot.TooLarge else FileSnapshot.Unavailable)
        } catch (_: WorkspaceFileTooLargeException) {
            CapturedSnapshot(FileSnapshot.TooLarge)
        } catch (_: Exception) {
            CapturedSnapshot(FileSnapshot.Unavailable)
        }
    }

    /** 条目是否为目录；不存在或读取失败时为空。 */
    suspend fun isDirectory(path: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            namespace.getByMaterializedPath(workspaceRootUuid, path)?.isDirectory
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** 按 uuid 解析虚拟路径；解析失败时为空。 */
    suspend fun pathOf(uuid: String): String? = withContext(Dispatchers.IO) {
        try {
            namespace.getByUuid(workspaceRootUuid, uuid)?.materializedPath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
