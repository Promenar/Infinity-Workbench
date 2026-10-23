package com.promenar.nexara.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.jobs.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BackgroundJobStoreTest {
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
