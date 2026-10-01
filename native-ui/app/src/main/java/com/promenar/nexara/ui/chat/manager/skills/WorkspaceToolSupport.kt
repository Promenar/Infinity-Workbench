package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** 工作区工具定位结果：要么是当前会话工作区内的活动条目，要么是可直接回传给模型的错误。 */
internal sealed interface WorkspaceEntryResolution {
    data class Found(val entry: FileEntry) : WorkspaceEntryResolution
    data class Failed(val result: ToolResult) : WorkspaceEntryResolution
}

/** 模型可见的工作区路径：以 `/` 开头、以 `/` 分隔，根目录为 `/`。 */
internal fun normalizeWorkspaceToolPath(raw: String): String {
    val segments = raw.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() }
    return "/" + segments.joinToString("/")
}

/**
 * 按 `uuid` 或 `path` 定位条目，二者都提供时以 uuid 为准。
 * 越界、保留目录与非法路径由仓库层拒绝，这里统一转为不泄露物理路径的错误结果。
 */
internal suspend fun resolveWorkspaceEntry(
    repository: IWorkspaceRepository?,
    workspaceRootUuid: String,
    args: JsonObject,
    operation: String,
    uuidKey: String = "uuid",
    pathKey: String = "path",
): WorkspaceEntryResolution {
    val uuid = args.stringArgument(uuidKey)?.trim()?.takeIf(String::isNotEmpty)
    val path = args.stringArgument(pathKey)?.takeIf(String::isNotBlank)
    if (uuid == null && path == null) {
        return WorkspaceEntryResolution.Failed(
            workspaceToolError(operation, "缺少 $uuidKey 或 $pathKey 参数"),
        )
    }
    if (repository == null) {
        return if (uuid != null) {
            WorkspaceEntryResolution.Failed(workspaceToolError(operation, "工作区不可用"))
        } else {
            WorkspaceEntryResolution.Failed(workspaceToolError(operation, "当前不支持按路径定位，请提供 $uuidKey"))
        }
    }
    val entry = try {
        if (uuid != null) {
            repository.getByUuid(workspaceRootUuid, uuid)
        } else {
            repository.getByMaterializedPath(workspaceRootUuid, normalizeWorkspaceToolPath(path!!))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        return WorkspaceEntryResolution.Failed(workspaceToolError(operation, "路径无效或位于系统保留目录"))
    } catch (_: Exception) {
        null
    }
    return entry?.let(WorkspaceEntryResolution::Found)
        ?: WorkspaceEntryResolution.Failed(
            workspaceToolError(operation, "未找到条目：${uuid ?: normalizeWorkspaceToolPath(path!!)}"),
        )
}

/**
 * 解析调用方给出的 uuid（兼容旧参数），无 path 参数时直接使用 uuid，
 * 便于 read/write/patch/diff 在保留原 FileOperationRepository 契约的同时支持路径定位。
 */
internal suspend fun resolveWorkspaceFileUuid(
    repository: IWorkspaceRepository?,
    workspaceRootUuid: String,
    args: JsonObject,
    operation: String,
): Pair<String?, ToolResult?> {
    val uuid = args.stringArgument("uuid")?.trim()?.takeIf(String::isNotEmpty)
    if (uuid != null || repository == null) {
        return if (uuid != null) uuid to null else null to workspaceToolError(operation, "缺少 uuid 或 path 参数")
    }
    return when (val resolved = resolveWorkspaceEntry(repository, workspaceRootUuid, args, operation)) {
        is WorkspaceEntryResolution.Found -> if (resolved.entry.isDirectory) {
            null to workspaceToolError(operation, "目标是目录，不是文件：${resolved.entry.materializedPath}")
        } else {
            resolved.entry.uuid to null
        }
        is WorkspaceEntryResolution.Failed -> null to resolved.result
    }
}

internal fun workspaceToolError(operation: String, message: String): ToolResult = ToolResult(
    id = "${operation}_error_${System.currentTimeMillis()}",
    content = message.take(512),
    status = "error",
)

/** 文件与目录名的模型侧预检；最终约束仍以仓库层为准。 */
internal fun invalidWorkspaceName(name: String): Boolean =
    name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
        name.startsWith(".nexara", ignoreCase = true) || name == ".recycle_bin"

internal fun FileEntry.describeForTool(): String = buildString {
    append(if (isDirectory) "[DIR] " else "[FILE] ")
    append(materializedPath)
    append(" [uuid=").append(uuid).append(']')
    if (!isDirectory) append(" [hash=").append(hash).append(']')
}
