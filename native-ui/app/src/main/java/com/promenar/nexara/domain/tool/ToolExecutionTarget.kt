package com.promenar.nexara.domain.tool

import com.promenar.nexara.data.remote.protocol.ProtocolTool
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 完整授权目标使用 JSON 元组编码；显示格式不参与授权比较。 */
object ToolExecutionTargets {
    fun extract(
        tool: ProtocolTool,
        arguments: String,
        resolvedPaths: Map<String, String> = emptyMap(),
    ): String? {
        val args = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
            ?: return null
        val serverId = tool.mcpServerId
        if (serverId != null || tool.sourceId == "mcp" || tool.runtimeToolId.startsWith("mcp:")) {
            if (serverId.isNullOrBlank()) return null
            val prefix = "mcp:$serverId:"
            if (!tool.runtimeToolId.startsWith(prefix)) return null
            val remoteName = tool.runtimeToolId.removePrefix(prefix).takeIf(String::isNotBlank) ?: return null
            return tuple("mcp", serverId, remoteName)
        }
        if (tool.risk == ToolRisk.PROCESS) {
            val host = args.string("host")?.trim()?.takeIf(String::isNotBlank) ?: return null
            val command = args.string("command")?.takeIf(String::isNotBlank) ?: return null
            return tuple("process", host.lowercase(Locale.ROOT), command)
        }
        return when (tool.function.name) {
            "read_file", "write_file", "patch_file", "diff_file", "delete_file" ->
                entryPath(args, resolvedPaths)?.let { tuple("path", it) }
            "create_file", "create_directory" -> {
                val name = validName(args.string("name")) ?: return null
                val parent = entryPath(args, resolvedPaths, "parentUuid", "parentPath", "/") ?: return null
                tuple("path", childPath(parent, name))
            }
            "move_file" -> moveTarget(args, resolvedPaths)
            "list_files" -> entryPath(args, resolvedPaths, "parentUuid", "path", "/")
                ?.let { tuple("path", it) }
            "search_files" -> args.string("query")?.takeIf(String::isNotBlank)?.let { tuple("path", "/") }
            "web_fetch" -> webHost(args)?.let { tuple("host", it) }
            "exec_js" -> args.string("code")?.let { tuple("tool", tool.function.name) }
            else -> null
        }
    }

    /** 清除不可见控制字符与格式控制后按 UTF-16 长度限制显示，保留完整代理对。 */
    fun display(target: String, maxChars: Int = 240): String {
        if (maxChars <= 0) return ""
        val parts = runCatching { Json.parseToJsonElement(target) as? JsonArray }.getOrNull()
            ?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return@map null }
            ?.takeIf { it.none { part -> part == null } }
            ?.filterNotNull()
            .orEmpty()
        val readable = when (parts.firstOrNull()) {
            "path", "host", "tool" -> parts.getOrNull(1)?.takeIf { parts.size == 2 }
            "move" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} → ${it[2]}" }
            "mcp" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} / ${it[2]}" }
            "process" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} · ${it[2]}" }
            else -> null
        } ?: target
        val safe = buildString {
            var offset = 0
            while (offset < readable.length) {
                val point = readable.codePointAt(offset)
                if (!Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt()) {
                    appendCodePoint(point)
                }
                offset += Character.charCount(point)
            }
        }
        if (safe.length <= maxChars) return safe
        val end = (maxChars - 1).let { limit ->
            if (limit > 0 && Character.isHighSurrogate(safe[limit - 1])) limit - 1 else limit
        }
        return safe.substring(0, end) + "…"
    }

    private fun moveTarget(args: JsonObject, resolvedPaths: Map<String, String>): String? {
        val source = entryPath(args, resolvedPaths)?.takeIf { it != "/" } ?: return null
        val newName = args.string("newName")?.trim()?.takeIf(String::isNotEmpty)
        if (args.containsKey("newName") && args.string("newName") == null) return null
        if (newName != null && validName(newName) == null) return null
        val hasParent = !args.string("newParentUuid").isNullOrBlank() || !args.string("newParentPath").isNullOrBlank()
        if (args.containsKey("newParentUuid") && args.string("newParentUuid") == null ||
            args.containsKey("newParentPath") && args.string("newParentPath") == null
        ) return null
        if (!hasParent && newName == null) return null
        val parent = if (hasParent) {
            entryPath(args, resolvedPaths, "newParentUuid", "newParentPath") ?: return null
        } else {
            source.substringBeforeLast('/').ifEmpty { "/" }
        }
        return tuple("move", source, childPath(parent, newName ?: source.substringAfterLast('/')))
    }

    private fun entryPath(
        args: JsonObject,
        resolvedPaths: Map<String, String>,
        uuidKey: String = "uuid",
        pathKey: String = "path",
        defaultPath: String? = null,
    ): String? {
        if (args.containsKey(uuidKey) && args.string(uuidKey) == null ||
            args.containsKey(pathKey) && args.string(pathKey) == null
        ) return null
        val uuid = args.string(uuidKey)?.trim()?.takeIf(String::isNotEmpty)
        if (uuid != null) return resolvedPaths[uuid]?.let(::normalizePath)
        val path = args.string(pathKey)?.takeIf(String::isNotBlank) ?: defaultPath ?: return null
        return normalizePath(path)
    }

    private fun normalizePath(raw: String): String? {
        val path = raw.trim()
        if (path.isEmpty() || '\u0000' in path || path.startsWith("\\\\") ||
            Regex("^[A-Za-z]:[\\\\/].*").matches(path)
        ) return null
        val segments = path.replace('\\', '/').split('/').filter(String::isNotEmpty)
        if (segments.any { it == "." || it == ".." || it.startsWith(".nexara", ignoreCase = true) } ||
            segments.firstOrNull() == ".recycle_bin"
        ) return null
        return "/" + segments.joinToString("/")
    }

    private fun validName(raw: String?): String? = raw?.trim()?.takeIf { name ->
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name &&
            !name.startsWith(".nexara", ignoreCase = true) && name != ".recycle_bin"
    }

    private fun webHost(args: JsonObject): String? {
        val raw = args.string("url")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if ("://" in raw && !raw.startsWith("http://") && !raw.startsWith("https://")) return null
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return uri.host?.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
    }

    private fun childPath(parent: String, name: String): String = parent.trimEnd('/') + "/" + name

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun tuple(vararg parts: String): String = JsonArray(parts.map(::JsonPrimitive)).toString()
}
