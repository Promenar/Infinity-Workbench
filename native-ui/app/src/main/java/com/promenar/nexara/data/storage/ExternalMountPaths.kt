package com.promenar.nexara.data.storage

import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.storage.ExternalFolderException
import java.util.Base64

object ExternalMountPaths {
    const val ROOT_UUID = "external:root"
    fun validateAlias(alias: String): String = ExternalFolderPaths.validateName(alias.trim()).also {
        if (it == "mnt") throw ExternalFolderException("INVALID_PATH", "mnt 是保留名称")
    }
    fun parse(path: String): Pair<String, String>? {
        val normalized = ExternalFolderPaths.normalize(path)
        if (!normalized.startsWith("/mnt/")) return null
        val parts = normalized.removePrefix("/mnt/").split('/', limit = 2)
        validateAlias(parts[0])
        return parts[0] to if (parts.size == 1) "/" else "/${parts[1]}"
    }
    fun uuid(mount: ExternalMount, path: String): String = "external:${mount.id}:" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ExternalFolderPaths.normalize(path).toByteArray(Charsets.UTF_8))
    fun uuidPath(uuid: String, mounts: List<ExternalMount>): String? {
        if (uuid == ROOT_UUID) return "/mnt"
        val parts = uuid.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "external") return null
        val mount = mounts.singleOrNull { it.id == parts[1] } ?: return null
        return try {
            virtualPath(mount, ExternalFolderPaths.normalize(String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)))
        } catch (_: IllegalArgumentException) { null }
    }

    /** 工作区挂载的别名；它的虚拟路径就是相对路径本身，`/mnt` 仍保留给挂载。 */
    const val WORKSPACE_ALIAS = ""
    const val WORKSPACE_SOURCE = "workspace"
    fun workspaceMountId(workspaceId: String) = "ws_$workspaceId"

    fun virtualPath(mount: ExternalMount, relative: String): String =
        if (mount.alias == WORKSPACE_ALIAS) relative else "/mnt/${mount.alias}" + relative.takeIf { it != "/" }.orEmpty()

    /** 路径所属挂载的别名：`/mnt/<别名>` 为挂载；[rooted] 时其余路径（`/mnt` 本身除外）属于工作区挂载。 */
    fun aliasOf(path: String, rooted: Boolean): String? {
        parse(path)?.let { return it.first }
        return if (rooted && ExternalFolderPaths.normalize(path) != "/mnt") WORKSPACE_ALIAS else null
    }
    fun conflicts(first: ExternalMount, second: ExternalMount): Boolean =
        first.id != second.id && first.alias == second.alias &&
            (first.sessionId == null || second.sessionId == null || first.sessionId == second.sessionId)
}

val ExternalMount.isWorkspaceRoot: Boolean get() = alias == ExternalMountPaths.WORKSPACE_ALIAS

/** 一次工具调用要固定的全部挂载身份：可见挂载加当前工作区挂载。 */
suspend fun com.promenar.nexara.domain.storage.ExternalWorkspaceAccess.namespaceBindings(sessionId: String): Map<String, String> =
    visible(sessionId).associate { it.alias to it.id } + listOfNotNull(workspaceMount(sessionId)).associate { it.alias to it.id }
