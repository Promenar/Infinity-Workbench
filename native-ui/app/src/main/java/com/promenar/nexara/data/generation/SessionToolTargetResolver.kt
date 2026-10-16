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
): String? {
    val args = (ToolArgumentsValidator().validate(arguments) as? ToolArgumentsValidation.Valid)?.arguments ?: return null
    val paths = mutableMapOf<String, String>()
    try {
        val rootUuid = session.workspaceRootUuid
        if (rootUuid != null && repository != null) {
            listOf("uuid", "parentUuid", "newParentUuid").forEach { key ->
                val uuid = (args[key] as? JsonPrimitive)?.content
                if (!uuid.isNullOrBlank()) {
                    repository.getByUuid(rootUuid, uuid)?.materializedPath?.let { paths[uuid] = it }
                }
            }
        }
        return ToolExecutionTargets.extract(tool, arguments, paths)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { return null }
}
