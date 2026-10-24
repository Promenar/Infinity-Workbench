package com.promenar.nexara.domain.jobs

import kotlinx.coroutines.flow.Flow

enum class BackgroundJobRunner { SHELL, PYTHON, LINUX, TERMUX }

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
    /** 相对会话工作区的日志路径；日志在 Termux 私有目录时为 null。 */
    val logWorkspacePath: String?,
    /** 实时输出在 `ToolLiveOutputStore` 中的键，供界面复用实时输出面板。 */
    val liveOutputKey: String,
)

/** 界面使用的后台任务入口。 */
interface BackgroundJobController {
    /** [sessionId] 为 null 时返回全部会话的任务，按启动时间倒序。 */
    fun observe(sessionId: String?): Flow<List<BackgroundJob>>

    /** 返回 false 表示任务不存在或已经结束。 */
    suspend fun stop(jobId: String): Boolean
}
