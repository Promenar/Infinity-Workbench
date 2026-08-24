package com.promenar.nexara.data.session

import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
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
        check(journal.state == WorkspaceMutationStage.DB_COMMITTED) { "删除 journal 尚未提交数据库" }
        val parent = workspaceParent.toAbsolutePath().normalize()
        val source = staged.target.physicalRoot.toAbsolutePath().normalize()
        val target = stagedPath(parent, staged.operationId)
        check(!exists(source) && exists(target)) { "DB_COMMITTED 删除文件布局冲突" }
        verifyIdentity(target, staged.target.rootIdentity)
        deleteTreeNoFollow(target)
        check(database.workspaceMutationDao().deleteCommitted(staged.operationId) == 1) {
            "DB_COMMITTED 删除 journal 无法清理"
        }
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

    private fun deleteTreeNoFollow(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
