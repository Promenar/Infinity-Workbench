package com.promenar.nexara.data.generation

import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.tool.ToolArgumentsValidation
import com.promenar.nexara.domain.tool.ToolArgumentsValidator
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

/** UUID 必须在当前会话工作区内解析；解析失败不产生可记住的授权目标。 */
suspend fun resolveSessionToolTarget(
    session: Session,
    tool: ProtocolTool,
    arguments: String,
    repository: IWorkspaceRepository?,
    mounts: com.promenar.nexara.domain.storage.ExternalWorkspaceAccess? = null,
    sshHostStore: com.promenar.nexara.data.ssh.SshHostStore? = null,
    sshSessionHost: ((String, String) -> String?)? = null,
): String? {
    val args = (ToolArgumentsValidator().validate(arguments) as? ToolArgumentsValidation.Valid)?.arguments ?: return null
    val paths = mutableMapOf<String, String>()
    try {
        val visibleMounts = mounts?.visible(session.id).orEmpty()
        // 自选目录工作区以空别名挂载承载 `/`：路径授权绑定工作区身份，切换工作区后旧授权不再适用。
        val workspaceMount = mounts?.workspaceMount(session.id)
        val namespaceMounts = visibleMounts + listOfNotNull(workspaceMount)
        if (tool.function.name == "ssh_session_send") {
            val id = (args["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            sshSessionHost?.invoke("agent:${session.id}", id)?.let { paths["ssh_session:$id"] = it }
        }
        val rootUuid = session.workspaceRootUuid
        if (rootUuid != null && repository != null || workspaceMount != null) {
            listOf("uuid", "parentUuid", "newParentUuid").forEach { key ->
                val uuid = (args[key] as? JsonPrimitive)?.content
                if (!uuid.isNullOrBlank()) {
                    if (uuid.startsWith("external:")) com.promenar.nexara.data.storage.ExternalMountPaths.uuidPath(uuid, namespaceMounts)?.let { paths[uuid] = it }
                    else if (workspaceMount == null && rootUuid != null) repository?.getByUuid(rootUuid, uuid)?.materializedPath?.let { paths[uuid] = it }
                }
            }
        }
        val sshIdentities = if (tool.mcpServerId == null && tool.sourceId != "mcp" && !tool.runtimeToolId.startsWith("mcp:") &&
            tool.function.name in setOf("ssh_exec", "ssh_session_open", "ssh_session_send", "sftp_list", "sftp_read", "sftp_write")
        ) {
            val alias = if (tool.function.name == "ssh_session_send") {
                val id = (args["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                paths["ssh_session:$id"]
            } else (args["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (alias == null) return null
            val host = sshHostStore?.find(alias) ?: return null
            mapOf(alias to host.executionIdentity())
        } else emptyMap()
        val target = ToolExecutionTargets.extract(tool, arguments, paths, sshIdentities) ?: return null
        if (mounts == null) return target
        val parts = kotlinx.serialization.json.Json.parseToJsonElement(target) as? kotlinx.serialization.json.JsonArray ?: return null
        val metadata = parts.lastOrNull() as? kotlinx.serialization.json.JsonObject
        val tuple = if (metadata != null) parts.dropLast(1) else parts.toList()
        val strings = tuple.map { (it as? JsonPrimitive)?.content ?: return null }
        if (strings.firstOrNull() !in setOf("path", "move")) return target
        val legacyPaths = paths.filter { (uuid, path) -> !uuid.startsWith("external:") && (path == "/mnt" || path.startsWith("/mnt/")) }
        val mountIds = strings.drop(1).mapNotNull { path ->
            val parsed = com.promenar.nexara.data.storage.ExternalMountPaths.parse(path)
            if (parsed == null) {
                workspaceMount?.takeIf { com.promenar.nexara.domain.storage.ExternalFolderPaths.normalize(path) != "/mnt" }?.let { it.alias to it.id }
            } else parsed.let { (alias, _) ->
                val mountId = visibleMounts.singleOrNull { it.alias == alias }?.id
                if (mountId == null && path !in legacyPaths.values) return null
                mountId?.let { alias to it }
            }
        }.toMap().toSortedMap()
        if (mountIds.isEmpty() && legacyPaths.isEmpty()) return target
        // 授权绑定挂载身份；移除后复用同名别名也不会继承旧授权。
        return kotlinx.serialization.json.JsonArray(tuple + kotlinx.serialization.json.JsonObject(metadata.orEmpty() + mapOf(
            "mountIds" to kotlinx.serialization.json.JsonObject(mountIds.mapValues { JsonPrimitive(it.value) }),
            "legacyWorkspaceUuids" to kotlinx.serialization.json.JsonArray(legacyPaths.keys.sorted().map(::JsonPrimitive)),
        ))).toString()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { return null }
}
