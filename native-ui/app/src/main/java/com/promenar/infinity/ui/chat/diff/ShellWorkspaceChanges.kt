package com.promenar.infinity.ui.chat.diff

import androidx.compose.runtime.Immutable
import com.promenar.infinity.data.model.FileChangeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 内置命令在会话私有工作区的改动清单中的一项；只有路径，没有行数。 */
@Immutable
data class WorkspaceChangeEntry(
    val change: FileChangeType,
    val path: String,
    val fromPath: String? = null,
)

@Immutable
data class WorkspaceChangeList(
    val entries: List<WorkspaceChangeEntry>,
    val truncated: Boolean,
)

/**
 * 从 `shell_exec` / `python_exec` 结果 JSON 中取出 `workspace_changes`。
 * 只有会话私有工作区的结果带有该对象；自选目录工作区（`workspace_tracking: "none"`）、
 * 对账失败（值为 null）、非 JSON 内容或没有任何改动时都返回空。
 */
internal fun parseWorkspaceChanges(content: String?): WorkspaceChangeList? {
    val text = content?.trim()?.takeIf { it.startsWith("{") } ?: return null
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val changes = root["workspace_changes"] as? JsonObject ?: return null
    fun paths(key: String): List<String> = (changes[key] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf(String::isNotBlank)
    }
    val entries = buildList {
        paths("added").forEach { add(WorkspaceChangeEntry(FileChangeType.CREATED, it)) }
        paths("modified").forEach { add(WorkspaceChangeEntry(FileChangeType.MODIFIED, it)) }
        paths("removed").forEach { add(WorkspaceChangeEntry(FileChangeType.DELETED, it)) }
        (changes["moved"] as? JsonArray).orEmpty().forEach { element ->
            val move = element as? JsonObject ?: return@forEach
            val from = (move["from"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
            val to = (move["to"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
            add(WorkspaceChangeEntry(FileChangeType.MOVED, to, from))
        }
    }
    val truncated = (changes["truncated"] as? JsonPrimitive)?.booleanOrNull == true
    if (entries.isEmpty() && !truncated) return null
    return WorkspaceChangeList(entries, truncated)
}
