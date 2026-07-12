package com.promenar.nexara.ui.settings

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.BackupExportOptions
import com.promenar.nexara.data.backup.PendingRestoreMetadata
import com.promenar.nexara.data.backup.PendingRestorePhase
import com.promenar.nexara.data.remote.webdav.RemoteBackup
import com.promenar.nexara.data.remote.webdav.WebDavConfig
import com.promenar.nexara.data.remote.webdav.WebDavPruneWarning
import com.promenar.nexara.data.repository.BackupUploadReceipt
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state never contains secrets and include keys defaults off`() {
        val state = BackupUiState()
        val names = BackupUiState::class.java.declaredFields.map { it.name }

        assertThat(state.includeKeys).isFalse()
        assertThat(state.hasWebDavPassword).isFalse()
        assertThat(names).containsNoneOf("webdavPass", "password", "passwordConfirmation", "apiKey", "progress")
    }

    @Test
    fun `startup migrates nonempty plaintext once without overwriting an existing secret`() {
        val settings = FakeSettings(webDavPasswordPlaintext = "legacy")
        val secrets = FakeSecrets()
        newViewModel(settings = settings, secrets = secrets)

        assertThat(secrets.text(SecretCatalog.webDavPassword)).isEqualTo("legacy")
        assertThat(settings.webDavPasswordPlaintext).isNull()

        settings.webDavPasswordPlaintext = "stale"
        secrets.put(SecretCatalog.webDavPassword, "current".encodeToByteArray())
        val vm = newViewModel(settings = settings, secrets = secrets)
        assertThat(secrets.text(SecretCatalog.webDavPassword)).isEqualTo("current")
        assertThat(settings.webDavPasswordPlaintext).isNull()
        assertThat(vm.uiState.value.hasWebDavPassword).isTrue()
    }

    @Test
    fun `saving replacing and deleting WebDAV password uses SecretStore and clears plaintext`() {
        val settings = FakeSettings(webDavPasswordPlaintext = "legacy")
        val secrets = FakeSecrets()
        val vm = newViewModel(settings = settings, secrets = secrets)

        val first = "first".toCharArray()
        vm.saveWebDavConfig("https://dav.invalid/", "user", first)
        assertThat(first).isEqualTo(CharArray(5))
        assertThat(secrets.text(SecretCatalog.webDavPassword)).isEqualTo("first")
        assertThat(settings.webDavPasswordPlaintext).isNull()

        val second = "second".toCharArray()
        vm.saveWebDavConfig("https://dav.invalid/", "user", second)
        assertThat(second).isEqualTo(CharArray(6))
        assertThat(secrets.text(SecretCatalog.webDavPassword)).isEqualTo("second")

        vm.deleteWebDavPassword()
        assertThat(secrets.contains(SecretCatalog.webDavPassword)).isFalse()
        assertThat(vm.uiState.value.hasWebDavPassword).isFalse()
    }

    @Test
    fun `mismatched key password never calls repository and consumes both inputs`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations)
        vm.setIncludeKeys(true)
        val password = "one".toCharArray()
        val confirmation = "two".toCharArray()

        vm.export(ByteArrayOutputStream(), password, confirmation)
        advanceUntilIdle()

        assertThat(operations.exportCalls).isEqualTo(0)
        assertThat(password).isEqualTo(CharArray(3))
        assertThat(confirmation).isEqualTo(CharArray(3))
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)
    }

    @Test
    fun `matching key password reaches repository then all owned arrays are cleared`() = runTest(dispatcher) {
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations)
        vm.setIncludeKeys(true)
        val password = "same".toCharArray()
        val confirmation = "same".toCharArray()

        vm.export(ByteArrayOutputStream(), password, confirmation)
        advanceUntilIdle()

        assertThat(operations.exportCalls).isEqualTo(1)
        assertThat(operations.observedPassword).isEqualTo(CharArray(4))
        assertThat(operations.observedConfirmation).isEqualTo(CharArray(4))
        assertThat(password).isEqualTo(CharArray(4))
        assertThat(confirmation).isEqualTo(CharArray(4))
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)
    }

    @Test
    fun `test and list use stored password while state exposes only real remote metadata`() = runTest(dispatcher) {
        val remote = RemoteBackup("one.nexara", 42, 1234, "etag")
        val operations = FakeOperations(remote = listOf(remote))
        val secrets = FakeSecrets().apply { put(SecretCatalog.webDavPassword, "secret".encodeToByteArray()) }
        val vm = newViewModel(operations = operations, secrets = secrets)
        vm.saveWebDavConfig("https://dav.invalid/", "u", null)

        vm.testConnection()
        advanceUntilIdle()
        assertThat(operations.lastConfig?.password).isEqualTo("secret")
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Success::class.java)

        vm.listRemote()
        advanceUntilIdle()
        assertThat(vm.uiState.value.remoteBackups).containsExactly(remote)
        assertThat(vm.selectRemote(remote)).isTrue()
        assertThat(vm.selectRemote(remote.copy(strongEtag = "changed"))).isFalse()
    }

    @Test
    fun `remote restore stages exact selected object and stale selection cannot restart`() = runTest(dispatcher) {
        val remote = RemoteBackup("one.nexara", 42, 1234, "etag")
        val operations = FakeOperations(remote = listOf(remote))
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)
        vm.listRemote(); advanceUntilIdle(); assertThat(vm.selectRemote(remote)).isTrue()

        operations.remote = listOf(remote.copy(strongEtag = "new"))
        val password = "optional".toCharArray()
        vm.restoreSelectedRemote(password)
        advanceUntilIdle()

        assertThat(password).isEqualTo(CharArray(8))
        assertThat(operations.stageRemoteCalls).isEqualTo(1)
        assertThat(restart.calls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)
    }

    @Test
    fun `local stage failure does not restart while success transitions to Restarting`() = runTest(dispatcher) {
        val operations = FakeOperations(stageFailure = true)
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)

        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()
        assertThat(restart.calls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)

        operations.stageFailure = false
        vm.restoreLocal(ByteArrayInputStream(byteArrayOf(1)), null)
        advanceUntilIdle()
        assertThat(restart.calls).isEqualTo(1)
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Restarting)
    }

    @Test
    fun `upload preserves committed cleanup warning`() = runTest(dispatcher) {
        val operations = FakeOperations(warning = WebDavPruneWarning("cleanup", listOf("old")))
        val vm = newViewModel(operations = operations)

        vm.upload(null, null)
        advanceUntilIdle()

        val success = vm.uiState.value.operation as BackupOperation.Success
        assertThat(success.cleanupWarning).isTrue()
    }

    @Test
    fun `only one operation runs and cancellation prevents stale result overwrite`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)

        assertThat(vm.testConnection()).isTrue()
        runCurrent()
        assertThat(vm.listRemote()).isFalse()
        vm.cancelOperation()
        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
    }

    @Test
    fun `rejected password operation cannot overwrite the active phase`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)
        vm.setIncludeKeys(true)
        vm.testConnection()
        runCurrent()

        val password = "one".toCharArray()
        val confirmation = "two".toCharArray()
        assertThat(vm.export(ByteArrayOutputStream(), password, confirmation)).isFalse()

        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        assertThat(password).isEqualTo(CharArray(3))
        assertThat(confirmation).isEqualTo(CharArray(3))
        vm.cancelOperation()
    }

    @Test
    fun `clearing ViewModel cancels in flight work`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)
        val store = ViewModelStore().apply { put("backup", vm) }

        vm.testConnection(); runCurrent(); store.clear(); advanceUntilIdle()

        assertThat(operations.testCancelled).isTrue()
    }

    private fun newViewModel(
        operations: FakeOperations = FakeOperations(),
        settings: FakeSettings = FakeSettings(),
        secrets: FakeSecrets = FakeSecrets(),
        restart: FakeRestart = FakeRestart(),
    ) = BackupViewModel(operations, settings, secrets, restart, clock = { 999L })

    private class FakeSettings(
        override var webDavEnabled: Boolean = false,
        override var autoBackup: Boolean = false,
        override var webDavUrl: String = "",
        override var webDavUser: String = "",
        override var lastBackupTime: Long = 0,
        override var webDavPasswordPlaintext: String? = null,
    ) : BackupSettingsStore

    private class FakeSecrets : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId) = id in values
        override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
        fun text(id: SecretId) = values[id]?.toString(Charsets.UTF_8)
    }

    private class FakeRestart : BackupRestartRequester {
        var calls = 0
        override fun requestRestart() { calls++ }
    }

    private class FakeOperations(
        var remote: List<RemoteBackup> = emptyList(),
        var stageFailure: Boolean = false,
        private val warning: WebDavPruneWarning? = null,
        private val testGate: CompletableDeferred<Result<Unit>>? = null,
    ) : BackupOperations {
        var exportCalls = 0
        var stageRemoteCalls = 0
        var lastConfig: WebDavConfig? = null
        var observedPassword: CharArray? = null
        var observedConfirmation: CharArray? = null
        var testCancelled = false

        override suspend fun export(options: BackupExportOptions): ByteArray {
            exportCalls++
            observedPassword = options.password
            observedConfirmation = options.passwordConfirmation
            return byteArrayOf(1, 2, 3)
        }
        override suspend fun upload(config: WebDavConfig, options: BackupExportOptions): BackupUploadReceipt {
            lastConfig = config
            observedPassword = options.password
            observedConfirmation = options.passwordConfirmation
            return BackupUploadReceipt("new.nexara", warning)
        }
        override suspend fun testRemote(config: WebDavConfig): Result<Unit> {
            lastConfig = config
            return try { testGate?.await() ?: Result.success(Unit) } catch (error: kotlinx.coroutines.CancellationException) {
                testCancelled = true
                throw error
            }
        }
        override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> { lastConfig = config; return remote }
        override suspend fun stageLocalRestore(input: java.io.InputStream, password: CharArray?): PendingRestoreMetadata {
            if (stageFailure) error("failure")
            return metadata()
        }
        override suspend fun stageRemoteRestore(config: WebDavConfig, selected: RemoteBackup, password: CharArray?): PendingRestoreMetadata {
            stageRemoteCalls++
            if (selected !in remote) error("stale")
            return metadata()
        }
        private fun metadata() = PendingRestoreMetadata(
            "123e4567-e89b-12d3-a456-426614174000",
            MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)),
            PendingRestorePhase.STAGED,
        )
    }
}
