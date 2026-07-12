package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.BackupContent
import com.promenar.nexara.data.backup.BackupDataSource
import com.promenar.nexara.data.backup.BackupExportOptions
import com.promenar.nexara.data.backup.BackupManifest
import com.promenar.nexara.data.backup.BackupPackageCodec
import com.promenar.nexara.data.backup.BackupSnapshot
import com.promenar.nexara.data.backup.BackupValidationException
import com.promenar.nexara.data.backup.PendingRestoreMetadata
import com.promenar.nexara.data.backup.PendingRestoreStore
import com.promenar.nexara.data.backup.ValidatedBackup
import com.promenar.nexara.data.backup.DefaultBackupPackageCodec
import com.promenar.nexara.data.backup.PendingRestorePayload
import com.promenar.nexara.data.backup.PendingRestorePhase
import com.promenar.nexara.data.remote.webdav.RemoteBackup
import com.promenar.nexara.data.remote.webdav.UploadAndPruneResult
import com.promenar.nexara.data.remote.webdav.WebDavBackupClient
import com.promenar.nexara.data.remote.webdav.WebDavConfig
import com.promenar.nexara.data.remote.webdav.WebDavPruneWarning
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

class BackupRepositoryTypedTest {
    @Test
    fun `export is canonical and secrets are disabled by default`() = runBlocking {
        val source = RecordingSource()
        val codec = RecordingCodec()
        val repository = BackupRepository(source, codec, FakeWebDav(), FakePendingStore())

        val bytes = repository.export(BackupExportOptions())

        assertThat(source.lastContent).containsExactly(
            BackupContent.DATABASE,
            BackupContent.PREFERENCES,
            BackupContent.FILES,
        )
        assertThat(codec.includeSecrets).isFalse()
        assertThat(bytes).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(source.snapshot.database).isEqualTo(byteArrayOf(0, 0, 0))
    }

    @Test
    fun `secret export requires matching non-empty password and wipes owned copies`() = runBlocking {
        val source = RecordingSource()
        val codec = RecordingCodec()
        val repository = BackupRepository(source, codec, FakeWebDav(), FakePendingStore())

        assertThrows<BackupValidationException> {
            runBlocking {
                repository.export(
                    BackupExportOptions(
                        includeSecrets = true,
                        password = "one".toCharArray(),
                        passwordConfirmation = "two".toCharArray(),
                    )
                )
            }
        }

        val password = "same".toCharArray()
        val confirmation = "same".toCharArray()
        repository.export(BackupExportOptions(true, password, confirmation))
        assertThat(codec.passwordObservedAfterReturn).isEqualTo(CharArray(4))
        assertThat(password.concatToString()).isEqualTo("same")
        assertThat(confirmation.concatToString()).isEqualTo("same")
    }

    @Test
    fun `upload preserves committed warning and only uses uploadAndPrune`() = runBlocking {
        val warning = WebDavPruneWarning("warning", listOf("old.nexara"))
        val webDav = FakeWebDav(warning)
        val repository = BackupRepository(RecordingSource(), RecordingCodec(), webDav, FakePendingStore())

        val result = repository.upload(WebDavConfig("https://dav.invalid/backups/", "u", "p"), BackupExportOptions())

        assertThat(result.pruneWarning).isEqualTo(warning)
        assertThat(webDav.keep).isEqualTo(5)
        assertThat(webDav.uploadedBytes).isEqualTo(byteArrayOf(9, 8, 7))
    }

    @Test
    fun `real codec export stages only authenticated package and local owned bytes are wiped`() = runBlocking {
        val pending = RecordingPendingStore()
        val repository = BackupRepository(RecordingSource(), DefaultBackupPackageCodec(), FakeWebDav(), pending)
        val encoded = repository.export()

        repository.stageLocalRestore(ByteArrayInputStream(encoded), "borrowed".toCharArray())

        assertThat(pending.stageCalls).isEqualTo(1)
        assertThat(pending.packageReference).isEqualTo(ByteArray(encoded.size))
        assertThat(pending.passwordReference).isEqualTo(CharArray("borrowed".length))
        encoded.fill(0)
    }

    @Test
    fun `wrong password and corrupt package never create pending restore`() = runBlocking {
        val pending = RecordingPendingStore()
        val repository = BackupRepository(RecordingSource(), DefaultBackupPackageCodec(), FakeWebDav(), pending)
        val password = "correct".toCharArray()
        val encoded = repository.export(BackupExportOptions(true, password, password.copyOf()))
        try {
            assertThrows<BackupValidationException> {
                repository.stageValidated(encoded, "wrong".toCharArray())
            }
            assertThrows<BackupValidationException> {
                repository.stageValidated(encoded.copyOf().also { it[it.lastIndex]++ }, password)
            }
            assertThat(pending.stageCalls).isEqualTo(0)
        } finally {
            encoded.fill(0)
            password.fill('\u0000')
        }
    }

    @Test
    fun `local input above sixteen MiB is rejected before pending write`() = runBlocking {
        val pending = RecordingPendingStore()
        val repository = BackupRepository(RecordingSource(), RecordingCodec(), FakeWebDav(), pending)
        val oversized = ByteArray(com.promenar.nexara.data.backup.BackupPackageLimits.MAX_IN_MEMORY_BYTES.toInt() + 1)

        assertThrows<BackupValidationException> {
            repository.stageLocalRestore(ByteArrayInputStream(oversized))
        }
        assertThat(pending.stageCalls).isEqualTo(0)
        oversized.fill(0)
    }

