package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.jobs.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BackgroundJobStoreTest {
    @Test fun `旧任务和未知执行器可读取且未知原值在保存后保留`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-compat").toFile()
        try {
            java.io.File(root,"records.json").writeText("""[{"id":"legacy","sessionId":"s","workspaceRootUuid":"root","runner":"SHELL","summary":"echo","startedAtMillis":1,"maxRuntimeMinutes":120,"onExit":"none","logWorkspacePath":null,"runDirectory":"unused"},{"id":"future","sessionId":"s","workspaceRootUuid":"root","runner":"FUTURE_RUNNER","summary":"echo","startedAtMillis":1,"maxRuntimeMinutes":120,"onExit":"none","logWorkspacePath":null,"runDirectory":"unused"}]""")
            val store=BackgroundJobStore(root)
            val records=store.all()
            assertThat(records.map{it.job().runner}).containsExactly(BackgroundJobRunner.SHELL,BackgroundJobRunner.SHELL)
            val future=records.single{it.id=="future"}
            store.put(future)
            assertThat(BackgroundJobStore(root).all().single{it.id=="future"}.runner).isEqualTo("FUTURE_RUNNER")
            store.put(record("linux").copy(runner="LINUX"))
            assertThat(BackgroundJobStore(root).all().single{it.id=="linux"}.job().runner).isEqualTo(BackgroundJobRunner.LINUX)
        } finally { root.deleteRecursively() }
    }
    @Test fun `SSH任务的主机ID随记录持久化，旧记录读作本地任务`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-host").toFile()
        try {
            java.io.File(root,"records.json").writeText("""[{"id":"legacy","sessionId":"s","workspaceRootUuid":"root","runner":"SSH","summary":"uptime","startedAtMillis":1,"maxRuntimeMinutes":120,"onExit":"none","logWorkspacePath":null,"runDirectory":"unused"}]""")
            val store=BackgroundJobStore(root)
            assertThat(store.all().single().job().remoteHost).isNull()
            store.put(record("ssh").copy(runner="SSH",remoteHost="nas"))
            val job=BackgroundJobStore(root).all().single{it.id=="ssh"}.job()
            assertThat(job.runner).isEqualTo(BackgroundJobRunner.SSH)
            assertThat(job.remoteHost).isEqualTo("nas")
        } finally { root.deleteRecursively() }
    }
    @Test fun `后台任务的运行类型：SSH 独立，Termux 与未知执行器按 shell`() {
        assertThat(execRunKind(BackgroundJobRunner.SSH)).isEqualTo(com.promenar.infinity.data.exec.ExecRunKind.SSH)
        assertThat(execRunKind(BackgroundJobRunner.LINUX)).isEqualTo(com.promenar.infinity.data.exec.ExecRunKind.LINUX)
        assertThat(execRunKind(BackgroundJobRunner.PYTHON)).isEqualTo(com.promenar.infinity.data.exec.ExecRunKind.PYTHON)
        assertThat(execRunKind(BackgroundJobRunner.TERMUX)).isEqualTo(com.promenar.infinity.data.exec.ExecRunKind.SHELL)
        assertThat(execRunKind(BackgroundJobRunner.SHELL)).isEqualTo(com.promenar.infinity.data.exec.ExecRunKind.SHELL)
    }
    @Test fun `任务记录原子持久化并按会话删除`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-store").toFile()
        try {
            val store=BackgroundJobStore(root)
            store.put(record("a")); store.put(record("b", "other"))
            assertThat(BackgroundJobStore(root).all().map{it.id}).containsExactly("a","b")
            store.deleteSession("s")
            assertThat(BackgroundJobStore(root).all().map{it.id}).containsExactly("b")
        } finally { root.deleteRecursively() }
    }
    @Test fun `完成记录仅保留七天内最近五十条而运行中不丢失`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-prune").toFile()
        try {
            val store=BackgroundJobStore(root, { 10 * 86400000L })
            store.put(record("running"))
            for(i in 1..60) store.put(record("end-$i").copy(state="EXITED",endedAtMillis=9*86400000L+i))
            store.put(record("old").copy(state="EXITED",endedAtMillis=1))
            assertThat(store.all()).hasSize(51)
            assertThat(store.all().map{it.id}).contains("running")
            assertThat(store.all().map{it.id}).doesNotContain("old")
        } finally { root.deleteRecursively() }
    }
    private fun record(id:String,session:String="s")=BackgroundJobRecord(id,session,"root","SHELL","echo",startedAtMillis=1,maxRuntimeMinutes=120,onExit="none",logWorkspacePath="jobs/$id.log",runDirectory="unused")
}
