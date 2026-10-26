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
    suspend fun isPending(operationId: String): Boolean = true
}

class RoomWorkspaceFileMutationJournal(
    private val database: NexaraDatabase,
    private val operationIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : WorkspaceFileMutationJournal {
    override suspend fun isPending(operationId: String): Boolean =
        database.workspaceMutationDao().get(operationId) != null

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
        require(databaseTargetUuid.isNotBlank() && PersistentFileIdentity.isValid(rootIdentity)) { "文件 journal 身份无效" }
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
        database.fileEntryDao().getAllWorkspaceRootsForMaintenance().forEach { adoptLegacyRootIdentity(it) }
        recoverEntities(database.workspaceMutationDao().getUnfinished().filterNot(::isSessionDelete))
        cleanupOrphanCreateOwnership(database.fileEntryDao().getAllWorkspaceRootsForMaintenance())
    }

    suspend fun recoverRootOrThrow(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        adoptLegacyRootIdentityOrThrow(workspaceRootUuid)
        recoverEntities(
            database.workspaceMutationDao().getUnfinished()
                .filter { it.workspaceRootUuid == workspaceRootUuid }
                .filterNot(::isSessionDelete),
        )
        database.fileEntryDao().getAnyStateByUuidForLifecycle(workspaceRootUuid, workspaceRootUuid)
            ?.let { cleanupOrphanCreateOwnership(listOf(it)) }
    }

    /** 仅清理已证明与数据库现状一致的移动类 journal，绝不移动或删除用户节点。 */
    suspend fun abandonRootOrThrow(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        require(workspaceRootUuid.isNotBlank()) { "工作区根标识不能为空" }
        adoptLegacyRootIdentityOrThrow(workspaceRootUuid)
        try {
            val root = database.fileEntryDao().getAnyStateByUuidForLifecycle(workspaceRootUuid, workspaceRootUuid)
                ?: conflict("文件 journal 工作区根不存在")
            val rootPath = File(root.physicalRootPath).toPath()
            WorkspaceMutationCoordinator.withBoundRoot(rootPath, root.hash) {
                val entities = pendingFileMutations(workspaceRootUuid)
                entities.forEach { entity ->
                    if (entity.operationType !in setOf(WorkspaceMutationType.MOVE, WorkspaceMutationType.RENAME,
                            WorkspaceMutationType.RECYCLE, WorkspaceMutationType.RESTORE)) {
                        conflict("该 journal 类型需要保留内部恢复证据，不能安全放弃")
                    }
                    if (Sha256Utils.hash(entity.payload) != entity.payloadDigest) conflict("文件 journal payload 摘要不匹配")
                    val payload = when (val decoded = WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload)) {
                        is WorkspaceMutationPayloadResult.Valid -> decoded.payload
                        is WorkspaceMutationPayloadResult.Invalid -> conflict(decoded.error.message)
                    }
                    if (payload.expectedSha256 != root.hash || payload.databaseTargetUuid.isNullOrBlank()) {
                        conflict("文件 journal 的根身份或数据库目标无效")
                    }
                    relative(payload.sourceRelativePath)
                    relative(payload.targetRelativePath ?: conflict("文件 journal 缺少目标路径"))
                }
                val entries = database.fileEntryDao().getAllStatesByWorkspaceRootForCleanup(workspaceRootUuid).sortedBy { it.uuid }
                val snapshot = StartupJournalConsistency.rootSnapshot(root, entries, fileOps)
                database.withTransaction {
                    if (pendingFileMutations(workspaceRootUuid) != entities ||
                        database.fileEntryDao().getAnyStateByUuidForLifecycle(workspaceRootUuid, workspaceRootUuid) != root ||
                        database.fileEntryDao().getAllStatesByWorkspaceRootForCleanup(workspaceRootUuid).sortedBy { it.uuid } != entries) {
                        conflict("文件 journal 或工作区数据库在检查后发生变化")
                    }
                    if (StartupJournalConsistency.rootSnapshot(root, entries, fileOps) != snapshot) {
                        conflict("工作区文件在检查后发生变化")
                    }
                    entities.forEach { deleteForState(it) }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (known: WorkspaceMutationRecoveryException) {
            throw known
        } catch (failure: Throwable) {
            throw WorkspaceMutationRecoveryException("文件 journal 无法安全放弃", failure)
        }
    }

    /**
     * nx1 格式之前认领的根在数据库中保存 `nonce|sha256(完整fileKey)`。文件端口核验旧认领与当前目录后，
     * 才把私有区、引用该认领的未完成 journal 与根认领一起改写为等价现行身份；无法核验时全部保持原样。
     */
    suspend fun adoptLegacyRootIdentityOrThrow(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        database.fileEntryDao().getAnyStateByUuidForLifecycle(workspaceRootUuid, workspaceRootUuid)
            ?.let { adoptLegacyRootIdentity(it) }
    }

    private suspend fun adoptLegacyRootIdentity(root: FileEntry) {
        val legacy = root.hash
        if (legacy.isBlank() || PersistentFileIdentity.isValid(legacy)) return
        val rootPath = File(root.physicalRootPath).toPath()
        val identity = adoptableIdentity(rootPath, legacy)
        WorkspaceMutationCoordinator.withBoundRoot(rootPath, identity) {
            if (adoptableIdentity(rootPath, legacy) != identity) conflict(LEGACY_ROOT_UNVERIFIED)
            // 私有区先于数据库迁移：中断后数据库仍为旧认领，下次启动重新核验并继续；反序会遗留按旧键寻址的私有区。
            fileOps.adoptLegacyRootPrivateData(legacy, identity)
            database.withTransaction {
                if (database.fileEntryDao().getAnyStateByUuidForLifecycle(root.workspaceRootUuid, root.uuid) != root) {
                    conflict("工作区根在身份格式迁移前发生变化")
                }
                if (adoptableIdentity(rootPath, legacy) != identity) conflict(LEGACY_ROOT_UNVERIFIED)
                database.workspaceMutationDao().getUnfinished().filter { it.workspaceRootUuid == root.uuid }.forEach { row ->
                    if (Sha256Utils.hash(row.payload) != row.payloadDigest) return@forEach
                    val payload = (WorkspaceMutationPayloadCodec.decode(row.payloadVersion, row.payload)
                        as? WorkspaceMutationPayloadResult.Valid)?.payload ?: return@forEach
                    if (payload.expectedSha256 != legacy) return@forEach
                    val raw = WorkspaceMutationPayloadCodec.encode(payload.copy(expectedSha256 = identity))
                    if (database.workspaceMutationDao().replacePayload(row.operationId, row.payloadDigest, raw, Sha256Utils.hash(raw)) != 1) {
                        conflict("文件 journal 在身份格式迁移期间发生变化")
                    }
                }
                database.fileEntryDao().update(root.copy(hash = identity))
            }
            // 事务提交前保留旧标记中的完整 fileKey，避免中断后丢失旧摘要的核验证据。
            fileOps.ensureRoot(rootPath, initializeIdentity = false, expectedIdentity = identity)
        }
    }

    private fun adoptableIdentity(rootPath: Path, legacy: String): String = try {
        fileOps.adoptLegacyRootIdentity(rootPath, legacy)
    } catch (failure: Exception) {
        throw WorkspaceMutationRecoveryException(LEGACY_ROOT_UNVERIFIED, failure)
    } ?: conflict(LEGACY_ROOT_UNVERIFIED)

    private suspend fun pendingFileMutations(workspaceRootUuid: String): List<WorkspaceMutationEntity> =
        database.workspaceMutationDao().getUnfinished()
            .filter { it.workspaceRootUuid == workspaceRootUuid && !isSessionDelete(it) }
            .sortedBy { it.operationId }

    private suspend fun recoverEntities(entities: List<WorkspaceMutationEntity>) {
        entities.forEach { entity ->
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
        WorkspaceMutationCoordinator.withBoundRoot(rootPath, identity) {
            fileOps.ensureRoot(rootPath, initializeIdentity = false, expectedIdentity = identity)
            fileOps.migrateInternalStorage(rootPath)
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
    }

    private suspend fun recoverCreateLike(
        entity: WorkspaceMutationEntity,
        root: Path,
        databaseTarget: FileEntry?,
        targetRaw: String,
        target: List<String>,
    ) {
        val targetExists = fileOps.exists(root, target)
        val ownership = listOf(CREATE_OWNERSHIP_DIRECTORY, entity.operationId)
        val hasOwnership = fileOps.exists(root, ownership)
        val stagedNode = ownership + CREATE_STAGED_NODE
        val stagedExists = hasOwnership && fileOps.exists(root, stagedNode)
        val rollbackNode = ownership + CREATE_ROLLBACK_NODE
        val rollbackExists = hasOwnership && fileOps.exists(root, rollbackNode)
        val manifest = if (hasOwnership && fileOps.exists(root, ownership + CREATE_MANIFEST)) {
            decodeCreateOwnershipManifest(fileOps.read(root, ownership + CREATE_MANIFEST))
                ?: conflict("创建类 journal 的 ownership manifest 损坏")
        } else null
        if (manifest != null && normalize(manifest.targetRelativePath) != normalize(targetRaw)) {
            conflict("创建类 journal 的 ownership target 不匹配")
        }
        fun verify(relative: List<String>) {
            val proof = manifest ?: conflict("创建类 journal 缺少归属 manifest")
            requireCurrentCreationProof(proof)
            val token = proof.proofToken ?: conflict("旧创建 journal 缺少持续归属证明，节点已保留")
            fileOps.verifyCreationProof(root, relative, ownership, token, proof.identity)
        }
        fun removeOwned(relative: List<String>) {
            val proof = manifest ?: conflict("创建类 journal 缺少归属 manifest")
            requireCurrentCreationProof(proof)
            val token = proof.proofToken ?: conflict("旧创建 journal 缺少持续归属证明，节点已保留")
            fileOps.deleteCreatedNode(root, relative, ownership, token, proof.identity)
        }
        when (entity.state) {
            WorkspaceMutationStage.PREPARED -> when {
                databaseTarget != null -> conflict("PREPARED 创建 journal 意外存在数据库目标")
                targetExists && !stagedExists && !rollbackExists -> {
                    removeOwned(target)
                    cleanupCompletedCreateOwnership(fileOps, root, ownership, rolledBack = true)
                    deleteForState(entity)
                }
                targetExists -> conflict("创建类 journal 的物理目标缺少 operation 归属证明")
                else -> {
                    if (stagedExists && rollbackExists) conflict("创建回滚存在多个隔离节点")
                    if (stagedExists) {
                        if (manifest != null) removeOwned(stagedNode) else fileOps.deleteNonRecursive(root, stagedNode)
                    }
                    if (rollbackExists) {
                        if (manifest != null) removeOwned(rollbackNode) else fileOps.deleteNonRecursive(root, rollbackNode)
                    }
                    if (hasOwnership || fileOps.exists(root, creationCleanupReceiptPath(ownership))) {
                        if (manifest != null || fileOps.exists(root, creationCleanupReceiptPath(ownership))) {
                            cleanupCompletedCreateOwnership(fileOps, root, ownership, rolledBack = true)
                        } else if (hasOwnership) {
                            val present = fileOps.listChildren(root, ownership)
                            present.forEach { name -> fileOps.deleteNonRecursive(root, ownership + name) }
                            fileOps.deleteNonRecursive(root, ownership)
                        }
                    }
                    deleteForState(entity)
                }
            }
            WorkspaceMutationStage.DB_COMMITTED -> {
                val committed = databaseTarget ?: conflict("DB_COMMITTED 创建 journal 缺少数据库目标")
                if (normalize(committed.materializedPath) != normalize(targetRaw)) {
                    conflict("DB_COMMITTED 创建 journal 数据库路径不匹配")
                }
                if (!targetExists) {
                    if (!stagedExists || rollbackExists) conflict("DB_COMMITTED 创建 journal 缺少物理目标")
                    verify(stagedNode)
                    fileOps.move(root, stagedNode, target).commit()
                }
                verify(target)
                val actual = fileOps.inspect(root, target)
                if (manifest == null || actual != manifest.identity) {
                    conflict("DB_COMMITTED 创建 journal 物理目标不属于原 staged node")
                }
                val databaseMatches = if (committed.isDirectory) {
                    actual.kind == "directory"
                } else {
                    actual.kind == "file" && actual.sizeBytes == committed.sizeBytes && actual.sha256 == committed.hash
                }
                if (!databaseMatches) conflict("DB_COMMITTED 创建 journal 物理身份与数据库不匹配")
                // journal 是已提交业务状态的事实源；先完成 journal，再由有界维护清理回收 owner。
                // 即使进程在二者之间退出，下次启动也不会因 manifest 缺失永久阻断恢复。
                deleteForState(entity)
                // owner manifest 是后处理的持久记录，由下方 orphan 清理完成目标标记清理后回收。
            }
        }
    }

    /**
     * 仅在启动恢复或单工作区删除门禁内调用。此时没有新的文件 mutation 获准进入；成功收敛
     * journal 后，整个工作区 ownership 根都只可能包含无 journal 的残留。
     */
    private suspend fun cleanupOrphanCreateOwnership(roots: List<FileEntry>) {
        try {
            roots.forEach { root ->
                val hasLiveFileMutation = database.workspaceMutationDao().getUnfinished()
                    .any { it.workspaceRootUuid == root.workspaceRootUuid && !isSessionDelete(it) }
                if (hasLiveFileMutation) return@forEach
                val rootPath = File(root.physicalRootPath).toPath()
                WorkspaceMutationCoordinator.withBoundRoot(rootPath, root.hash) {
                    fileOps.ensureRoot(rootPath, initializeIdentity = false, expectedIdentity = root.hash)
                    val ownershipRoot = listOf(CREATE_OWNERSHIP_DIRECTORY)
                    if (fileOps.exists(rootPath, ownershipRoot)) {
                        creationOwnershipOperationIds(fileOps, rootPath).forEach { operationId ->
                            cleanupCompletedCreateOwnership(fileOps, rootPath, ownershipRoot + operationId)
                        }
                        fileOps.deleteNonRecursive(rootPath, ownershipRoot)
                    }
                }
            }
        } catch (known: WorkspaceMutationRecoveryException) {
            throw known
        } catch (failure: Throwable) {
            throw WorkspaceMutationRecoveryException("创建 ownership 维护清理失败", failure)
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
        // source 与 target 同一路径（移入原所在目录）的 journal 既不移动文件也不改数据库，只需收尾；
        // 此后同一节点可能已被其它操作改名或删除，数据库路径不再作为判断依据。
        if (normalize(sourceRaw) == normalize(targetRaw)) {
            deleteForState(entity)
            return
        }
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
        return !payload.databaseTargetUuid.isNullOrBlank() &&
            payload.targetRelativePath?.startsWith("${WorkspaceMutationRecoveryCoordinator.STAGING_DIRECTORY}/") == true
    }

    private fun conflict(message: String): Nothing = throw WorkspaceMutationRecoveryException(message)
}

internal const val LEGACY_ROOT_UNVERIFIED = "工作区根仍为旧版身份格式，且当前目录无法按旧格式核验，未自动迁移；文件、数据库与恢复记录均已保留"
internal const val CREATE_OWNERSHIP_DIRECTORY = ".nexara_create_operations"
internal const val CREATE_STAGED_NODE = "staged-node"
internal const val CREATE_MANIFEST = "ownership-v1"
internal const val CREATE_DIRECTORY_MARKER = ".nexara_creation_identity"
internal const val CREATE_ROLLBACK_NODE = "rollback-node"
internal const val CREATE_DIRECTORY_DELETE_READY = "directory-delete-ready"
internal const val CREATE_FILE_DELETE_READY = "file-delete-ready"

internal fun requireCreationToken(token: String) {
    require(token.matches(Regex("[0-9a-f-]{36}"))) { "创建归属令牌无效" }
}

@kotlinx.serialization.Serializable
internal data class WorkspaceCreateOwnershipManifest(
    val targetRelativePath: String,
    val identity: WorkspaceNodeIdentity,
    val proofToken: String? = null,
    val proofVersion: Int = if (proofToken == null) 1 else if (identity.kind == "file") 3 else 4,
)

internal fun requireCurrentCreationProof(manifest: WorkspaceCreateOwnershipManifest) {
    val validVersions = if (manifest.identity.kind == "file") setOf(3) else setOf(2, 4)
    if (manifest.proofToken == null || manifest.proofVersion !in validVersions) {
        throw SecurityException("创建归属证明版本不可安全恢复，节点已保留")
    }
    requireCreationToken(manifest.proofToken)
}

internal fun encodeCreateOwnershipManifest(value: WorkspaceCreateOwnershipManifest): ByteArray {
    require(PersistentFileIdentity.isValid(value.identity.fileKey)) { "创建归属节点身份格式无效" }
    return listOf(
        "v${value.proofVersion}",
        value.identity.kind,
        value.identity.sizeBytes.toString(),
        value.identity.sha256.orEmpty(),
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.identity.fileKey.toByteArray(Charsets.UTF_8)),
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.targetRelativePath.toByteArray(Charsets.UTF_8)),
    ).let { if (value.proofToken == null) it else it + value.proofToken }
        .joinToString("\n").toByteArray(Charsets.UTF_8)
}

internal fun decodeCreateOwnershipManifest(bytes: ByteArray): WorkspaceCreateOwnershipManifest? {
    val parts = bytes.toString(Charsets.UTF_8).split('\n')
    val token = when {
        parts.size == 6 && parts[0] == "v1" -> null
        parts.size == 7 && parts[0] in setOf("v2", "v3", "v4") -> parts[6].takeIf {
            runCatching { requireCreationToken(it) }.isSuccess
        } ?: return null
        else -> return null
    }
    if (parts[1] !in setOf("file", "directory")) return null
    val size = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val target = runCatching {
        java.util.Base64.getUrlDecoder().decode(parts[5]).toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
    val hash = parts[3].takeIf(String::isNotBlank)
    if ((parts[1] == "file") != (hash != null)) return null
    val fileKey = runCatching {
        java.util.Base64.getUrlDecoder().decode(parts[4]).toString(Charsets.UTF_8)
    }.getOrNull()?.takeIf(PersistentFileIdentity::isValid) ?: return null
    return WorkspaceCreateOwnershipManifest(target, WorkspaceNodeIdentity(parts[1], size, hash, fileKey), token,
        parts[0].removePrefix("v").toInt())
}
