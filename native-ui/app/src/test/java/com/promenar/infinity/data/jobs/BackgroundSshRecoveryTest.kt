package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BackgroundSshRecoveryTest {
    @Test fun `重启将SSH任务标记中断并保留日志且不进入本机恢复`() = runBlocking {
        val root = Files.createTempDirectory("ssh-job-recovery").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = BackgroundJobStore(File(root, "store"))
            val run = File(root, "runs/job-test").apply { mkdirs() }
            File(run, "output.log").writeText("已遮蔽的远端输出")
            store.put(BackgroundJobRecord("job-test", "chat", "root", "SSH", "远端命令", startedAtMillis = System.currentTimeMillis(),
                maxRuntimeMinutes = 120, onExit = "none", logWorkspacePath = "job-log:job-test", runDirectory = run.path))
            val manager = BackgroundJobManager(scope, store, File(root, "runs"), BuiltinCommandRunner(),
                prepareWorkspace = { error("SSH 不使用本机工作区") }, prepareCommand = { _, _, _, _, _ -> error("SSH 不执行本机命令") },
                reconcile = { _, _, _ -> error("SSH 不执行本机恢复") })
            manager.initialize()
            val result = manager.status("chat", "job-test")
            assertThat(result.record.state).isEqualTo("INTERRUPTED")
            assertThat(result.record.guidance).contains("远端进程可能仍在运行")
            assertThat(result.tail.text).isEqualTo("已遮蔽的远端输出")
            assertThat(manager.list("chat").single().job().runner.name).isEqualTo("SSH")
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
