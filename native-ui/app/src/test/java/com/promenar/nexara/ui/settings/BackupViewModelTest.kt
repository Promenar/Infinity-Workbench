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
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    fun `failed secret save wipes input and leaves previous config and plaintext untouched`() {
        val settings = FakeSettings(
            webDavUrl = "https://old.invalid/",
            webDavUser = "old-user",
            webDavPasswordPlaintext = "legacy",
        )
        val secrets = FakeSecrets()
        val vm = newViewModel(settings = settings, secrets = secrets)
        settings.webDavPasswordPlaintext = "fallback"
        secrets.throwOnPut = true
        val password = "replacement".toCharArray()

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            vm.saveWebDavConfig("https://new.invalid/", "new-user", password)
        }

        assertThat(password).isEqualTo(CharArray("replacement".length))
        assertThat(settings.webDavUrl).isEqualTo("https://old.invalid/")
        assertThat(settings.webDavUser).isEqualTo("old-user")
        assertThat(settings.webDavPasswordPlaintext).isEqualTo("fallback")
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
    fun `changing WebDAV endpoint or password invalidates listed selection`() = runTest(dispatcher) {
        val remote = RemoteBackup("a.nexara", 42, 1234, "etag-a")
        val operations = FakeOperations(remote = listOf(remote))
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)
        vm.saveWebDavConfig("https://a.invalid/", "a", "a-pass".toCharArray())
        vm.listRemote(); advanceUntilIdle(); assertThat(vm.selectRemote(remote)).isTrue()

        vm.saveWebDavConfig("https://b.invalid/", "b", "b-pass".toCharArray())

        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.selectedRemote).isNull()
        assertThat(vm.restoreSelectedRemote(null)).isFalse()
        assertThat(operations.stageRemoteCalls).isEqualTo(0)
        assertThat(restart.calls).isEqualTo(0)

        operations.remote = listOf(remote)
        vm.listRemote(); advanceUntilIdle(); assertThat(vm.selectRemote(remote)).isTrue()
        vm.deleteWebDavPassword()
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.selectedRemote).isNull()
    }

    @Test
    fun `listing result from old config cannot repopulate state after config change`() = runTest(dispatcher) {
        val stale = RemoteBackup("stale.nexara", 42, 1234, "etag-a")
        val listGate = CompletableDeferred<List<RemoteBackup>>()
        val operations = FakeOperations(listGate = listGate)
        val vm = newViewModel(operations = operations)
        vm.saveWebDavConfig("https://a.invalid/", "a", null)
        vm.listRemote()
        runCurrent()

        vm.saveWebDavConfig("https://b.invalid/", "b", null)
        listGate.complete(listOf(stale))
        advanceUntilIdle()

        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://b.invalid/")
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.selectedRemote).isNull()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
    }

    @Test
    fun `config change at commit hook prevents old list state write`() = runTest(dispatcher) {
        val stale = RemoteBackup("stale.nexara", 42, 1234, "etag-a")
        val hooks = CommitGateHooks(BackupOperation.ListingRemote)
        val vm = newViewModel(operations = FakeOperations(remote = listOf(stale)), hooks = hooks)
        vm.saveWebDavConfig("https://a.invalid/", "a", null)
        vm.listRemote()
        runCurrent()
        hooks.entered.await()

        vm.saveWebDavConfig("https://b.invalid/", "b", null)
        hooks.release.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.webdavUrl).isEqualTo("https://b.invalid/")
        assertThat(vm.uiState.value.remoteBackups).isEmpty()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
    }

    @Test
    fun `operations reject during lock-free config save and never mix old endpoint with new password`() = runTest(dispatcher) {
        val secrets = FakeSecrets()
        val operations = FakeOperations()
        val vm = newViewModel(operations = operations, secrets = secrets)
        vm.saveWebDavConfig("https://a.invalid/", "a", "a-pass".toCharArray())
        secrets.blockPut = true
        val saveResult = AtomicReference<Boolean>()
        val saveThread = Thread {
            saveResult.set(vm.saveWebDavConfig("https://b.invalid/", "b", "b-pass".toCharArray()))
        }.apply { start() }
        assertThat(secrets.putEntered.await(5, TimeUnit.SECONDS)).isTrue()

        val operationResult = AtomicReference<Boolean>()
        val operationThread = Thread { operationResult.set(vm.testConnection()) }.apply { start() }
        operationThread.join(1_000)
        val returnedWithoutBlocking = !operationThread.isAlive
        secrets.putRelease.countDown()
        saveThread.join(5_000)
        operationThread.join(5_000)

        assertThat(returnedWithoutBlocking).isTrue()
        assertThat(operationResult.get()).isFalse()
        assertThat(saveResult.get()).isTrue()
        assertThat(vm.testConnection()).isTrue()
        advanceUntilIdle()
        assertThat(operations.lastConfig?.baseUrl).isEqualTo("https://b.invalid/")
        assertThat(operations.lastConfig?.username).isEqualTo("b")
        assertThat(operations.lastConfig?.password).isEqualTo("b-pass")
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
    fun `busy rejection closes owned local restore stream`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val operations = FakeOperations(testGate = gate)
        val vm = newViewModel(operations = operations)
        vm.testConnection()
        runCurrent()
        val input = CloseTrackingInputStream(byteArrayOf(1))

        assertThat(vm.restoreLocal(input, null)).isFalse()

        assertThat(input.closed).isTrue()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        vm.cancelOperation()
    }

    @Test
    fun `busy remote restore rejection preserves active phase and local close Error propagates`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val vm = newViewModel(operations = FakeOperations(testGate = gate))
        vm.testConnection(); runCurrent()

        assertThat(vm.restoreSelectedRemote(null)).isFalse()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)

        val input = CloseTrackingInputStream(byteArrayOf(1), closeError = AssertionError("input-close"))
        val thrown = org.junit.jupiter.api.assertThrows<AssertionError> {
            vm.restoreLocal(input, null)
        }
        assertThat(thrown).hasMessageThat().isEqualTo("input-close")
        vm.cancelOperation()
    }

    @Test
    fun `export owns and closes output on busy and password validation rejection`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        val vm = newViewModel(operations = FakeOperations(testGate = gate))
        vm.testConnection(); runCurrent()
        val busyOutput = TrackingOutputStream()
        assertThat(vm.export(busyOutput, null, null)).isFalse()
        assertThat(busyOutput.closeCount).isEqualTo(1)
        vm.cancelOperation()

        vm.setIncludeKeys(true)
        val missingOutput = TrackingOutputStream()
        assertThat(vm.export(missingOutput, null, null)).isFalse()
        assertThat(missingOutput.closeCount).isEqualTo(1)

        val mismatchOutput = TrackingOutputStream()
        assertThat(vm.export(mismatchOutput, "one".toCharArray(), "two".toCharArray())).isFalse()
        assertThat(mismatchOutput.closeCount).isEqualTo(1)
    }

    @Test
    fun `export closes output exactly once on success failure and prelaunch cancellation`() = runTest(dispatcher) {
        val successOutput = TrackingOutputStream()
        val successVm = newViewModel()
        successVm.export(successOutput, null, null); advanceUntilIdle()
        assertThat(successOutput.closeCount).isEqualTo(1)

        val failedOutput = TrackingOutputStream()
        val failedVm = newViewModel(operations = FakeOperations(exportFailure = true))
        failedVm.export(failedOutput, null, null); advanceUntilIdle()
        assertThat(failedOutput.closeCount).isEqualTo(1)
        assertThat(failedVm.uiState.value.operation).isInstanceOf(BackupOperation.Error::class.java)

        val exportGate = CompletableDeferred<Unit>()
        val cancelledOutput = TrackingOutputStream()
        val cancelledVm = newViewModel(operations = FakeOperations(exportGate = exportGate))
        cancelledVm.export(cancelledOutput, null, null)
        cancelledVm.cancelOperation()
        advanceUntilIdle()
        assertThat(cancelledOutput.closeCount).isEqualTo(1)

        val clearGate = CompletableDeferred<Unit>()
        val clearedOutput = TrackingOutputStream()
        val clearedVm = newViewModel(operations = FakeOperations(exportGate = clearGate))
        val store = ViewModelStore().apply { put("export", clearedVm) }
        clearedVm.export(clearedOutput, null, null)
        store.clear()
        advanceUntilIdle()
        assertThat(clearedOutput.closeCount).isEqualTo(1)
    }

    @Test
    fun `export close Error is not swallowed on synchronous rejection`() {
        val vm = newViewModel()
        vm.setIncludeKeys(true)
        val output = TrackingOutputStream(closeError = AssertionError("close-error"))

        val thrown = org.junit.jupiter.api.assertThrows<AssertionError> {
            vm.export(output, null, null)
        }

        assertThat(thrown).hasMessageThat().isEqualTo("close-error")
        assertThat(output.closeCount).isEqualTo(1)
    }

    @Test
    fun `LAZY registration placeholder rejects a concurrent second start`() {
        val hooks = BlockingRegistrationHooks()
        val vm = newViewModel(hooks = hooks)
        val firstResult = AtomicReference<Boolean>()
        val first = Thread { firstResult.set(vm.testConnection()) }.apply { start() }
        assertThat(hooks.entered.await(5, TimeUnit.SECONDS)).isTrue()

        val secondResult = vm.listRemote()

        assertThat(secondResult).isFalse()
        hooks.release.countDown()
        first.join(5_000)
        assertThat(firstResult.get()).isTrue()
        vm.cancelOperation()
    }

    @Test
    fun `cancel cleanup cannot overwrite a newly registered phase`() = runTest(dispatcher) {
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        val output = TrackingOutputStream(closeEntered = closeEntered, closeRelease = closeRelease)
        val exportGate = CompletableDeferred<Unit>()
        val vm = newViewModel(operations = FakeOperations(exportGate = exportGate))
        vm.export(output, null, null)
        val cancelThread = Thread { vm.cancelOperation() }.apply { start() }
        assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(vm.testConnection()).isTrue()
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        closeRelease.countDown()
        cancelThread.join(5_000)

        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Testing)
        vm.cancelOperation()
    }

    @Test
    fun `cancel after remote stage starts can never request restart`() = runTest(dispatcher) {
        val remote = RemoteBackup("one.nexara", 42, 1234, "etag")
        val stageStarted = CompletableDeferred<Unit>()
        val stageRelease = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            remote = listOf(remote),
            remoteStageStarted = stageStarted,
            remoteStageGate = stageRelease,
        )
        val restart = FakeRestart()
        val vm = newViewModel(operations = operations, restart = restart)
        vm.listRemote(); advanceUntilIdle(); vm.selectRemote(remote)
        vm.restoreSelectedRemote(null)
        runCurrent(); stageStarted.await()

        vm.cancelOperation()
        stageRelease.complete(Unit)
        advanceUntilIdle()

        assertThat(restart.calls).isEqualTo(0)
        assertThat(vm.uiState.value.operation).isEqualTo(BackupOperation.Idle)
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
        hooks: BackupViewModelHooks = BackupViewModelHooks.None,
    ) = BackupViewModel(operations, settings, secrets, restart, clock = { 999L }, hooks = hooks)

    private class BlockingRegistrationHooks : BackupViewModelHooks {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun afterOperationRegistered(phase: BackupOperation) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        override suspend fun beforeStateCommit(phase: BackupOperation) = Unit
    }

    private class CommitGateHooks(private val target: BackupOperation) : BackupViewModelHooks {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override fun afterOperationRegistered(phase: BackupOperation) = Unit
        override suspend fun beforeStateCommit(phase: BackupOperation) {
            if (phase::class == target::class) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
    }

    private class FakeSettings(
        override var webDavEnabled: Boolean = false,
        override var autoBackup: Boolean = false,
        override var webDavUrl: String = "",
        override var webDavUser: String = "",
        override var lastBackupTime: Long = 0,
        override var webDavPasswordPlaintext: String? = null,
    ) : BackupSettingsStore

    private class FakeSecrets : SecretStore {
        private val values = ConcurrentHashMap<SecretId, ByteArray>()
        var throwOnPut = false
        var blockPut = false
        val putEntered = CountDownLatch(1)
        val putRelease = CountDownLatch(1)
        override fun put(id: SecretId, value: ByteArray) {
            if (throwOnPut) error("secret write failed")
            if (blockPut) {
                putEntered.countDown()
                putRelease.await(5, TimeUnit.SECONDS)
            }
            values[id] = value.copyOf()
        }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId) = values.containsKey(id)
        override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
        fun text(id: SecretId) = values[id]?.toString(Charsets.UTF_8)
    }

    private class CloseTrackingInputStream(
        bytes: ByteArray,
        private val closeError: Error? = null,
    ) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            closeError?.let { throw it }
            super.close()
        }
    }

    private class TrackingOutputStream(
        private val closeError: Error? = null,
        private val closeEntered: CountDownLatch? = null,
        private val closeRelease: CountDownLatch? = null,
    ) : OutputStream() {
        var closeCount = 0
        override fun write(value: Int) = Unit
        override fun close() {
            closeCount++
            closeEntered?.countDown()
            closeRelease?.await(5, TimeUnit.SECONDS)
            closeError?.let { throw it }
        }
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
        private val listGate: CompletableDeferred<List<RemoteBackup>>? = null,
        private val exportGate: CompletableDeferred<Unit>? = null,
        private val exportFailure: Boolean = false,
        private val remoteStageStarted: CompletableDeferred<Unit>? = null,
        private val remoteStageGate: CompletableDeferred<Unit>? = null,
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
            exportGate?.await()
            if (exportFailure) error("export failed")
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
        override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> {
            lastConfig = config
            return listGate?.let { withContext(NonCancellable) { it.await() } } ?: remote
        }
        override suspend fun stageLocalRestore(input: java.io.InputStream, password: CharArray?): PendingRestoreMetadata {
            if (stageFailure) error("failure")
            return metadata()
        }
        override suspend fun stageRemoteRestore(config: WebDavConfig, selected: RemoteBackup, password: CharArray?): PendingRestoreMetadata {
            stageRemoteCalls++
            if (selected !in remote) error("stale")
            if (remoteStageGate != null) withContext(NonCancellable) {
                remoteStageStarted?.complete(Unit)
                remoteStageGate.await()
            }
            return metadata()
        }
        private fun metadata() = PendingRestoreMetadata(
            "123e4567-e89b-12d3-a456-426614174000",
            MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)),
            PendingRestorePhase.STAGED,
        )
    }
}
