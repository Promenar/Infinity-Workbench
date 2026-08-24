package com.promenar.nexara.data.session

import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadResult
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationStage
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import com.promenar.nexara.data.repository.SecureWorkspaceFileOps
import com.promenar.nexara.data.repository.WorkspaceMutationRecoveryCoordinator
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class RoomSessionWorkspaceMutationJournal(
    private val database: NexaraDatabase,
    private val workspaceParent: Path,
    private val operationIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val verifyIdentity: (Path, String) -> Unit = { path, identity ->
        SecureWorkspaceFileOps().ensureRoot(path, initializeIdentity = false, expectedIdentity = identity)
        Unit
    },
    private val afterIdentityMarkerDeleted: () -> Unit = {},
) : SessionWorkspaceMutationJournal {
    override suspend fun stage(target: SessionDeletionTarget): StagedSessionWorkspaceMutation =
        withContext(Dispatchers.IO) {
            val parent = validateTarget(target)
            val operationId = operationIdFactory().also(::validateOperationId)
            val targetRelative = "${WorkspaceMutationRecoveryCoordinator.STAGING_DIRECTORY}/$operationId"
            val stagedPath = parent.resolve(targetRelative).normalize()
            check(!Files.exists(stagedPath, LinkOption.NOFOLLOW_LINKS)) { "删除 stage 目标已存在" }
            val payload = WorkspaceMutationPayload(
                sourceRelativePath = target.physicalRoot.fileName.toString(),
                targetRelativePath = targetRelative,
                databaseTargetUuid = target.sessionId,
                expectedSha256 = target.rootIdentity,
            )
            val raw = WorkspaceMutationPayloadCodec.encode(payload)
            val now = clock()
            database.workspaceMutationDao().insert(
                WorkspaceMutationEntity(
                    operationId = operationId,
                    workspaceRootUuid = target.workspaceRootUuid,
                    operationType = WorkspaceMutationType.DELETE,
                    payload = raw,
                    payloadDigest = Sha256Utils.hash(raw),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            Files.createDirectories(stagedPath.parent)
            if (Files.isSymbolicLink(stagedPath.parent)) throw SecurityException("删除 stage 目录不可为符号链接")
            Files.move(target.physicalRoot, stagedPath, StandardCopyOption.ATOMIC_MOVE)
            StagedSessionWorkspaceMutation(operationId, target)
        }

    override suspend fun rollback(staged: StagedSessionWorkspaceMutation) = withContext(Dispatchers.IO) {
        validateJournal(staged, WorkspaceMutationStage.PREPARED)
        val parent = workspaceParent.toAbsolutePath().normalize()
        val source = staged.target.physicalRoot.toAbsolutePath().normalize()
        val target = stagedPath(parent, staged.operationId)
        val sourceExists = exists(source)
        val targetExists = exists(target)
        when {
            sourceExists && !targetExists -> verifyIdentity(source, staged.target.rootIdentity)
            !sourceExists && targetExists -> {
                verifyIdentity(target, staged.target.rootIdentity)
                Files.move(target, source, StandardCopyOption.ATOMIC_MOVE)
            }
            else -> throw IllegalStateException("PREPARED 删除回滚文件布局冲突")
        }
        check(database.workspaceMutationDao().deletePrepared(staged.operationId) == 1) {
            "PREPARED 删除 journal 无法清理"
        }
    }

    override suspend fun complete(staged: StagedSessionWorkspaceMutation) = withContext(Dispatchers.IO) {
        val journal = database.workspaceMutationDao().get(staged.operationId)
            ?: throw IllegalStateException("DB_COMMITTED 删除 journal 不存在")
        validateJournal(staged, WorkspaceMutationStage.DB_COMMITTED, journal)
        val parent = workspaceParent.toAbsolutePath().normalize()
        val source = staged.target.physicalRoot.toAbsolutePath().normalize()
        val target = stagedPath(parent, staged.operationId)
        check(!exists(source) && exists(target)) { "DB_COMMITTED 删除文件布局冲突" }
        verifyIdentity(target, staged.target.rootIdentity)
        deleteTreeKeepingIdentityUntilLast(target)
        check(database.workspaceMutationDao().deleteCommitted(staged.operationId) == 1) {
            "DB_COMMITTED 删除 journal 无法清理"
        }
    }

    private suspend fun validateJournal(
        staged: StagedSessionWorkspaceMutation,
        expectedState: WorkspaceMutationStage,
        loaded: WorkspaceMutationEntity? = null,
    ) {
        val journal = loaded ?: database.workspaceMutationDao().get(staged.operationId)
            ?: throw IllegalStateException("删除 journal 不存在")
        check(journal.operationId == staged.operationId) { "删除 journal operationId 不匹配" }
        check(journal.state == expectedState && journal.operationType == WorkspaceMutationType.DELETE) {
            "删除 journal 状态无效"
        }
        check(journal.workspaceRootUuid == staged.target.workspaceRootUuid) { "删除 journal 工作区身份不匹配" }
        check(Sha256Utils.hash(journal.payload) == journal.payloadDigest) { "删除 journal 摘要不匹配" }
        val payload = when (val decoded = WorkspaceMutationPayloadCodec.decode(journal.payloadVersion, journal.payload)) {
            is WorkspaceMutationPayloadResult.Valid -> decoded.payload
            is WorkspaceMutationPayloadResult.Invalid -> error(decoded.error.message)
        }
        val expectedTargetRelative = "${WorkspaceMutationRecoveryCoordinator.STAGING_DIRECTORY}/${staged.operationId}"
        check(payload.targetRelativePath == expectedTargetRelative) {
            "删除 journal stage 目标与 operationId 不匹配"
        }
        check(payload.sourceRelativePath == staged.target.physicalRoot.fileName.toString()) {
            "删除 journal 根路径不匹配"
        }
        check(payload.databaseTargetUuid == staged.target.sessionId) { "删除 journal 会话身份不匹配" }
        check(payload.expectedSha256 == staged.target.rootIdentity) { "删除 journal 根身份不匹配" }
    }

    private fun validateTarget(target: SessionDeletionTarget): Path {
        val parent = workspaceParent.toAbsolutePath().normalize()
        val source = target.physicalRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent) ||
            source.parent != parent || Files.isSymbolicLink(source) ||
            !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw SecurityException("会话工作区根或父目录无效")
        }
        verifyIdentity(source, target.rootIdentity)
        return parent
    }

    private fun stagedPath(parent: Path, operationId: String): Path {
        validateOperationId(operationId)
        return parent.resolve(WorkspaceMutationRecoveryCoordinator.STAGING_DIRECTORY)
            .resolve(operationId).normalize()
    }

    private fun validateOperationId(operationId: String) {
        require(operationId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "删除 operationId 无效" }
    }

    private fun exists(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun deleteTreeKeepingIdentityUntilLast(root: Path) {
        val identityMarker = root.resolve(ROOT_IDENTITY_MARKER)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file != identityMarker) Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                if (dir != root) Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        Files.deleteIfExists(identityMarker)
        afterIdentityMarkerDeleted()
        Files.delete(root)
    }

    private companion object {
        const val ROOT_IDENTITY_MARKER = ".nexara_root_identity"
    }
}
