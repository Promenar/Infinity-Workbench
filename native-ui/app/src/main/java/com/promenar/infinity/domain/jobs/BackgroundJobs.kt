package com.promenar.infinity.domain.jobs

import kotlinx.coroutines.flow.Flow

enum class BackgroundJobRunner { SHELL, PYTHON, LINUX, TERMUX, SSH }

enum class BackgroundJobState {
    RUNNING,
    /** 进程自行退出，退出码见 [BackgroundJob.exitCode]。 */
    EXITED,
    /** 被用户或 `job_stop` 停止。 */
    STOPPED,
    /** 超过最长运行时间被终止。 */
    TIMED_OUT,
    /** 应用进程重启时任务仍在运行，已清理残留进程。 */
    INTERRUPTED,
    /** 启动失败或状态无法确认。 */
    FAILED,
    ;

    val isTerminal: Boolean get() = this != RUNNING
}

/** 任务结束时的处理方式。 */
enum class BackgroundJobExitAction(val wireName: String) {
    NONE("none"),
    NOTIFY("notify"),
    WAKE("wake"),
    ;

    companion object {
        fun fromWire(value: String?): BackgroundJobExitAction? = entries.firstOrNull { it.wireName == value }
    }
}

data class BackgroundJob(
    val id: String,
    val sessionId: String,
    val runner: BackgroundJobRunner,
    /** 单行命令摘要。 */
    val summary: String,
    val state: BackgroundJobState,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val maxRuntimeMinutes: Int,
    val onExit: BackgroundJobExitAction,
    /** 任务日志引用，由 BackgroundJobController.readLog 读取，不是工作区路径。 */
    val logWorkspacePath: String?,
    /** 实时输出在 `ToolLiveOutputStore` 中的键，供界面复用实时输出面板。 */
    val liveOutputKey: String,
    /** [BackgroundJobRunner.SSH] 任务所在的主机 ID，与运行环境标签一起显示；本地任务为 null。 */
    val remoteHost: String? = null,
)

data class BackgroundJobLog(val text: String, val truncated: Boolean)

/** 界面使用的后台任务入口。 */
interface BackgroundJobController {
    /** [sessionId] 为 null 时返回全部会话的任务，按启动时间倒序。 */
    fun observe(sessionId: String?): Flow<List<BackgroundJob>>

    /** 会话归属校验后读取应用私有日志，兼容实时任务的日志末尾。 */
    suspend fun readLog(sessionId: String, reference: String): BackgroundJobLog =
        throw UnsupportedOperationException("任务日志读取不可用")

    /** 返回 false 表示任务不存在或已经结束。 */
    suspend fun stop(jobId: String): Boolean
}
