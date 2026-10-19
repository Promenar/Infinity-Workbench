package com.promenar.nexara.domain.tool

import com.promenar.nexara.data.remote.protocol.ProtocolTool
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 完整授权目标使用 JSON 元组编码；显示格式不参与授权比较。 */
object ToolExecutionTargets {
    fun extract(
        tool: ProtocolTool,
        arguments: String,
        resolvedPaths: Map<String, String> = emptyMap(),
        sshIdentities: Map<String, SshExecutionIdentity> = emptyMap(),
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
        if (tool.function.name == "ssh_session_send") {
            val id = args.string("session_id") ?: return null
            val host = resolvedPaths["ssh_session:$id"]?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            val input = args.string("input") ?: return null
            return sshTarget("process", host, input, sshIdentities)
        }
        if (tool.function.name == "ssh_session_open") {
            val host = args.string("host")?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            return sshTarget("process", host, "[PTY]", sshIdentities)
        }
        if (tool.function.name in setOf("sftp_list", "sftp_read", "sftp_write")) {
            val host = args.string("host")?.takeIf { SSH_ALIAS.matches(it) } ?: return null
            val path = args.string("path")?.takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Character::isISOControl) } ?: return null
            // 远端相对路径和符号链接的语义由服务器决定，授权键保留原始路径。
            return sshTarget("remote_path", host, path, sshIdentities)
        }
        if (tool.risk == ToolRisk.PROCESS && tool.function.name == "python_exec" && tool.sourceId == "builtin") {
            val code = args.string("code")?.takeIf(String::isNotBlank) ?: return null
            return tuple("process", "python", code)
        }
        if (tool.risk == ToolRisk.PROCESS) {
            val host = when (tool.function.name) {
                "termux_exec" -> "termux"
                "shell_exec" -> "builtin".takeIf { tool.sourceId == "builtin" }
                else -> null
            } ?: args.string("host")?.trim()?.takeIf(String::isNotBlank) ?: return null
            val command = args.string("command")?.takeIf(String::isNotBlank) ?: return null
            if (tool.function.name == "ssh_exec") {
                if (!SSH_ALIAS.matches(host)) return null
                return sshTarget("process", host, command, sshIdentities)
            }
            return tuple("process", host.lowercase(Locale.ROOT), command)
        }
        val target = when (tool.function.name) {
            "read_file", "write_file", "patch_file", "diff_file", "delete_file", "run_script" ->
                entryPath(args, resolvedPaths)?.let { tuple("path", it) }
            "create_file", "create_directory" -> {
                val name = validName(args.string("name")) ?: return null
                val parent = entryPath(args, resolvedPaths, "parentUuid", "parentPath", "/") ?: return null
                childPath(parent, name).takeUnless { it == "/mnt" }?.let { tuple("path", it) }
            }
            "move_file" -> moveTarget(args, resolvedPaths)
            "list_files" -> entryPath(args, resolvedPaths, "parentUuid", "path", "/")
                ?.let { tuple("path", it) }
            "search_files" -> args.string("query")?.takeIf(String::isNotBlank)?.let { tuple("path", "/") }
            "web_fetch" -> webHost(args)?.let { tuple("host", it) }
            "exec_js", "python_sandbox" -> args.string("code")?.let { tuple("tool", tool.function.name) }
            else -> null
        }
        return if (target != null && tool.function.name == "delete_file" && (args["irreversible"] as? JsonPrimitive)?.booleanOrNull == true) {
            JsonArray((Json.parseToJsonElement(target) as JsonArray) + JsonObject(mapOf("oneShot" to JsonPrimitive(true)))).toString()
        } else target
    }

    fun canRemember(target: String): Boolean = runCatching {
        val metadata = (Json.parseToJsonElement(target) as? JsonArray)?.lastOrNull() as? JsonObject
        (metadata?.get("oneShot") as? JsonPrimitive)?.booleanOrNull != true
    }.getOrDefault(true)

    /** 清除不可见控制字符与格式控制后按 UTF-16 长度限制显示，保留完整代理对。 */
    /** 进程目标的执行位置（如 builtin、python、termux）；命令正文另行完整展示时用它代替整条目标，避免重复。 */
    fun processScope(target: String): String? {
        val parts = runCatching { Json.parseToJsonElement(target) as? JsonArray }.getOrNull()
            ?.let { array -> if (array.lastOrNull() is JsonObject) JsonArray(array.dropLast(1)) else array }
            ?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null }
            ?: return null
        return parts.takeIf { it.size == 3 && it[0] == "process" }?.get(1)?.let { display(it) }?.takeIf(String::isNotBlank)
    }

    fun display(target: String, maxChars: Int = 240): String {
        if (maxChars <= 0) return ""
        val parts = runCatching { Json.parseToJsonElement(target) as? JsonArray }.getOrNull()
            ?.let { array -> if (array.lastOrNull() is JsonObject) JsonArray(array.dropLast(1)) else array }
            ?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return@map null }
            ?.takeIf { it.none { part -> part == null } }
            ?.filterNotNull()
            .orEmpty()
        val readable = when (parts.firstOrNull()) {
            "path", "host", "tool" -> parts.getOrNull(1)?.takeIf { parts.size == 2 }
            "move" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} → ${it[2]}" }
            "mcp" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} / ${it[2]}" }
            "process", "remote_path" -> parts.takeIf { it.size == 3 }?.let { "${it[1]} · ${it[2]}" }
            else -> null
        } ?: target
        // 换行与制表符显示为可见符号，多行命令不会被拼成看似不同的单行；其它控制与双向格式字符剥离。
        val safe = buildString {
            var offset = 0
            while (offset < readable.length) {
                val point = readable.codePointAt(offset)
                when {
                    point == '\n'.code -> append('↵')
                    point == '\r'.code -> if (readable.getOrNull(offset + 1) != '\n') append('↵')
                    point == '\t'.code -> append('⇥')
                    !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt() -> appendCodePoint(point)
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
        val destination = childPath(parent, newName ?: source.substringAfterLast('/'))
        if (source == "/mnt" || destination == "/mnt") return null
        return tuple("move", source, destination)
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
            segments.firstOrNull() == ".recycle_bin" ||
            segments.firstOrNull() == "mnt" && segments.drop(1).any { it == ".recycle_bin" }
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
    private fun sshTarget(kind: String, host: String, value: String, identities: Map<String, SshExecutionIdentity>): String? {
        val identity = identities[host] ?: return null
        if (identity.hostId.isBlank() || identity.digest.isBlank()) return null
        return JsonArray(listOf(JsonPrimitive(kind), JsonPrimitive(host), JsonPrimitive(value), JsonObject(mapOf(
            "sshHostId" to JsonPrimitive(identity.hostId),
            "sshIdentity" to JsonPrimitive(identity.digest),
        )))).toString()
    }
    private val SSH_ALIAS = Regex("[a-z][a-z0-9_-]{0,47}")
}
