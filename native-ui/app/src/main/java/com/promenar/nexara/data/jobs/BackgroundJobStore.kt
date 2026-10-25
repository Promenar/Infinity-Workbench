package com.promenar.nexara.data.jobs

import com.promenar.nexara.domain.jobs.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackgroundJobRecord(
    val id: String,
    val sessionId: String,
    val workspaceRootUuid: String,
    val runner: String,
    val summary: String,
    val state: String = "RUNNING",
    val exitCode: Int? = null,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val maxRuntimeMinutes: Int,
    val onExit: String,
    val logWorkspacePath: String?,
    val runDirectory: String,
    val workspaceDirectory: String? = null,
    val termuxDirectory: String? = null,
    val termuxPid: Int? = null,
    val guidance: String? = null,
    val isolatedWorkspace: Boolean = false,
    /** 无法确认旧writer已停止时保留原日志目录项，避免unlink后丢失追加输出。 */
    val logMigrationDeferred: Boolean = false,
) {
    /** 未知执行器按 SHELL 展示，持久化原值保留，不据此启动新命令。 */
    val runnerKind: BackgroundJobRunner get() = BackgroundJobRunner.entries.firstOrNull { it.name == runner } ?: BackgroundJobRunner.SHELL
    fun job() = BackgroundJob(id,sessionId,runnerKind,summary,BackgroundJobState.valueOf(state),exitCode,startedAtMillis,endedAtMillis,maxRuntimeMinutes,BackgroundJobExitAction.fromWire(onExit) ?: BackgroundJobExitAction.NONE,BackgroundJobLogs.reference(id),"job:$id")
    val running: Boolean get() = state == "RUNNING"
}

/** 文件提交完成才更新内存；损坏文件保留并拒绝启动，避免遗失进程归属。 */
class BackgroundJobStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val pruned = mutableListOf<BackgroundJobRecord>()
    private var records: List<BackgroundJobRecord>? = null
    private fun load(): List<BackgroundJobRecord> = records ?: File(root,"records.json").let { file ->
        (if(file.exists()) json.decodeFromString<List<BackgroundJobRecord>>(file.readText()) else emptyList()).also { records=it }
    }
    private fun save(values: List<BackgroundJobRecord>) {
        val terminal = values.filterNot { it.running }.filter { (it.endedAtMillis ?: it.startedAtMillis) >= now()-7*86400000L }
            .sortedByDescending { it.endedAtMillis }.take(50)
        val next = values.filter { it.running } + terminal
        check(root.isDirectory || root.mkdirs()) { "无法创建后台任务记录目录" }
        val temp=File(root,"records.json.tmp")
        try {
            FileOutputStream(temp).use { it.write(json.encodeToString(next).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp.toPath(),File(root,"records.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            pruned += values.filter { old -> next.none { it.id == old.id } }
            records=next
        } finally { temp.delete() }
    }
    private suspend fun <T> locked(block: ()->T):T=withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { block() } }
    suspend fun updateIfPresent(id:String, transform:(BackgroundJobRecord)->BackgroundJobRecord):BackgroundJobRecord? = locked {
        val values=load()
        val old=values.find{it.id==id} ?: return@locked null
        val updated=transform(old)
        require(updated.id==old.id && updated.sessionId==old.sessionId)
        save(values.map{if(it.id==id)updated else it})
        updated
    }
    suspend fun drainPruned():List<BackgroundJobRecord> = locked {pruned.toList().also{pruned.clear()}}
    suspend fun prune() = locked { save(load()) }
    suspend fun all():List<BackgroundJobRecord> = locked { load().toList() }
    suspend fun put(record:BackgroundJobRecord) = locked { save(load().filterNot { it.id==record.id }+record) }
    suspend fun deleteSession(sessionId:String) = locked { save(load().filterNot{it.sessionId==sessionId}) }
}
