package com.promenar.nexara.data.backup

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.promenar.nexara.MainActivity
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.utils.NexaraLogger

/**
 * 独立进程重启中继。Intent 只携带主进程 PID；恢复包、密码、事务 ID 均只存在加密 pending store。
 */
class RestoreRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(application as NexaraApplication).restoreRelayEarlyExit) return finishAndRemoveTask()
        if (intent?.action != ACTION_RESTART_MAIN) return finishAndRemoveTask()
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        if (mainPid <= 0 || mainPid == Process.myPid()) return finishAndRemoveTask()
        val belongsToMainProcess = getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.any { it.pid == mainPid && it.processName == packageName } == true
        if (!belongsToMainProcess) return finishAndRemoveTask()

        NexaraLogger.log("[NexaraRestoreRelay] relay_pid=${Process.myPid()} main_pid=$mainPid payload=none")
        Process.killProcess(mainPid)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finishAndRemoveTask()
    }

    companion object {
        const val ACTION_RESTART_MAIN = "com.promenar.nexara.action.RESTART_FOR_RESTORE"
        internal const val EXTRA_MAIN_PID = "main_pid"
        const val PROCESS_SUFFIX = ":restore_relay"

        fun requestRestart(context: Context) {
            context.startActivity(
                Intent(context, RestoreRelayActivity::class.java)
                    .setAction(ACTION_RESTART_MAIN)
                    .putExtra(EXTRA_MAIN_PID, Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
            )
        }
    }
}
