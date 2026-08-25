package com.promenar.nexara.data.repository

import com.promenar.nexara.data.local.db.dao.FileEntryDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import java.io.File
import java.nio.file.Files

/**
 * 在任何身份标记或数据库认领写入前，批量验证公开旧版工作区根。
 * 这样别名路径、共享根或非法结构会整体失败，不会留下“前半批已认领”的部分状态。
 */
internal class LegacyWorkspaceRootAdoptionCoordinator(
    private val filesDir: File,
    private val defaultWorkspaceParent: File,
    private val sessionDao: SessionDao,
    private val fileEntryDao: FileEntryDao,
    private val workspaceRepository: IWorkspaceRepository,
    private val sameFile: (File, File) -> Boolean = LegacyWorkspaceRootPolicy::isSameFile,
) {
    suspend fun adoptAll() {
        validateNoCanonicalRootAliases(fileEntryDao.getStructurallyValidWorkspaceRootCandidates())
        val claims = mutableListOf<SessionRootClaim>()
        for (session in sessionDao.getAll()) {
            session.claimOrNull()?.let(claims::add)
        }
        validateNoCanonicalSharing(claims)
        val pending = claims.filter { it.root.hash.isBlank() }.sortedBy { it.session.id }
        pending.forEach(::validatePendingClaim)
        pending.forEach { claim -> workspaceRepository.ensureSessionRoot(claim.session.id) }
    }

    private fun validateNoCanonicalRootAliases(roots: List<FileEntry>) {
        val seen = mutableListOf<RootFileIdentity>()
        roots.forEach { root ->
            val declared = File(root.physicalRootPath.trim()).absoluteFile.toPath().normalize()
            if (Files.isSymbolicLink(declared)) {
                throw SecurityException("工作区根不能是符号链接: ${root.uuid}")
            }
            val canonical = declared.toFile().canonicalFile
            val previous = seen.firstOrNull { identity ->
                identity.canonical.path == canonical.path || sameFile(identity.canonical, canonical)
            }
            if (previous != null && previous.ownerId != root.uuid) {
                throw SecurityException("多个工作区根记录指向同一规范物理目录")
            }
            if (previous == null) seen += RootFileIdentity(root.uuid, canonical)
        }
    }

    private suspend fun SessionEntity.claimOrNull(): SessionRootClaim? {
        val rootUuid = workspaceRootUuid ?: return null
        val root = fileEntryDao.getAnyStateByUuidForLifecycle(rootUuid, rootUuid)
            ?: throw IllegalStateException("Session 工作区根引用不存在: $id")
        return SessionRootClaim(this, root)
    }

    private fun validateNoCanonicalSharing(claims: List<SessionRootClaim>) {
        val seen = mutableListOf<RootFileIdentity>()
        claims.forEach { claim ->
            val declared = File(claim.root.physicalRootPath.trim()).absoluteFile.toPath().normalize()
            if (Files.isSymbolicLink(declared)) {
                throw SecurityException("Session 工作区根不能是符号链接: ${claim.session.id}")
            }
            val canonical = declared.toFile().canonicalFile
            val previous = seen.firstOrNull { identity ->
                identity.canonical.path == canonical.path || sameFile(identity.canonical, canonical)
            }
            if (previous != null && previous.ownerId != claim.session.id) {
                throw SecurityException("多个 Session 指向同一规范工作区根")
            }
            if (previous == null) seen += RootFileIdentity(claim.session.id, canonical)
        }
    }

    private fun validatePendingClaim(claim: SessionRootClaim) {
        val root = claim.root
        if (
            root.uuid != root.workspaceRootUuid ||
            root.parentUuid != null ||
            !root.isDirectory ||
            root.inRecycleBin ||
            root.materializedPath != "/"
        ) {
            throw SecurityException("旧版工作区根记录结构无效: ${claim.session.id}")
        }
        val declaredPath = claim.session.workspacePath?.takeIf { it.isNotBlank() }
            ?: root.physicalRootPath
        val declared = File(declaredPath.trim()).absoluteFile.toPath().normalize()
        val canonicalRoot = File(root.physicalRootPath).canonicalFile
        if (declared.toFile().canonicalFile != canonicalRoot) {
            throw SecurityException("Session 与旧版工作区根路径不一致: ${claim.session.id}")
        }
        if (
            !LegacyWorkspaceRootPolicy.isTrusted(
                sessionId = claim.session.id,
                declaredRootPath = declared,
                root = canonicalRoot,
                defaultWorkspaceParent = defaultWorkspaceParent,
                filesDir = filesDir,
            )
        ) {
            throw SecurityException("旧版工作区根不在应用私有目录内: ${claim.session.id}")
        }
    }

    private data class SessionRootClaim(
        val session: SessionEntity,
        val root: FileEntry,
    )

    private data class RootFileIdentity(
        val ownerId: String,
        val canonical: File,
    )
}

internal object LegacyWorkspaceRootPolicy {
    private const val RAG_SESSION_ID = "__nexara_rag_workspace__"
    private const val RAG_DIRECTORY = "rag_workspace"
    private const val PUBLISHED_SESSION_WORKSPACES_DIRECTORY = "workspaces"

    fun isTrusted(
        sessionId: String,
        declaredRootPath: java.nio.file.Path,
        root: File,
        defaultWorkspaceParent: File,
        filesDir: File,
    ): Boolean {
        val sessionParent = defaultWorkspaceParent.canonicalFile
        val appFilesDir = filesDir.canonicalFile
        val sessionParentDirectory = sessionParent.parentFile ?: return false
        if (!isSameFile(sessionParentDirectory, appFilesDir)) return false
        val expected = if (sessionId == RAG_SESSION_ID) {
            File(appFilesDir, RAG_DIRECTORY)
        } else {
            File(sessionParent, com.promenar.nexara.infra.util.Sha256Utils.hash(sessionId))
        }
        val expectedPath = expected.absoluteFile.toPath().normalize()
        if (
            !Files.isSymbolicLink(declaredRootPath) &&
            (declaredRootPath == expectedPath || isSameFile(declaredRootPath.toFile(), expected))
        ) {
            return isSameFile(root, expected)
        }
        if (sessionId == RAG_SESSION_ID || !isSinglePathSegment(sessionId)) return false
        val publishedParent = File(appFilesDir, PUBLISHED_SESSION_WORKSPACES_DIRECTORY)
        val publishedParentPath = publishedParent.absoluteFile.toPath().normalize()
        if (Files.isSymbolicLink(publishedParentPath)) return false
        val publishedExpected = File(publishedParent, sessionId)
        val publishedExpectedPath = publishedExpected.absoluteFile.toPath().normalize()
        if (
            Files.isSymbolicLink(declaredRootPath) ||
            (declaredRootPath != publishedExpectedPath && !isSameFile(declaredRootPath.toFile(), publishedExpected))
        ) return false
        val rootParent = root.parentFile ?: return false
        return isSameFile(root, publishedExpected) && isSameFile(rootParent, publishedParent)
    }

    /** Android 的 /data/data 与 /data/user/0 可能是同一 inode 的 bind-mount 别名。 */
    internal fun isSameFile(left: File, right: File): Boolean = runCatching {
        Files.isSameFile(left.toPath(), right.toPath())
    }.getOrDefault(false)

    private fun isSinglePathSegment(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            File(value).name == value && '/' !in value && '\\' !in value
}