    @Test
    fun `remote restore download validation and pending stage all stay off caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val observedThreads = ConcurrentLinkedQueue<Thread>()
        val remote = RemoteBackup("remote.nexara", 3, 1, null)
        val config = WebDavConfig("https://dav.invalid/", "u", "p")
        val codec = object : BackupPackageCodec {
            override fun encode(snapshot: BackupSnapshot, options: com.promenar.nexara.data.backup.BackupOptions) = error("unused")
            override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup {
                observedThreads += Thread.currentThread()
                return ValidatedBackup(BackupManifest(1, 1, "test", 1, emptyList(), false, false))
            }
        }
        val webDav = object : WebDavBackupClient {
            override suspend fun test(config: WebDavConfig) = Result.success(Unit)
            override suspend fun uploadAtomic(config: WebDavConfig, fileName: String, bytes: ByteArray) = error("unused")
            override suspend fun uploadAndPrune(config: WebDavConfig, fileName: String, bytes: ByteArray, keep: Int) = error("unused")
            override suspend fun list(config: WebDavConfig): List<RemoteBackup> {
                observedThreads += Thread.currentThread()
                return listOf(remote)
            }
            override suspend fun download(config: WebDavConfig, backup: RemoteBackup): ByteArray {
                observedThreads += Thread.currentThread()
                return byteArrayOf(1, 2, 3)
            }
            override suspend fun prune(config: WebDavConfig, keep: Int) = error("unused")
        }
        val pending = object : PendingRestoreStore {
            override fun begin(expectedTxId: String) { observedThreads += Thread.currentThread() }
            override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?): PendingRestoreMetadata {
                observedThreads += Thread.currentThread()
                return PendingRestoreMetadata(
                    expectedTxId,
                    MessageDigest.getInstance("SHA-256").digest(packageBytes),
                    PendingRestorePhase.STAGING,
                )
            }
            override fun authorize(expectedTxId: String) = error("unused")
            override fun read(): PendingRestorePayload? = null
            override fun clear(expectedTxId: String) = Unit
            override fun cancel(expectedTxId: String) = Unit
        }

        BackupRepository(RecordingSource(), codec, webDav, pending)
            .stageRemoteRestore("remote-op", config, remote, null)

        assertThat(observedThreads).isNotEmpty()
        assertThat(observedThreads.none { it === caller }).isTrue()
    }

    private class RecordingSource : BackupDataSource {
        val snapshot = BackupSnapshot(byteArrayOf(1, 2, 3), byteArrayOf(4), databaseSchemaVersion = 1, appVersion = "test")
        var lastContent: Set<BackupContent>? = null
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = snapshot.also { lastContent = content }
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = Unit
    }

    private class RecordingCodec : BackupPackageCodec {
        var includeSecrets = false
        var passwordObservedAfterReturn: CharArray? = null
        override fun encode(snapshot: BackupSnapshot, options: com.promenar.nexara.data.backup.BackupOptions): ByteArray {
            includeSecrets = options.includeSecrets
            passwordObservedAfterReturn = options.password
            return byteArrayOf(9, 8, 7)
        }
        override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup = error("unused")
    }

    private class FakeWebDav(private val warning: WebDavPruneWarning? = null) : WebDavBackupClient {
        var keep = 0
        var uploadedBytes = ByteArray(0)
        override suspend fun test(config: WebDavConfig) = Result.success(Unit)
        override suspend fun uploadAtomic(config: WebDavConfig, fileName: String, bytes: ByteArray) = error("must not be called")
        override suspend fun uploadAndPrune(config: WebDavConfig, fileName: String, bytes: ByteArray, keep: Int): UploadAndPruneResult {
            this.keep = keep
            uploadedBytes = bytes.copyOf()
            return UploadAndPruneResult.Committed(fileName, warning)
        }
        override suspend fun list(config: WebDavConfig): List<RemoteBackup> = emptyList()
        override suspend fun download(config: WebDavConfig, backup: RemoteBackup): ByteArray = error("unused")
        override suspend fun prune(config: WebDavConfig, keep: Int) = error("must not be called")
    }

    private class FakePendingStore : PendingRestoreStore {
        override fun begin(expectedTxId: String) = Unit
        override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?) = error("unused")
        override fun authorize(expectedTxId: String) = error("unused")
        override fun read() = null
        override fun clear(expectedTxId: String) = Unit
        override fun cancel(expectedTxId: String) = Unit
    }

    private class RecordingPendingStore : PendingRestoreStore {
        var stageCalls = 0
        var packageReference: ByteArray? = null
        var passwordReference: CharArray? = null
        override fun begin(expectedTxId: String) = Unit
        override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?): PendingRestoreMetadata {
            stageCalls++
            packageReference = packageBytes
            passwordReference = password
            return PendingRestoreMetadata(
                expectedTxId,
                MessageDigest.getInstance("SHA-256").digest(packageBytes),
                PendingRestorePhase.STAGING,
            )
        }
        override fun authorize(expectedTxId: String) = PendingRestoreMetadata(
            expectedTxId,
            MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)),
            PendingRestorePhase.STAGED,
        )
        override fun read(): PendingRestorePayload? = null
        override fun clear(expectedTxId: String) = Unit
        override fun cancel(expectedTxId: String) = Unit
    }
}
