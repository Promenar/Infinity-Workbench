package com.promenar.nexara.data.subagent

import com.promenar.nexara.data.model.TokenUsage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 子代理只与主 Agent 通信，界面只能读取；用量待父轮次汇入其助手消息。 */
interface SubagentUsageLedger {
    fun add(sessionId: String, usage: TokenUsage)
    fun drain(sessionId: String): TokenUsage?
}

/**
 * 子代理运行记录：运行中保存在内存供界面实时读取，同时写入应用私有的不备份目录；
 * 父会话删除时整目录删除。进程重启后仍为运行中的记录显示为已中断。
 */
class SubagentRunStore(
    private val root: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SubagentUsageLedger {
    private val live = MutableStateFlow<Map<String, SubagentBatch>>(emptyMap())
    private val mutex = Mutex()
    private val usage = ConcurrentHashMap<String, TokenUsage>()
    /** 已删除的父会话：仍在收尾的子代理不能再写回记录或用量。会话 id 不复用，只需保存在内存。 */
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    /** 运行中读内存；结束或重启后读磁盘，失去执行者的运行显示为已中断。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(sessionId: String, stepId: String): Flow<SubagentBatch?> {
        val key = key(sessionId, stepId)
        return live.map { it[key] }.distinctUntilChanged()
            .mapLatest { current -> current ?: load(sessionId, stepId) }
            .distinctUntilChanged()
    }

    fun snapshot(sessionId: String, stepId: String): SubagentBatch? = live.value[key(sessionId, stepId)]

    /** 运行中更新：内存立即可见，磁盘按调用方节奏写入。 */
    suspend fun update(batch: SubagentBatch, persist: Boolean) {
        if (batch.sessionId in deleted) return
        live.update { it + (key(batch.sessionId, batch.stepId) to batch) }
        if (persist) write(batch)
    }

    suspend fun finish(batch: SubagentBatch) {
        if (batch.sessionId in deleted) return
        write(batch)
        live.update { it - key(batch.sessionId, batch.stepId) }
    }

    suspend fun load(sessionId: String, stepId: String): SubagentBatch? = withContext(dispatcher) {
        try {
            mutex.withLock {
                val entry = entry(sessionId, stepId, create = false) ?: return@withLock null
                if (!Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS)) return@withLock null
                val batch = json.decodeFromString(SubagentBatch.serializer(), entry.readText(Charsets.UTF_8))
                if (batch.sessionId != sessionId || batch.stepId != stepId) return@withLock null
                if (live.value[key(sessionId, stepId)] == null && batch.runs.any { it.status.isActive }) batch.interrupted() else batch
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun deleteSession(sessionId: String): Unit = withContext(dispatcher) {
        mutex.withLock {
            deleted += sessionId
            live.update { batches -> batches.filterValues { it.sessionId != sessionId } }
            usage.remove(sessionId)
            val storeRoot = checkedRoot(create = false) ?: return@withLock
            val session = File(storeRoot, sha256(sessionId))
            checkChild(session, storeRoot)
            deleteTree(session)
        }
    }

    override fun add(sessionId: String, usage: TokenUsage) {
        if (sessionId in deleted) return
        if (usage.total <= 0 && usage.input <= 0 && usage.output <= 0) return
        this.usage.merge(sessionId, usage) { old, new ->
            TokenUsage(
                input = saturated(old.input, new.input),
                output = saturated(old.output, new.output),
                total = saturated(old.total, new.total),
                estimated = old.estimated || new.estimated,
            )
        }
    }

    override fun drain(sessionId: String): TokenUsage? = usage.remove(sessionId)

    private suspend fun write(batch: SubagentBatch) = withContext(dispatcher) {
        try {
            mutex.withLock {
                if (batch.sessionId in deleted) return@withLock
                val entry = entry(batch.sessionId, batch.stepId, create = true) ?: return@withLock
                val temporary = File(entry.parentFile, ".${entry.name}-${UUID.randomUUID()}.tmp")
                try {
                    FileOutputStream(temporary).use { stream ->
                        stream.write(json.encodeToString(SubagentBatch.serializer(), batch).toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    checkChild(entry, entry.parentFile)
                    try {
                        Files.move(temporary.toPath(), entry.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), entry.toPath(), REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporary.toPath())
                }
            }
        } catch (_: IOException) {
            // 记录只供查看；写盘失败不影响子代理结果交回主 Agent。
        } catch (_: SecurityException) {
            // 应用私有目录不可用时只保留内存记录。
        }
    }

    private fun entry(sessionId: String, stepId: String, create: Boolean): File? {
        val storeRoot = checkedRoot(create) ?: return null
        val session = File(storeRoot, sha256(sessionId))
        checkChild(session, storeRoot)
        if (!Files.exists(session.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            if (!session.mkdir() && !session.isDirectory) throw IOException("子代理记录目录无法创建。")
        }
        if (!Files.isDirectory(session.toPath(), NOFOLLOW_LINKS)) throw IOException("子代理记录目录不可用。")
        return File(session, "${sha256(stepId)}.json").also { checkChild(it, session) }
    }

    private fun checkedRoot(create: Boolean): File? {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("子代理记录根目录不可用。")
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            if (!root.mkdirs() && !root.isDirectory) throw IOException("子代理记录目录无法创建。")
        }
        if (!root.isDirectory) throw IOException("子代理记录目录不可用。")
        return root.canonicalFile
    }

    private fun checkChild(child: File, parent: File) {
        if (Files.isSymbolicLink(child.toPath()) || child.canonicalFile.parentFile != parent.canonicalFile) {
            throw IOException("子代理记录路径不安全。")
        }
    }

    private fun deleteTree(entry: File) {
        if (Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)) {
            val children = entry.listFiles() ?: throw IOException("子代理记录无法清理。")
            children.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) Files.deleteIfExists(child.toPath())
                else {
                    checkChild(child, entry)
                    deleteTree(child)
                }
            }
        }
        Files.deleteIfExists(entry.toPath())
    }

    private fun key(sessionId: String, stepId: String) = "$sessionId\u0000$stepId"

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun saturated(a: Int, b: Int) = (a.toLong() + b).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** 进程重启后没有执行者的运行记录。 */
internal fun SubagentBatch.interrupted(): SubagentBatch = copy(
    runs = runs.map { run ->
        if (run.status.isActive) run.copy(status = SubagentStatus.INTERRUPTED, currentTool = null, liveOutputKey = null) else run
    },
)
