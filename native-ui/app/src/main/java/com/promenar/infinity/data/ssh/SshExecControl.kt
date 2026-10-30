package com.promenar.infinity.data.ssh

import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.domain.tool.CommandTruncation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/** 命令的应用级控制柄；让出后按后台时限运行，停止只置位，由 IO 线程关闭通道。 */
class SshExecControl(val maxRuntimeSeconds: Int, background: Boolean = false) {
    init { require(maxRuntimeSeconds in 1..86_400) }
    val started = CompletableDeferred<Unit>()
    internal val stopRequested = AtomicBoolean(false)
    internal val finished = AtomicBoolean(false)
    @Volatile internal var background = background
    internal var promote: (suspend (String, String) -> Boolean)? = null
    @Volatile internal var cancelPending: (() -> Unit)? = null
    fun requestStop() { stopRequested.set(true); cancelPending?.invoke() }
}

internal const val SSH_DISCONNECTED_GUIDANCE = "已断开通道；远端进程可能仍在运行。"

internal fun SshExecOutput.commandResult(timeoutSeconds: Int): CommandToolResult = CommandToolResult(
    errorCode = when { stoppedByUser -> "SSH_STOPPED"; timedOut -> "SSH_TIMEOUT"; else -> null },
    guidance = when {
        stoppedByUser -> "用户停止了命令，$SSH_DISCONNECTED_GUIDANCE 不要自动重试，先询问用户。"
        timedOut -> "命令在 $timeoutSeconds 秒内未结束，$SSH_DISCONNECTED_GUIDANCE 重试前请先检查。"
        else -> null
    },
    exitCode = exitCode.takeUnless { timedOut || stoppedByUser },
    timedOut = timedOut,
    stdout = stdout, stderr = stderr,
    stdoutOriginalLength = stdoutOriginalLength, stderrOriginalLength = stderrOriginalLength,
    stdoutTruncated = stdoutOriginalLength?.let { it > stdout.length } ?: true,
    stderrTruncated = stderrOriginalLength?.let { it > stderr.length } ?: true,
    removedWhenTruncated = CommandTruncation.TAIL_REMOVED,
)
