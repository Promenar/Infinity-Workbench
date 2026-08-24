package com.promenar.nexara.data.repository

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadResult
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationStage
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.UUID

data class StagedWorkspaceFileMutation(
    val operationId: String,
    val workspaceRootUuid: String,
    val operationType: WorkspaceMutationType,
)

interface WorkspaceFileMutationJournal {
    suspend fun prepare(
        workspaceRootUuid: String,
        operationType: WorkspaceMutationType,
        sourceRelativePath: String,
        targetRelativePath: String,
        databaseTargetUuid: String,
        rootIdentity: String,
    ): StagedWorkspaceFileMutation

    suspend fun commitDatabase(staged: StagedWorkspaceFileMutation, mutation: suspend () -> Unit)
    suspend fun complete(staged: StagedWorkspaceFileMutation)
    suspend fun abort(staged: StagedWorkspaceFileMutation)
}

class RoomWorkspaceFileMutationJournal(
    private val database: NexaraDatabase,
    private val operationIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : WorkspaceFileMutationJournal {
    override suspend fun prepare(
        workspaceRootUuid: String,
        operationType: WorkspaceMutationType,
        sourceRelativePath: String,
        targetRelativePath: String,
        databaseTargetUuid: String,
        rootIdentity: String,
    ): StagedWorkspaceFileMutation {
        require(operationType != WorkspaceMutationType.DELETE || targetRelativePath.startsWith(".nexara_tombstones/")) {
            "文件 DELETE journal 目标必须位于 tombstone"
        }
        requireSafeRelative(sourceRelativePath)
        requireSafeRelative(targetRelativePath)
        require(databaseTargetUuid.isNotBlank() && rootIdentity.isNotBlank()) { "文件 journal 身份无效" }
        val operationId = operationIdFactory()
        require(operationId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "文件 journal operationId 无效" }
        val payload = WorkspaceMutationPayload(
            sourceRelativePath = sourceRelativePath,
            targetRelativePath = targetRelativePath,
            databaseTargetUuid = databaseTargetUuid,
            expectedSha256 = rootIdentity,
        )
        val raw = WorkspaceMutationPayloadCodec.encode(payload)
        val now = clock()
        database.workspaceMutationDao().insert(
            WorkspaceMutationEntity(
                operationId = operationId,
                workspaceRootUuid = workspaceRootUuid,
                operationType = operationType,
                payload = raw,
                payloadDigest = Sha256Utils.hash(raw),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return StagedWorkspaceFileMutation(operationId, workspaceRootUuid, operationType)
    }

    override suspend fun commitDatabase(
        staged: StagedWorkspaceFileMutation,
        mutation: suspend () -> Unit,
    ) {
        database.withTransaction {
            mutation()
            check(database.workspaceMutationDao().markDbCommitted(staged.operationId, clock()) == 1) {
                "文件 journal 无法标记 DB_COMMITTED"
            }
        }
    }

    override suspend fun complete(staged: StagedWorkspaceFileMutation) {
        check(database.workspaceMutationDao().deleteCommitted(staged.operationId) == 1) {
            "文件 journal 无法完成"
        }
    }

    override suspend fun abort(staged: StagedWorkspaceFileMutation) {
        check(database.workspaceMutationDao().deletePrepared(staged.operationId) == 1) {
            "文件 journal 无法回滚"
        }
    }

    private fun requireSafeRelative(raw: String) {
        require(raw.isNotBlank() && '\u0000' !in raw && !raw.startsWith('/') && !raw.contains('\\')) {
            "文件 journal 相对路径无效"
        }
        require(raw.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "文件 journal 路径越界"
        }
    }
}

/**
 * 文件级 v3 journal 的启动恢复器。恢复只采信 Room 中的 materializedPath；文件布局存在歧义时保留
 * journal 并阻止进入可写态，绝不猜测覆盖。
 */
class WorkspaceFileMutationRecoveryCoordinator(
    private val database: NexaraDatabase,
    private val fileOps: WorkspaceFileOps = SecureWorkspaceFileOps(),
) {
    suspend fun recoverOrThrow() = withContext(Dispatchers.IO) {
        database.workspaceMutationDao().getUnfinished()
            .filterNot(::isSessionDelete)
            .forEach { entity ->
                try {
                    recoverOne(entity)
                } catch (known: WorkspaceMutationRecoveryException) {
                    throw known
                } catch (failure: Throwable) {
                    throw WorkspaceMutationRecoveryException("文件 journal 恢复失败", failure)
                }
            }
    }

    private suspend fun recoverOne(entity: WorkspaceMutationEntity) {
        if (Sha256Utils.hash(entity.payload) != entity.payloadDigest) conflict("文件 journal payload 摘要不匹配")
        val payload = when (val decoded = WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload)) {
            is WorkspaceMutationPayloadResult.Valid -> decoded.payload
            is WorkspaceMutationPayloadResult.Invalid -> conflict(decoded.error.message)
        }
        val targetUuid = payload.databaseTargetUuid?.takeIf(String::isNotBlank)
            ?: conflict("文件 journal 缺少数据库目标")
        val identity = payload.expectedSha256?.takeIf(String::isNotBlank)
            ?: conflict("文件 journal 缺少根身份")
        val targetRaw = payload.targetRelativePath?.takeIf(String::isNotBlank)
            ?: conflict("文件 journal 缺少目标路径")
        val source = relative(payload.sourceRelativePath)
        val target = relative(targetRaw)
        val root = database.fileEntryDao().getAnyStateByUuidForLifecycle(
            entity.workspaceRootUuid,
            entity.workspaceRootUuid,
        )
            ?: conflict("文件 journal 工作区根不存在")
        if (root.hash != identity) conflict("文件 journal 根身份与数据库不匹配")
        val rootPath = File(root.physicalRootPath).toPath()
        fileOps.ensureRoot(rootPath, initializeIdentity = false, expectedIdentity = identity)
        val databaseTarget = database.fileEntryDao().getAnyStateByUuidForLifecycle(
            entity.workspaceRootUuid,
            targetUuid,
        )

        when (entity.operationType) {
            WorkspaceMutationType.CREATE,
            WorkspaceMutationType.CREATE_STREAMING,
            WorkspaceMutationType.MKDIR -> recoverCreateLike(entity, rootPath, databaseTarget, targetRaw, target)

            WorkspaceMutationType.RENAME,
            WorkspaceMutationType.MOVE,
            WorkspaceMutationType.RECYCLE,
            WorkspaceMutationType.RESTORE -> recoverMoveLike(
                entity,
                rootPath,
                databaseTarget ?: conflict("移动类 journal 的数据库目标不存在"),
                payload.sourceRelativePath,
                targetRaw,
                source,
                target,
            )

            WorkspaceMutationType.DELETE -> recoverDeleteLike(entity, rootPath)
        }
    }

    private suspend fun recoverCreateLike(
        entity: WorkspaceMutationEntity,
        root: Path,
        databaseTarget: FileEntry?,
        targetRaw: String,
        target: List<String>,
    ) {
        val targetExists = fileOps.exists(root, target)
        when {
            databaseTarget == null && targetExists -> {
                fileOps.delete(root, target)
                deleteForState(entity)
            }
            databaseTarget == null -> deleteForState(entity)
            normalize(databaseTarget.materializedPath) == normalize(targetRaw) && targetExists -> deleteForState(entity)
            else -> conflict("创建类 journal 的数据库与文件布局冲突")
        }
    }

    private suspend fun recoverMoveLike(
        entity: WorkspaceMutationEntity,
        root: Path,
        databaseTarget: FileEntry,
        sourceRaw: String,
        targetRaw: String,
        source: List<String>,
        target: List<String>,
    ) {
        val sourceExists = fileOps.exists(root, source)
        val targetExists = fileOps.exists(root, target)
        if (sourceExists == targetExists) conflict("移动类 journal 文件布局冲突")
        when (normalize(databaseTarget.materializedPath)) {
            normalize(sourceRaw) -> {
                if (targetExists) fileOps.move(root, target, source).commit()
                deleteForState(entity)
            }
            normalize(targetRaw) -> {
                if (sourceExists) fileOps.move(root, source, target).commit()
                deleteForState(entity)
            }
            else -> conflict("移动类 journal 的数据库路径冲突")
        }
    }

    private suspend fun recoverDeleteLike(
        entity: WorkspaceMutationEntity,
        root: Path,
    ) {
        val restorePaths = buildMap {
            database.fileEntryDao().getAllStatesByWorkspaceRootForCleanup(entity.workspaceRootUuid)
                .filterNot { it.uuid == it.workspaceRootUuid }
                .forEach { entry ->
                    put(workspaceDeletionToken(entry.uuid), materializedRelative(entry.materializedPath))
                    put(
                        workspaceVersionDeletionToken(entry.uuid),
                        workspaceVersionSnapshotDirectory(entity.workspaceRootUuid, entry.uuid),
                    )
                }
        }
        val report = fileOps.recoverTombstones(root, restorePaths::get)
        if (report.attentionTokens.isNotEmpty()) conflict("删除类 journal tombstone 布局冲突")
        deleteForState(entity)
    }

    private suspend fun deleteForState(entity: WorkspaceMutationEntity) {
        val deleted = when (entity.state) {
            WorkspaceMutationStage.PREPARED -> database.workspaceMutationDao().deletePrepared(entity.operationId)
            WorkspaceMutationStage.DB_COMMITTED -> database.workspaceMutationDao().deleteCommitted(entity.operationId)
        }
        if (deleted != 1) conflict("文件 journal 无法清理")
    }

    private fun relative(raw: String): List<String> {
        if (raw.isBlank() || '\u0000' in raw || raw.startsWith('/') || raw.contains('\\')) {
            conflict("文件 journal 相对路径无效")
        }
        return raw.split('/').also { parts ->
            if (parts.any { it.isBlank() || it == "." || it == ".." }) conflict("文件 journal 路径越界")
        }
    }

    private fun materializedRelative(raw: String): List<String> {
        if (!raw.startsWith('/') || raw == "/") conflict("文件 journal 数据库路径无效")
        return relative(raw.trimStart('/'))
    }

    private fun normalize(raw: String): String = raw.replace('\\', '/').trim('/')

    private fun isSessionDelete(entity: WorkspaceMutationEntity): Boolean {
        if (entity.operationType != WorkspaceMutationType.DELETE) return false
        val payload = (WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload)
            as? WorkspaceMutationPayloadResult.Valid)?.payload ?: return false
        return payload.targetRelativePath?.startsWith("${WorkspaceMutationRecoveryCoordinator.STAGING_DIRECTORY}/") == true
    }

    private fun conflict(message: String): Nothing = throw WorkspaceMutationRecoveryException(message)
}
