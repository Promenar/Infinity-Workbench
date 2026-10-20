package com.promenar.nexara.data.repository

import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.data.storage.namespaceBindings
import com.promenar.nexara.data.workspace.SessionWorkspace
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.storage.ExternalWorkspaceAccess
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class ToolAllowlistEntry(
    val id: String,
    val toolName: String,
    val target: String,
    val scopeKey: String?,
    val createdAt: Long,
    val sourceSessionId: String?,
)

/** 非空结果中的 null 表示全局作用域；解析失败直接返回 null。 */
data class ToolAllowlistScope(val scopeKey: String?)

/** 审批记忆、运行时筛选和执行前复验共同使用的存储身份解析。 */
object ToolAllowlistScopes {
    suspend fun resolveForSession(
        sessionId: String,
        target: String,
        workspace: (suspend (String) -> SessionWorkspace)? = null,
        mounts: ExternalWorkspaceAccess? = null,
    ): ToolAllowlistScope? {
        val parts = parts(target) ?: return null
        if (parts.first() !in PATH_KINDS) return resolve(target, sessionId, null)
        return try {
            val ids = mounts?.namespaceBindings(sessionId).orEmpty()
            val needsWorkspace = parts.drop(1).any { ExternalMountPaths.parse(it) == null }
            resolve(target, sessionId, if (needsWorkspace) workspace?.invoke(sessionId) else null, ids)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    fun resolve(
        target: String,
        sessionId: String,
        workspace: SessionWorkspace?,
        mountIds: Map<String, String> = emptyMap(),
    ): ToolAllowlistScope? {
        val parts = parts(target) ?: return null
        if (parts.first() !in PATH_KINDS) return ToolAllowlistScope(null)
        val metadata = Json.parseToJsonElement(target).jsonArray.lastOrNull() as? JsonObject
        // 历史私有 UUID 的 /mnt 路径无法仅凭虚拟路径判断后端，不生成可复用授权。
        if ((metadata?.get("legacyWorkspaceUuids") as? JsonArray)?.isNotEmpty() == true) return null
        val bindings = metadata?.get("mountIds") as? JsonObject
        if (bindings?.any { (alias, id) -> id.string() != mountIds[alias] } == true) return null
        val scopes = parts.drop(1).map { path ->
            if (!path.startsWith('/') || runCatching { ExternalFolderPaths.normalize(path) }.getOrNull() != path || path == "/mnt") return null
            val mount = runCatching { ExternalMountPaths.parse(path) }.getOrNull()
            if (mount != null) {
                val id = mount?.first?.let(mountIds::get)?.takeIf(String::isNotBlank) ?: return null
                "mount:$id"
            } else when (workspace) {
                SessionWorkspace.Private -> "session:${sessionId.takeIf(String::isNotBlank) ?: return null}"
                is SessionWorkspace.Directory -> "workspace:${workspace.workspace.id.takeIf(String::isNotBlank) ?: return null}"
                else -> return null
            }
        }
        return scopes.distinct().singleOrNull()?.let(::ToolAllowlistScope)
    }

    internal fun validScope(target: String, scopeKey: String?): Boolean {
        val parts = parts(target) ?: return false
        return if (parts.first() in PATH_KINDS) {
            scopeKey != null && scopeKey.substringBefore(':') in setOf("workspace", "session", "mount") &&
                scopeKey.substringAfter(':', "").isNotBlank()
        } else scopeKey == null
    }

    private fun parts(target: String): List<String>? = runCatching {
        if (!ToolExecutionTargets.canRemember(target)) return null
        val array = Json.parseToJsonElement(target) as? JsonArray ?: return null
        val tuple = if (array.lastOrNull() is JsonObject) array.dropLast(1) else array.toList()
        val parts = tuple.map { it.string()?.takeIf(String::isNotBlank) ?: return null }
        val size = when (parts.firstOrNull()) {
            "path", "host", "tool" -> 2
            "move", "process", "mcp", "remote_path" -> 3
            else -> return null
        }
        parts.takeIf { it.size == size }
    }.getOrNull()

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    private val PATH_KINDS = setOf("path", "move")
}

/** [root] 必须位于 noBackupFilesDir；读取以磁盘为准，同进程的实例共享锁与列表。 */
class ToolAllowlistStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    private class StoreState {
        val mutex = Mutex()
        val entries = MutableStateFlow<List<ToolAllowlistEntry>>(emptyList())
    }
    private val state = states.computeIfAbsent(root.absoluteFile.normalize().path) { StoreState() }
    val entries: StateFlow<List<ToolAllowlistEntry>> = state.entries.asStateFlow()

    init {
        // 初次订阅自动加载，磁盘访问不占用主线程。
        CoroutineScope(Dispatchers.IO).launch { locked { refresh() } }
    }

    suspend fun add(toolName: String, target: String, scopeKey: String?, sourceSessionId: String? = null) {
        require(toolName.isNotBlank() && target.isNotBlank()) { "白名单元组不能为空" }
        if (!ToolAllowlistScopes.validScope(target, scopeKey)) return
        locked {
            val current = refresh()
            if (current.none { it.toolName == toolName && it.target == target && it.scopeKey == scopeKey }) {
                write(current + ToolAllowlistEntry(UUID.randomUUID().toString(), toolName, target, scopeKey, now(), sourceSessionId))
            }
        }
    }

    suspend fun contains(toolName: String, target: String, scopeKey: String?): Boolean {
        if (toolName.isBlank() || !ToolAllowlistScopes.validScope(target, scopeKey)) return false
        return locked { refresh().any { it.toolName == toolName && it.target == target && it.scopeKey == scopeKey } }
    }

    suspend fun remove(id: String) {
        locked {
            val current = refresh()
            val updated = current.filterNot { it.id == id }
            if (updated != current) write(updated)
        }
    }

    /** 删除绑定到 [scopeKey] 的全部条目；会话删除后清理其私有工作区的授权。 */
    suspend fun removeScope(scopeKey: String) {
        locked {
            val current = refresh()
            val updated = current.filterNot { it.scopeKey == scopeKey }
            if (updated != current) write(updated)
        }
    }

    suspend fun clear() { locked { write(emptyList()) } }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        state.mutex.withLock { block() }
    }

