package com.promenar.infinity.data.exec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** [LINUX] 为在 Linux 环境中运行的 shell 命令，[TERMINAL] 为本地终端会话。 */
enum class ExecRunKind { SHELL, PYTHON, LINUX, TERMINAL }

/** 运行中内置命令的展示信息；[summary] 已压缩为单行，可直接放进通知。 */
data class ExecRunInfo(
    val id: String,
    val kind: ExecRunKind,
    val summary: String,
    val timeoutSeconds: Int,
    val startedAtMillis: Long,
    val background: Boolean = false,
) {
    companion object {
        const val SUMMARY_MAX_CHARS = 80

        /** 取第一行非空内容；多行时追加省略号，超长时截断且不留下半个代理对。 */
        fun summarize(text: String): String {
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val first = lines.firstOrNull().orEmpty()
            val more = lines.size > 1
            if (first.length <= SUMMARY_MAX_CHARS) return if (more) "$first …" else first
            var end = SUMMARY_MAX_CHARS - 1
            if (Character.isHighSurrogate(first[end - 1])) end -= 1
            return first.substring(0, end) + "…"
        }
    }
}

/** 命令期间的前台服务状态，决定结果中的后台指引。 */
enum class ExecForegroundState {
    /** 前台服务已启动，通知栏有停止入口。 */
    STARTED,
    /** 当前分发渠道不提供命令前台服务（Play），只持有唤醒锁。 */
    UNSUPPORTED,
    /** 系统拒绝启动前台服务（通常是应用在后台且无豁免），只持有唤醒锁。 */
    NOT_ALLOWED,
}

data class ExecHeld<T>(val value: T, val foreground: ExecForegroundState)

/** 在命令运行期间保持进程可运行：唤醒锁、前台服务通知与停止入口。 */
interface ExecKeepAlive {
    /** [stop] 由通知的停止按钮在任意线程调用；[block] 结束（含取消）后立即释放全部资源。 */
    suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T>
}

object NoExecKeepAlive : ExecKeepAlive {
    override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> =
        ExecHeld(block(), ExecForegroundState.UNSUPPORTED)
}

/** 运行中命令的登记表；前台服务据此渲染通知并转发停止请求。 */
class ExecRunRegistry {
    class Active(val info: ExecRunInfo, internal val stop: () -> Unit)

    private val state = MutableStateFlow<List<Active>>(emptyList())
    val runs: StateFlow<List<Active>> = state.asStateFlow()

    fun register(active: Active) = state.update { it + active }

    fun unregister(id: String) = state.update { runs -> runs.filterNot { it.info.id == id } }

    /** [ids] 为空时停止全部；已结束的命令被忽略。返回实际收到停止请求的命令数。 */
    fun stop(ids: Collection<String> = emptyList()): Int {
        val targets = state.value.filter { ids.isEmpty() || it.info.id in ids }
        targets.forEach { runCatching(it.stop) }
        return targets.size
    }
}

/** 通知展示内容：以最近启动的命令为摘要，停止按钮只作用于渲染时列出的命令。 */
data class ExecNotificationModel(
    val count: Int,
    val latest: ExecRunInfo?,
    val stopIds: List<String>,
) {
    companion object {
        fun from(runs: List<ExecRunRegistry.Active>) = ExecNotificationModel(
            count = runs.size,
            latest = runs.maxByOrNull { it.info.startedAtMillis }?.info,
            stopIds = runs.map { it.info.id },
        )
    }
}
