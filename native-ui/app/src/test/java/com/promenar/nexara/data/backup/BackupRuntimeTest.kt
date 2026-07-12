package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackupRuntimeTest {
    @Test
    fun `recovery success opens writer gate exactly once`() {
        val calls = AtomicInteger()
        val runtime = BackupRuntime(FakeDataSource { calls.incrementAndGet() })

        runtime.recoverBeforeWriters()
        runtime.recoverBeforeWriters()

        assertThat(runtime.isWriterGateOpen).isTrue()
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `recovery failure keeps gate closed and exposes only sanitized error`() {
        val runtime = BackupRuntime(FakeDataSource { error("secret-path=/data/user/0/private") })

        val thrown = runCatching { runtime.recoverBeforeWriters() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(BackupStartupException::class.java)
        assertThat(thrown!!.message).isEqualTo("安全恢复未完成，应用写入已禁用")
        assertThat(thrown.toString()).doesNotContain("secret-path")
        assertThat(runtime.isWriterGateOpen).isFalse()
    }

    @Test
    fun `recovery waits for non cancellable side effects and leaves no worker thread`() {
        var sideEffectCompleted = false
        val callerThread = Thread.currentThread()
        var recoveryThread: Thread? = null
        val runtime = BackupRuntime(FakeDataSource {
            withContext(NonCancellable) {
                delay(100)
                recoveryThread = Thread.currentThread()
                sideEffectCompleted = true
            }
        })

        runtime.recoverBeforeWriters()

        assertThat(sideEffectCompleted).isTrue()
        assertThat(recoveryThread).isSameInstanceAs(callerThread)
        assertThat(runtime.isWriterGateOpen).isTrue()
        assertThat(Thread.getAllStackTraces().keys.map(Thread::getName))
            .doesNotContain("nexara-backup-startup-recovery")
    }

    @Test
    fun `requireWriterGate rejects access before recovery`() {
        val runtime = BackupRuntime(FakeDataSource { })

        val thrown = runCatching(runtime::requireWriterGate).exceptionOrNull()

        assertThat(thrown).isInstanceOf(BackupStartupException::class.java)
    }

    private class FakeDataSource(
        private val recover: suspend () -> Unit,
    ) : BackupDataSource {
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = error("unused")
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = recover()
    }
}