    private fun refresh(): List<ToolAllowlistEntry> = read().also { state.entries.value = it }

    private fun read(): List<ToolAllowlistEntry> = runCatching {
        val file = File(root, FILE_NAME)
        if (!file.isFile) return@runCatching emptyList()
        val document = json.decodeFromString<Document>(file.readText(Charsets.UTF_8))
        // 技能路径的旧授权失效时保留其它有效授权，不因规则收紧清空整份列表。
        val entries = document.entries.filterNot { ToolExecutionTargets.isSkillsPathTarget(it.target) }
        if (document.version != 1 || entries.any {
                it.id.isBlank() || it.toolName.isBlank() || it.createdAt < 0 || !ToolAllowlistScopes.validScope(it.target, it.scopeKey)
            } || entries.map { it.id }.distinct().size != entries.size ||
            entries.map { Triple(it.toolName, it.target, it.scopeKey) }.distinct().size != entries.size
        ) return@runCatching emptyList()
        sorted(entries)
    }.getOrElse { emptyList() }

    private fun write(entries: List<ToolAllowlistEntry>) {
        check(root.isDirectory || root.mkdirs()) { "无法创建全局白名单目录" }
        val updated = sorted(entries)
        val temporary = File.createTempFile("allowlist-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(Document(1, updated)).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // 同目录原子替换失败时保留原文件，禁止退化为非原子覆盖。
            Files.move(temporary.toPath(), File(root, FILE_NAME).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            state.entries.value = updated
        } finally {
            temporary.delete()
        }
    }

    private fun sorted(entries: List<ToolAllowlistEntry>) = entries.sortedWith(compareByDescending<ToolAllowlistEntry> { it.createdAt }.thenBy { it.id })

    @Serializable private data class Document(val version: Int, val entries: List<ToolAllowlistEntry>)

    private companion object {
        const val FILE_NAME = "allowlist.json"
        val json = Json { encodeDefaults = true }
        val states = ConcurrentHashMap<String, StoreState>()
    }
}
