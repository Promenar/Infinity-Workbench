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
    /**
     * 在同一数据库事务内比较完整 journal 快照、会话存在性及根绑定；调用只读复核后按原阶段 CAS 删除。
     * 未提供该事务端口时拒绝放弃，不能退回按 operationId 盲删。
     */
    private val abandonUnchanged: suspend (WorkspaceMutationEntity, Boolean, () -> Unit) -> Boolean = { _, _, _ -> false },
) {
    suspend fun recoverOrThrow() = withContext(Dispatchers.IO) {
        val entities = loadUnfinished()
        // 无法解析的 DELETE 无法证明属于文件还是会话；交给调用方按根或子系统隔离。
        entities.filter { it.operationType == WorkspaceMutationType.DELETE }.forEach { entity ->
            if (WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload) is WorkspaceMutationPayloadResult.Invalid) {
                conflict("DELETE journal 无法确定恢复范围")
            }
        }
        val parent = validateParent()
        entities.filter(::isSessionDelete).forEach { entity -> recoverOne(parent, entity) }
    }

    suspend fun recoverSessionOrThrow(sessionId: String) = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank()) { "会话标识不能为空" }
        val entities = loadUnfinished().filter { sessionIdOrNull(it) == sessionId }
        if (entities.isEmpty()) return@withContext
        val parent = validateParent()
        entities.forEach { entity -> recoverOne(parent, entity) }
    }

    /** 仅定位候选范围；损坏摘要仍能定位的记录必须交给该会话的严格恢复校验。 */
    fun sessionIdOrNull(entity: WorkspaceMutationEntity): String? {
        if (entity.operationType != WorkspaceMutationType.DELETE) return null
        val payload = (WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload)
            as? WorkspaceMutationPayloadResult.Valid)?.payload ?: return null
        val target = payload.targetRelativePath ?: return null
        if (!target.startsWith("$STAGING_DIRECTORY/")) return null
        return payload.databaseTargetUuid?.takeIf(String::isNotBlank)
    }

    /** 只清理无暂存残留且数据库与源布局稳定的 journal；不回滚、不删除任何用户节点。 */
    suspend fun abandonSessionOrThrow(sessionId: String) = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank()) { "会话标识不能为空" }
        val entities = loadUnfinished().filter { sessionIdOrNull(it) == sessionId }
        if (entities.isEmpty()) return@withContext
        val parent = validateParent()
        entities.forEach { entity ->
            if (Sha256Utils.hash(entity.payload) != entity.payloadDigest) conflict("journal payload 摘要不匹配")
            val payload = (WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload)
                as? WorkspaceMutationPayloadResult.Valid)?.payload ?: conflict("journal payload 无效")
            val identity = payload.expectedSha256?.takeIf(String::isNotBlank) ?: conflict("journal 缺少根身份")
            val targetRelative = payload.targetRelativePath ?: conflict("journal 缺少目标路径")
            if (!entity.operationId.matches(Regex("[A-Za-z0-9-]{1,64}")) ||
                targetRelative != "$STAGING_DIRECTORY/${entity.operationId}") {
                conflict("journal stage 目标与 operationId 不匹配")
            }
            val source = resolveSafe(parent, payload.sourceRelativePath, allowReserved = false)
            val target = resolveSafe(parent, targetRelative, allowReserved = true)
            if (source.parent != parent) conflict("会话 journal 源不是专用根")
            WorkspaceMutationCoordinator.withBoundRoot(source, identity) {
                val expectedSessionExists = sessionExists(sessionId)
                fun verifyLayout(): Map<String, StartupJournalConsistency.LayoutNode>? {
                    if (existsNoFollow(target)) conflict("会话 journal 仍有暂存节点，不能安全放弃")
                    if (entity.state == WorkspaceMutationStage.PREPARED) {
                        if (!expectedSessionExists || !existsNoFollow(source)) conflict("PREPARED journal 的会话或源目录不存在")
                        verifyDirectory(source, identity)
                        return StartupJournalConsistency.ordinaryLayout(source)
                    }
                    if (expectedSessionExists || existsNoFollow(source)) {
                        conflict("DB_COMMITTED journal 的会话或源目录仍存在")
                    }
                    return null
                }
                val layout = verifyLayout()
                var verified = false
                val deleted = abandonUnchanged(entity, expectedSessionExists) {
                    validateParent()
                    resolveSafe(parent, payload.sourceRelativePath, allowReserved = false)
                    resolveSafe(parent, targetRelative, allowReserved = true)
                    if (verifyLayout() != layout) conflict("会话 journal 的物理布局在检查后发生变化")
                    verified = true
                }
                if (!deleted || !verified) conflict("会话 journal 或数据库状态发生变化，未完成安全放弃")
            }
        }
    }

    private fun isSessionDelete(entity: WorkspaceMutationEntity): Boolean {
        if (entity.operationType != WorkspaceMutationType.DELETE) return false
        val payload = (WorkspaceMutationPayloadCodec.decode(entity.payloadVersion, entity.payload)
            as? WorkspaceMutationPayloadResult.Valid)?.payload ?: return false
        return payload.targetRelativePath?.startsWith("$STAGING_DIRECTORY/") == true
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
        val expectedTargetRelative = "$STAGING_DIRECTORY/${entity.operationId}"
        if (payload.targetRelativePath != expectedTargetRelative) {
            conflict("journal stage 目标与 operationId 不匹配")
        }
        val source = resolveSafe(parent, payload.sourceRelativePath, allowReserved = false)
        val target = resolveSafe(
            parent,
            expectedTargetRelative,
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
        val declared = workspaceParent.toAbsolutePath().normalize()
        if (!Files.isDirectory(declared, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(declared)) {
            conflict("工作区父目录无效")
        }
        return declared.toFile().canonicalFile.toPath()
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
        var ancestor = parent
        parts.dropLast(1).forEach { part ->
            ancestor = ancestor.resolve(part)
            if (existsNoFollow(ancestor) && (Files.isSymbolicLink(ancestor) ||
                    !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS))) {
                conflict("journal 路径经过符号链接或非目录节点")
            }
        }
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
