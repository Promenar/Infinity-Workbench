package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadResult
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationStage
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class WorkspaceMutationRecoveryException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** 启动前收敛 v3 journal；任一歧义都抛错，阻止应用进入可写态。 */
class WorkspaceMutationRecoveryCoordinator(
    private val workspaceParent: Path,
    private val loadUnfinished: suspend () -> List<WorkspaceMutationEntity>,
    private val sessionExists: suspend (String) -> Boolean,
    private val deletePrepared: suspend (String) -> Unit,
    private val deleteCommitted: suspend (String) -> Unit,
    private val verifyRootIdentity: (Path, String) -> Unit,
    private val identityMarkerName: String = ".nexara_root_identity",
) {
    suspend fun recoverOrThrow() = withContext(Dispatchers.IO) {
        val parent = validateParent()
        loadUnfinished().forEach { entity -> recoverOne(parent, entity) }
    }

    private suspend fun recoverOne(parent: Path, entity: WorkspaceMutationEntity) {
        if (entity.operationType != WorkspaceMutationType.DELETE) conflict("不支持自动恢复的 journal 类型")
        if (Sha256Utils.hash(entity.payload) != entity.payloadDigest) conflict("journal payload 摘要不匹配")
        val payload = when (val decoded = WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload)) {
            is WorkspaceMutationPayloadResult.Valid -> decoded.payload
            is WorkspaceMutationPayloadResult.Invalid -> conflict(decoded.error.message)
        }
        val sessionId = payload.databaseTargetUuid?.takeIf { it.isNotBlank() }
            ?: conflict("journal 缺少会话标识")
        val expectedIdentity = payload.expectedSha256?.takeIf { it.isNotBlank() }
            ?: conflict("journal 缺少根身份")
        val source = resolveSafe(parent, payload.sourceRelativePath, allowReserved = false)
        val target = resolveSafe(
            parent,
            payload.targetRelativePath ?: conflict("journal 缺少 stage 目标"),
            allowReserved = true,
        )
        if (!target.startsWith(parent.resolve(STAGING_DIRECTORY)) || target.nameCount != parent.nameCount + 2) {
            conflict("journal stage 目标不在保留目录")
        }
        val sourceExists = existsNoFollow(source)
        val targetExists = existsNoFollow(target)
        val dbExists = sessionExists(sessionId)
        if (sourceExists) verifyDirectory(source, expectedIdentity)
        if (targetExists) {
            verifyOrdinaryDirectory(target)
            val durableCommittedStage = entity.state == WorkspaceMutationStage.DB_COMMITTED &&
                !dbExists && !existsNoFollow(target.resolve(identityMarkerName))
            if (!durableCommittedStage) verifyIdentity(target, expectedIdentity)
        }

        when (entity.state) {
            WorkspaceMutationStage.PREPARED -> when {
                !dbExists -> conflict("PREPARED journal 的会话已消失")
                sourceExists && !targetExists -> deletePrepared(entity.operationId)
                !sourceExists && targetExists -> {
                    Files.move(target, source)
                    deletePrepared(entity.operationId)
                }
                else -> conflict("PREPARED journal 文件布局冲突")
            }
            WorkspaceMutationStage.DB_COMMITTED -> when {
                dbExists -> conflict("DB_COMMITTED journal 的会话仍存在")
                sourceExists && targetExists -> conflict("DB_COMMITTED journal 同时存在 source 与 target")
                targetExists -> {
                    deleteTreeKeepingIdentityUntilLast(target)
                    deleteCommitted(entity.operationId)
                }
                !sourceExists -> deleteCommitted(entity.operationId)
                else -> {
                    Files.createDirectories(target.parent)
                    Files.move(source, target)
                    deleteTreeKeepingIdentityUntilLast(target)
                    deleteCommitted(entity.operationId)
                }
            }
        }
    }

    private fun validateParent(): Path {
        val parent = workspaceParent.toAbsolutePath().normalize()
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            conflict("工作区父目录无效")
        }
        return parent
    }

    private fun resolveSafe(parent: Path, raw: String, allowReserved: Boolean): Path {
        if (raw.isBlank() || '\u0000' in raw || raw.startsWith('/') || raw.contains('\\')) {
            conflict("journal 相对路径无效")
        }
        val parts = raw.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) conflict("journal 路径越界")
        if (!allowReserved && parts.first().startsWith(".nexara")) conflict("journal source 使用保留目录")
        val resolved = parts.fold(parent) { path, part -> path.resolve(part) }.normalize()
        if (!resolved.startsWith(parent)) conflict("journal 路径越界")
        return resolved
    }

    private fun verifyDirectory(path: Path, expectedIdentity: String) {
        verifyOrdinaryDirectory(path)
        verifyIdentity(path, expectedIdentity)
    }

    private fun verifyOrdinaryDirectory(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            conflict("journal 目标不是普通目录")
        }
    }

    private fun verifyIdentity(path: Path, expectedIdentity: String) {
        try {
            verifyRootIdentity(path, expectedIdentity)
        } catch (failure: Throwable) {
            throw WorkspaceMutationRecoveryException("工作区根身份验证失败", failure)
        }
    }

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun deleteTreeKeepingIdentityUntilLast(root: Path) {
        val identityMarker = root.resolve(identityMarkerName)
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
        Files.delete(root)
    }

    private fun conflict(message: String): Nothing = throw WorkspaceMutationRecoveryException(message)

    companion object {
        const val STAGING_DIRECTORY = ".nexara_session_deletions"
    }
}
