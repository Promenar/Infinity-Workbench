package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackupRuntimeTest {
    @Test
    fun `concurrent recovery shares one attempt and becomes ready only after side effects finish`() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        var sideEffectCompleted = false
        val callerThread = Thread.currentThread()
        var recoveryThread: Thread? = null
        val runtime = BackupRuntime(FakeDataSource {
            calls.incrementAndGet()
            recoveryThread = Thread.currentThread()
            started.complete(Unit)
            withContext(NonCancellable) {
                allowCompletion.await()
                sideEffectCompleted = true
            }
        })

        val first = async { runtime.recoverBeforeWriters() }
        started.await()
        val second = async { runtime.recoverBeforeWriters() }

        assertThat(runtime.state.value).isEqualTo(BackupStartupState.Recovering)
        assertThat(runtime.isWriterGateOpen).isFalse()
        allowCompletion.complete(Unit)

        assertThat(first.await()).isEqualTo(BackupStartupState.Ready)
        assertThat(second.await()).isEqualTo(BackupStartupState.Ready)
        assertThat(sideEffectCompleted).isTrue()
        assertThat(recoveryThread).isNotSameInstanceAs(callerThread)
        assertThat(runtime.state.value).isEqualTo(BackupStartupState.Ready)
        assertThat(runtime.isWriterGateOpen).isTrue()
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `blocked recovery exposes sanitized state and explicit retry can open gate`() = runBlocking {
        val calls = AtomicInteger()
        val runtime = BackupRuntime(FakeDataSource {
            if (calls.incrementAndGet() == 1) error("secret-path=/data/user/0/private")
        })

        assertThat(runtime.recoverBeforeWriters()).isEqualTo(BackupStartupState.Blocked)
        assertThat(runtime.state.value).isEqualTo(BackupStartupState.Blocked)
        assertThat(runtime.isWriterGateOpen).isFalse()

        assertThat(runtime.recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
        assertThat(runtime.state.value).isEqualTo(BackupStartupState.Ready)
        assertThat(runtime.isWriterGateOpen).isTrue()
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `requireWriterGate rejects access before successful recovery`() {
        val runtime = BackupRuntime(FakeDataSource { })

        val thrown = runCatching(runtime::requireWriterGate).exceptionOrNull()

        assertThat(thrown).isInstanceOf(BackupStartupException::class.java)
        assertThat(thrown!!.message).isEqualTo("安全恢复未完成，应用写入已禁用")
    }

    private class FakeDataSource(
        private val recover: suspend () -> Unit,
    ) : BackupDataSource {
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = error("unused")
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = recover()
    }
}
