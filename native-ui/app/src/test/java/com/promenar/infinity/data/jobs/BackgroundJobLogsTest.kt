package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class BackgroundJobLogsTest {
    @Test fun `迁移明确归属的日志及轮转文件且重复运行不改用户文件`() = fixture { root, record ->
        val workspace = File(record.workspaceDirectory!!)
        val jobs = File(workspace, "jobs").apply { mkdirs() }
        File(jobs, "job-a.log").writeText("current")
        File(jobs, "job-a.log.1").writeText("previous")
        File(jobs, "user.log").writeText("user")
        BackgroundJobLogs.migrate(record, root)
        BackgroundJobLogs.migrate(record, root)
        assertThat(BackgroundJobLogs.file(record, root).readText()).isEqualTo("current")
        assertThat(File(record.runDirectory, "output.log.1").readText()).isEqualTo("previous")
        assertThat(File(jobs, "job-a.log").exists()).isFalse()
        assertThat(File(jobs, "user.log").readText()).isEqualTo("user")
    }

    @Test fun `中断迁移可重跑而新旧内容冲突时保留两份`() = fixture { root, record ->
        val jobs = File(record.workspaceDirectory!!, "jobs").apply { mkdirs() }
        val old = File(jobs, "job-a.log").apply { writeText("original") }
        val target = BackgroundJobLogs.file(record, root).apply { parentFile!!.mkdirs() }
        File(target.parentFile, "output.log.migration").writeText("partial")
        BackgroundJobLogs.migrate(record, root)
        assertThat(target.readText()).isEqualTo("original")
        old.parentFile!!.mkdirs()
        old.writeText("different")
        assertThat(runCatching { BackgroundJobLogs.migrate(record, root) }.exceptionOrNull()).isNotNull()
        assertThat(old.readText()).isEqualTo("different")
        assertThat(target.readText()).isEqualTo("original")
    }

    @Test fun `路径越界及符号链接日志拒绝迁移`() = fixture { root, record ->
        val outside = File(root.parentFile, "outside").apply { writeText("outside") }
        val jobs = File(record.workspaceDirectory!!, "jobs").apply { mkdirs() }
        Files.createSymbolicLink(File(jobs, "job-a.log").toPath(), outside.toPath())
        assertThat(runCatching { BackgroundJobLogs.migrate(record, root) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { BackgroundJobLogs.file(record.copy(runDirectory = root.parent), root) }.exceptionOrNull()).isNotNull()
        assertThat(outside.readText()).isEqualTo("outside")
    }

    @Test fun `日志引用不包含物理路径且仅接受任务编号`() {
        assertThat(BackgroundJobLogs.jobId(BackgroundJobLogs.reference("job-a"))).isEqualTo("job-a")
        assertThat(BackgroundJobLogs.jobId("job-log:../secret")).isNull()
        assertThat(BackgroundJobLogs.jobId("jobs/job-a.log")).isNull()
    }

    private fun fixture(block: (File, BackgroundJobRecord) -> Unit) {
        val temporary = Files.createTempDirectory("private-job-logs").toFile()
        try {
            val runs = File(temporary, "runs").apply { mkdirs() }
            val workspace = File(temporary, "workspace").apply { mkdirs() }
            val record = BackgroundJobRecord("job-a", "s", "r", "SHELL", "echo", startedAtMillis = 0,
                maxRuntimeMinutes = 1, onExit = "none", logWorkspacePath = "jobs/job-a.log",
                runDirectory = File(runs, "job-a").path, workspaceDirectory = workspace.path)
            block(runs, record)
        } finally { temporary.deleteRecursively() }
    }
}
