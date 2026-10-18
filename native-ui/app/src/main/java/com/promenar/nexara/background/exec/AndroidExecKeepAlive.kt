package com.promenar.nexara.background.exec

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.promenar.nexara.data.exec.ExecForegroundState
import com.promenar.nexara.data.exec.ExecHeld
import com.promenar.nexara.data.exec.ExecKeepAlive
import com.promenar.nexara.data.exec.ExecRunInfo
import com.promenar.nexara.data.exec.ExecRunRegistry

/**
 * 命令运行期间持有 `PARTIAL_WAKE_LOCK`（超时上限加余量，结束即释放），并在 [foregroundServiceAvailable] 时
 * 启动 [ExecForegroundService] 显示命令摘要与停止按钮。前台服务被系统拒绝时命令照常运行，只是没有通知。
 */
class AndroidExecKeepAlive(
    context: Context,
    private val registry: ExecRunRegistry,
    private val foregroundServiceAvailable: Boolean,
) : ExecKeepAlive {
    private val applicationContext = context.applicationContext
    private val power = applicationContext.getSystemService(PowerManager::class.java)

    override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> {
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(run.timeoutSeconds * 1_000L + WAKE_LOCK_MARGIN_MILLIS)
        }
        registry.register(ExecRunRegistry.Active(run, stop))
        try {
            val foreground = if (foregroundServiceAvailable) startForeground() else ExecForegroundState.UNSUPPORTED
            return ExecHeld(block(), foreground)
        } finally {
            registry.unregister(run.id)
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun startForeground(): ExecForegroundState = try {
        ContextCompat.startForegroundService(applicationContext, ExecForegroundService.startIntent(applicationContext))
        ExecForegroundState.STARTED
    } catch (_: ForegroundServiceStartNotAllowedException) {
        ExecForegroundState.NOT_ALLOWED
    } catch (_: SecurityException) {
        ExecForegroundState.NOT_ALLOWED
    } catch (_: IllegalStateException) {
        ExecForegroundState.NOT_ALLOWED
    }

    private companion object {
        const val WAKE_LOCK_TAG = "Nexara:builtin-exec"
        /** 覆盖等待写锁、终止宽限与对账；正常路径在 finally 中释放，上限只防泄漏。 */
        const val WAKE_LOCK_MARGIN_MILLIS = 5 * 60_000L
    }
}
