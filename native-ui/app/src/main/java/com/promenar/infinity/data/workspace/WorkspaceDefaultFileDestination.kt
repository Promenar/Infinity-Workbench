package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.data.storage.isWorkspaceRoot
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalMount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 默认文件落点只表达存储归属，运行模式负责呈现路径。 */
@Serializable
enum class WorkspaceDefaultFileDestination { SESSION_PRIVATE, PRIMARY_WORKSPACE }

/** 画布镜像固定首次写出的登记工作区；私有根始终归属当前会话。 */
@Serializable
data class WorkspaceFileDestinationBinding(
    val destination: WorkspaceDefaultFileDestination,
    val workspaceId: String? = null,
)

fun defaultWorkspaceFileDestination(
    workspace: SessionWorkspace?,
    mounts: List<ExternalMount>,
): WorkspaceDefaultFileDestination {
    if (workspace is SessionWorkspace.Unavailable) throw ExternalFolderException("UNAVAILABLE", "当前会话工作区不可用，请检查目录与授权")
    return defaultWorkspaceFileDestination(workspace is SessionWorkspace.Directory, mounts)
}

fun defaultWorkspaceFileDestination(primaryIsRegistered: Boolean, mounts: List<ExternalMount>): WorkspaceDefaultFileDestination =
    if (primaryIsRegistered && mounts.none { !it.isUserSkills && !it.isWorkspaceRoot }) {
        WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE
    } else WorkspaceDefaultFileDestination.SESSION_PRIVATE

/** 省略媒体路径时审批冻结逻辑落点与完整命名空间，新增挂载也必须重新批准。 */
fun defaultWorkspaceFileApprovalTarget(
    primaryWorkspaceId: String?,
    mounts: List<ExternalMount>,
    namespaceBindings: Map<String, String>,
): JsonObject = buildJsonObject {
    put("destination", defaultWorkspaceFileDestination(primaryWorkspaceId != null, mounts).name)
    put("workspaceId", primaryWorkspaceId?.let(::JsonPrimitive) ?: JsonNull)
    put("mountIds", JsonObject(namespaceBindings.toSortedMap().mapValues { JsonPrimitive(it.value) }))
}

/** 路径呈现与私有入口解析必须配对替换，Linux 可将私有目标呈现为 `/home/session`。 */
fun interface WorkspaceFilePathPresenter {
    fun present(destination: WorkspaceDefaultFileDestination, relativePath: String, privatePrimary: Boolean): String

    fun privateRelativePath(path: String): String? = SessionPrivateWorkspacePaths.relative(path)
}

object BuiltinWorkspaceFilePathPresenter : WorkspaceFilePathPresenter {
    override fun present(destination: WorkspaceDefaultFileDestination, relativePath: String, privatePrimary: Boolean): String {
        val relative = ExternalFolderPaths.normalize(relativePath)
        return if (destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE && !privatePrimary) {
            SessionPrivateWorkspacePaths.virtual(relative)
        } else relative
    }
}

/** `/session` 独立于主工作区和用户挂载，UUID 前缀防止私有条目被当作主工作区旧 UUID。 */
object SessionPrivateWorkspacePaths {
    const val ROOT = "/session"
    private const val UUID_PREFIX = "session:"
    fun relative(path: String): String? {
        val normalized = ExternalFolderPaths.normalize(path)
        return when {
            normalized == ROOT -> "/"
            normalized.startsWith("$ROOT/") -> normalized.removePrefix(ROOT)
            else -> null
        }
    }
    fun contains(path: String) = relative(path) != null
    fun virtual(relative: String) = ROOT + ExternalFolderPaths.normalize(relative).takeUnless { it == "/" }.orEmpty()
    fun uuid(uuid: String) = UUID_PREFIX + uuid
    fun privateUuid(uuid: String): String? = uuid.takeIf { it.startsWith(UUID_PREFIX) }?.removePrefix(UUID_PREFIX)
}
