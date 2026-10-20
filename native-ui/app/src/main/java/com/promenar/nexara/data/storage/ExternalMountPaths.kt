package com.promenar.nexara.data.storage

import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.storage.ExternalFolderException
import java.util.Base64

object ExternalMountPaths {
    const val ROOT_UUID = "external:root"
    const val SKILLS_ALIAS = "skills"
    const val SKILLS_MOUNT_ID = "builtin:skills"
    const val SKILLS_SOURCE = "builtin_skills"
    fun validateAlias(alias: String): String = ExternalFolderPaths.validateName(alias.trim()).also {
        if (it in setOf("mnt", SKILLS_ALIAS)) throw ExternalFolderException("INVALID_PATH", "$it 是保留名称")
    }
    fun parse(path: String): Pair<String, String>? {
        val normalized = ExternalFolderPaths.normalize(path)
        if (normalized == "/skills" || normalized.startsWith("/skills/")) {
            return SKILLS_ALIAS to normalized.removePrefix("/skills").ifEmpty { "/" }
        }
        if (!normalized.startsWith("/mnt/")) return null
        val parts = normalized.removePrefix("/mnt/").split('/', limit = 2)
        if (parts[0] == SKILLS_ALIAS) throw ExternalFolderException("INVALID_PATH", "skills 是保留名称；请在挂载管理中以其它别名重新添加历史同名挂载")
        validateAlias(parts[0])
        return parts[0] to if (parts.size == 1) "/" else "/${parts[1]}"
    }
    fun uuid(mount: ExternalMount, path: String): String = "external:${mount.id}:" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ExternalFolderPaths.normalize(path).toByteArray(Charsets.UTF_8))
    fun uuidPath(uuid: String, mounts: List<ExternalMount>): String? {
        if (uuid == ROOT_UUID) return "/mnt"
        if (!uuid.startsWith("external:")) return null
        val separator = uuid.lastIndexOf(':')
        if (separator <= "external:".length) return null
        val mount = mounts.singleOrNull { it.id == uuid.substring("external:".length, separator) } ?: return null
        return try {
            virtualPath(mount, ExternalFolderPaths.normalize(String(Base64.getUrlDecoder().decode(uuid.substring(separator + 1)), Charsets.UTF_8)))
        } catch (_: IllegalArgumentException) { null }
    }

    /** 工作区挂载的别名；其虚拟路径以 `/` 为根，`/mnt` 和 `/skills` 为独立保留根。 */
    const val WORKSPACE_ALIAS = ""
    const val WORKSPACE_SOURCE = "workspace"
    fun workspaceMountId(workspaceId: String) = "ws_$workspaceId"

    fun virtualPath(mount: ExternalMount, relative: String): String = when {
        mount.isWorkspaceRoot -> relative
        mount.isUserSkills -> "/skills" + relative.takeIf { it != "/" }.orEmpty()
        else -> "/mnt/${mount.alias}" + relative.takeIf { it != "/" }.orEmpty()
    }

    fun isMountedPath(path: String): Boolean {
        val normalized = ExternalFolderPaths.normalize(path)
        return normalized == "/mnt" || parse(normalized) != null
    }

    /** `/skills` 与 `/mnt/<别名>` 优先归属挂载；[rooted] 时其余路径（`/mnt` 除外）属于工作区。 */
    fun aliasOf(path: String, rooted: Boolean): String? {
        parse(path)?.let { return it.first }
        return if (rooted && ExternalFolderPaths.normalize(path) != "/mnt") WORKSPACE_ALIAS else null
    }
    fun conflicts(first: ExternalMount, second: ExternalMount): Boolean =
        first.id != second.id && first.alias == second.alias &&
            (first.sessionId == null || second.sessionId == null || first.sessionId == second.sessionId)
}

val ExternalMount.isWorkspaceRoot: Boolean get() = alias == ExternalMountPaths.WORKSPACE_ALIAS
val ExternalMount.isUserSkills: Boolean get() = id == ExternalMountPaths.SKILLS_MOUNT_ID && sourceType == ExternalMountPaths.SKILLS_SOURCE

/** 一次工具调用要固定的全部挂载身份：可见挂载加当前工作区挂载。 */
suspend fun com.promenar.nexara.domain.storage.ExternalWorkspaceAccess.namespaceBindings(sessionId: String): Map<String, String> =
    visible(sessionId).associate { it.alias to it.id } + listOfNotNull(workspaceMount(sessionId)).associate { it.alias to it.id }
