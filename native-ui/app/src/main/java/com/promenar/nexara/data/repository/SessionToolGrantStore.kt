package com.promenar.nexara.data.repository

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SessionToolGrant(val toolName: String, val target: String)

/** [root] 必须是应用私有目录；每次读取磁盘，撤销对同进程的其它实例立即生效。 */
class SessionToolGrantStore(private val root: File) {
    suspend fun grant(sessionId: String, toolName: String, target: String) {
        require(sessionId.isNotBlank() && toolName.isNotBlank() && target.isNotBlank()) { "授权元组不能为空" }
        if (!com.promenar.nexara.domain.tool.ToolExecutionTargets.canRemember(target)) return
        locked {
            val current = read(sessionId)
            val grant = SessionToolGrant(toolName, target)
            if (grant !in current) write(sessionId, current + grant)
        }
    }

    suspend fun contains(sessionId: String, toolName: String, target: String): Boolean {
        if (sessionId.isBlank() || toolName.isBlank() || target.isBlank()) return false
        if (!com.promenar.nexara.domain.tool.ToolExecutionTargets.canRemember(target)) return false
        return locked { SessionToolGrant(toolName, target) in read(sessionId) }
    }

    suspend fun list(sessionId: String): List<SessionToolGrant> {
        if (sessionId.isBlank()) return emptyList()
        return locked { read(sessionId) }
    }

    suspend fun revoke(sessionId: String, toolName: String, target: String) {
        if (sessionId.isBlank() || toolName.isBlank() || target.isBlank()) return
        locked {
            val current = read(sessionId)
            val updated = current - SessionToolGrant(toolName, target)
            if (updated != current) {
                if (updated.isEmpty()) delete(sessionId) else write(sessionId, updated)
            }
        }
    }

    suspend fun deleteSession(sessionId: String) {
        if (sessionId.isBlank()) return
        locked { delete(sessionId) }
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        locks.computeIfAbsent(root.canonicalPath) { Mutex() }.withLock { block() }
    }

    private fun read(sessionId: String): List<SessionToolGrant> {
        val file = sessionFile(sessionId)
        if (!file.isFile) return emptyList()
        // 记录不完整、版本未知或会话身份不符时一律不产生授权。
        return runCatching {
            val document = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: return@runCatching emptyList()
            if (document["version"] != JsonPrimitive(1) || document["sessionId"] != JsonPrimitive(sessionId)) {
                return@runCatching emptyList()
            }
            val grants = document["grants"] as? JsonArray ?: return@runCatching emptyList()
            grants.map { item ->
                val tuple = item as? JsonArray ?: return@runCatching emptyList()
                if (tuple.size != 2) return@runCatching emptyList()
                val name = tuple[0].string() ?: return@runCatching emptyList()
                val target = tuple[1].string() ?: return@runCatching emptyList()
                if (name.isBlank() || target.isBlank()) return@runCatching emptyList()
                SessionToolGrant(name, target)
            }.distinct().sortedWith(compareBy(SessionToolGrant::toolName, SessionToolGrant::target))
        }.getOrElse { emptyList() }
    }

    private fun write(sessionId: String, grants: List<SessionToolGrant>) {
        check(root.isDirectory || root.mkdirs()) { "无法创建会话授权目录" }
        val document = JsonObject(mapOf(
            "version" to JsonPrimitive(1),
            "sessionId" to JsonPrimitive(sessionId),
            "grants" to JsonArray(grants.sortedWith(compareBy(SessionToolGrant::toolName, SessionToolGrant::target)).map { grant ->
                JsonArray(listOf(JsonPrimitive(grant.toolName), JsonPrimitive(grant.target)))
            }),
        ))
        val temporary = File.createTempFile("grants-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(document.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // 同目录原子替换；不支持原子移动时失败关闭，不以非原子覆盖替代。
            Files.move(
                temporary.toPath(),
                sessionFile(sessionId).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun delete(sessionId: String) {
        val file = sessionFile(sessionId)
        check(!file.exists() || file.delete()) { "无法清理会话授权" }
    }

    private fun sessionFile(sessionId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "$key.json")
    }

    private fun kotlinx.serialization.json.JsonElement.string(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
