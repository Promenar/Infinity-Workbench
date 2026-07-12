package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest

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

    @Test
    fun `pending restore uses stable operation id and is applied at most once across runtime recreation`() = runBlocking {
        val txId = "123e4567-e89b-12d3-a456-426614174000"
        val store = FakePendingStore(txId, byteArrayOf(7, 8), "pw".toCharArray())
        val source = ReceiptDataSource()
        val codec = object : BackupPackageCodec {
            override fun encode(snapshot: BackupSnapshot, options: BackupOptions) = error("unused")
            override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup {
                assertThat(bytes).isEqualTo(byteArrayOf(7, 8))
                assertThat(password).isEqualTo("pw".toCharArray())
                return ValidatedBackup(
                    BackupManifest(1, 1, "test", 1, emptyList(), false, false),
                )
            }
        }

        assertThat(BackupRuntime(source, store, codec).recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
        assertThat(BackupRuntime(source, store, codec).recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)

        assertThat(source.restoreIds).containsExactly(txId)
        assertThat(store.clearCount).isEqualTo(1)
        assertThat(store.isPresent).isFalse()
    }

    @Test
    fun `receipt prevents reapply when pending cleanup crashes and retry only clears pending`() = runBlocking {
        val txId = "123e4567-e89b-12d3-a456-426614174001"
        val store = FakePendingStore(txId, byteArrayOf(7), null).apply { failNextClear = true }
        val source = ReceiptDataSource()
        val codec = object : BackupPackageCodec {
            override fun encode(snapshot: BackupSnapshot, options: BackupOptions) = error("unused")
            override fun decode(bytes: ByteArray, password: CharArray?) = ValidatedBackup(
                BackupManifest(1, 1, "test", 1, emptyList(), false, false),
            )
        }

        assertThat(BackupRuntime(source, store, codec).recoverBeforeWriters()).isEqualTo(BackupStartupState.Blocked)
        assertThat(source.restoreIds).containsExactly(txId)
        assertThat(store.isPresent).isTrue()

        assertThat(BackupRuntime(source, store, codec).recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
        assertThat(source.restoreIds).containsExactly(txId)
        assertThat(store.isPresent).isFalse()
    }

    @Test
    fun `startup clears unfinished or cancelled transaction without decoding or restoring`() = runBlocking {
        for (phase in listOf(PendingRestorePhase.STAGING, PendingRestorePhase.CANCELLED)) {
            val store = FakePendingStore(
                "123e4567-e89b-12d3-a456-426614174010",
                ByteArray(0),
                null,
                phase,
            )
            val source = ReceiptDataSource()
            val codec = object : BackupPackageCodec {
                override fun encode(snapshot: BackupSnapshot, options: BackupOptions) = error("unused")
                override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup = error("must not decode")
            }

            assertThat(BackupRuntime(source, store, codec).recoverBeforeWriters()).isEqualTo(BackupStartupState.Ready)
            assertThat(source.restoreIds).isEmpty()
            assertThat(store.isPresent).isFalse()
        }
    }

    private class FakeDataSource(
        private val recover: suspend () -> Unit,
    ) : BackupDataSource {
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = error("unused")
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = recover()
    }

    private class ReceiptDataSource : BackupDataSource {
        val restoreIds = mutableListOf<String>()
        private val receipts = mutableSetOf<String>()
        override suspend fun snapshot(content: Set<BackupContent>) = error("unused")
        override suspend fun restore(validated: ValidatedBackup) = error("stable operation id required")
        override suspend fun restore(validated: ValidatedBackup, operationId: String) {
            validated.close()
            restoreIds += operationId
            receipts += operationId
        }
        override suspend fun hasCompletedRestore(operationId: String) = operationId in receipts
        override suspend fun recoverInterruptedRestore() = Unit
    }

    private class FakePendingStore(
        private val txId: String,
        private val bytes: ByteArray,
        private val chars: CharArray?,
        private val phase: PendingRestorePhase = PendingRestorePhase.STAGED,
    ) : PendingRestoreStore {
        var isPresent = true
        var clearCount = 0
        var failNextClear = false
        override fun begin(expectedTxId: String) = error("unused")
        override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?) = error("unused")
        override fun authorize(expectedTxId: String) = error("unused")
        override fun read(): PendingRestorePayload? = if (!isPresent) null else PendingRestorePayload(
            PendingRestoreMetadata(txId, MessageDigest.getInstance("SHA-256").digest(bytes), phase),
            bytes.copyOf(),
            chars?.copyOf(),
        )
        override fun clear(expectedTxId: String) {
            assertThat(expectedTxId).isEqualTo(txId)
            if (failNextClear) {
                failNextClear = false
                error("simulated pending cleanup crash")
            }
            isPresent = false
            clearCount++
        }
        override fun cancel(expectedTxId: String) = clear(expectedTxId)
    }
}
