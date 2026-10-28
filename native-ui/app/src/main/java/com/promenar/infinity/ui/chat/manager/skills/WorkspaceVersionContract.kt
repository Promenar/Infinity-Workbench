package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.*

internal const val WORKSPACE_VERSION_REQUIREMENT =
    "必须提供 expectedHash 或 expected_sha256 之一，取自 read_file 返回的 Hash（私有工作区）或 sha256（挂载文件），不得自行推算或补填。"

internal fun workspaceExpectedHash(args: JsonObject): String? =
    args.stringArgument("expectedHash")?.takeIf(String::isNotBlank)
        ?: args.stringArgument("expected_sha256")?.takeIf(String::isNotBlank)

/** 版本由调用方显式提供；私有工作区与挂载路径共享同一错误契约。 */
internal fun workspaceVersionError(operation: String, args: JsonObject): ToolResult? {
    val legacy = args.stringArgument("expectedHash")?.takeIf(String::isNotBlank)
    val sha256 = args.stringArgument("expected_sha256")?.takeIf(String::isNotBlank)
    val conflict = legacy != null && sha256 != null && legacy != sha256
    if (!conflict && (legacy != null || sha256 != null)) return null
    val error = buildJsonObject {
        put("code", if (conflict) "CONFLICT" else "MISSING_VERSION")
        put("message", if (conflict) "expectedHash 与 expected_sha256 不一致，请使用同一次 read_file 返回的版本。" else WORKSPACE_VERSION_REQUIREMENT)
        put("parameters", buildJsonArray { add("expectedHash"); add("expected_sha256") })
        put("source_tool", "read_file")
        put("source_fields", buildJsonArray { add("Hash"); add("sha256") })
    }.toString()
    return ToolResult("${operation}_version_error", error, "error", data = error)
}
