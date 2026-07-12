package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.backup.BackupExportOptions
import com.promenar.nexara.data.backup.PendingRestoreMetadata
import com.promenar.nexara.data.backup.RestoreRelayActivity
import com.promenar.nexara.data.remote.webdav.RemoteBackup
import com.promenar.nexara.data.remote.webdav.WebDavConfig
import com.promenar.nexara.data.repository.BackupRepository
import com.promenar.nexara.data.repository.BackupUploadReceipt
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.data.security.WebDavAuthRecord
import com.promenar.nexara.data.security.WebDavAuthRecordCodec
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackupErrorCode {
    PASSWORD_REQUIRED,
    PASSWORD_MISMATCH,
    CONFIGURATION_MISSING,
    CONNECTION_FAILED,
    REMOTE_LIST_FAILED,
    STALE_SELECTION,
    EXPORT_FAILED,
    UPLOAD_FAILED,
    RESTORE_FAILED,
    RESTART_FAILED,
    RESTORE_CLEANUP_FAILED,
}

sealed interface BackupOperation {
    data object Initializing : BackupOperation
    data object SavingConfig : BackupOperation
    data object Idle : BackupOperation
    data object Testing : BackupOperation
    data object ListingRemote : BackupOperation
    data object Exporting : BackupOperation
    data object Uploading : BackupOperation
    data object StagingRestore : BackupOperation
    data object CancellingRestore : BackupOperation
    data object Restarting : BackupOperation
    data class Blocked(val code: BackupErrorCode, val message: String) : BackupOperation
    data class Success(val message: String, val cleanupWarning: Boolean = false) : BackupOperation
    data class Error(val code: BackupErrorCode, val message: String) : BackupOperation
}

data class BackupUiState(
    val includeKeys: Boolean = false,
    val webdavEnabled: Boolean = false,
    val autoBackup: Boolean = false,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val hasWebDavPassword: Boolean = false,
    val lastBackupTime: Long = 0,
    val remoteBackups: List<RemoteBackup> = emptyList(),
    val selectedRemote: RemoteBackup? = null,
    val remoteConfigRevision: Long? = null,
    val operation: BackupOperation = BackupOperation.Idle,
) {
    val keysChecked: Boolean get() = includeKeys
    val isExporting: Boolean get() = operation is BackupOperation.Exporting || operation is BackupOperation.Uploading
    val isImporting: Boolean get() = operation is BackupOperation.StagingRestore || operation is BackupOperation.Restarting
    val statusMessage: String? get() = when (val current = operation) {
        BackupOperation.Idle -> null
        BackupOperation.Initializing -> "正在安全读取备份配置…"
        BackupOperation.SavingConfig -> "正在安全保存 WebDAV 配置…"
        BackupOperation.Testing -> "正在测试连接…"
        BackupOperation.ListingRemote -> "正在读取远程备份…"
        BackupOperation.Exporting -> "正在导出备份…"
        BackupOperation.Uploading -> "正在上传备份…"
        BackupOperation.StagingRestore -> "正在验证并暂存恢复包…"
        BackupOperation.CancellingRestore -> "正在安全清理已取消的恢复事务…"
        BackupOperation.Restarting -> "恢复包已暂存，正在安全重启…"
        is BackupOperation.Blocked -> current.message
        is BackupOperation.Success -> current.message
        is BackupOperation.Error -> current.message
    }
    val error: String? get() = when (val current = operation) {
        is BackupOperation.Error -> current.message
        is BackupOperation.Blocked -> current.message
        else -> null
    }
    val canExecute: Boolean get() = operation !is BackupOperation.Testing &&
        operation !is BackupOperation.Initializing &&
        operation !is BackupOperation.SavingConfig &&
        operation !is BackupOperation.ListingRemote &&
        operation !is BackupOperation.Exporting &&
        operation !is BackupOperation.Uploading &&
        operation !is BackupOperation.StagingRestore &&
        operation !is BackupOperation.CancellingRestore &&
        operation !is BackupOperation.Restarting
}

interface BackupSettingsStore {
    var webDavEnabled: Boolean
    var autoBackup: Boolean
    var webDavUrl: String
    var webDavUser: String
    var lastBackupTime: Long
    var webDavPasswordPlaintext: String?
}

interface BackupOperations {
    suspend fun export(options: BackupExportOptions): ByteArray
    suspend fun upload(config: WebDavConfig, options: BackupExportOptions): BackupUploadReceipt
    suspend fun testRemote(config: WebDavConfig): Result<Unit>
    suspend fun listRemote(config: WebDavConfig): List<RemoteBackup>
    fun newRestoreOperationId(): String = UUID.randomUUID().toString()
    suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?): PendingRestoreMetadata
    suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray?,
    ): PendingRestoreMetadata
    suspend fun discardPendingRestore(operationId: String)
    suspend fun authorizePendingRestore(operationId: String)
}

fun interface BackupRestartRequester {
    fun requestRestart()
}

internal interface BackupViewModelHooks {
    fun afterOperationRegistered(phase: BackupOperation)
    suspend fun beforeStateCommit(phase: BackupOperation)

    data object None : BackupViewModelHooks {
        override fun afterOperationRegistered(phase: BackupOperation) = Unit
        override suspend fun beforeStateCommit(phase: BackupOperation) = Unit
    }
}

private data class BoundWebDavConfig(val revision: Long, val config: WebDavConfig)
private data class ConfigMutationReservation(val revision: Long, val cancellation: OperationCancellation)
private data class RestoreControl(
    val operationId: String,
    var restartAuthorized: Boolean = false,
    var durableAuthorized: Boolean = false,
)

private class OnceCleanup(private val action: () -> Unit) {
    private val completed = AtomicBoolean(false)
    fun run() {
        if (completed.compareAndSet(false, true)) action()
    }
}

private sealed interface OperationCancellation {
    fun run()

    data object None : OperationCancellation {
        override fun run() = Unit
    }

    class Active(
        private val job: Job?,
        private val cleanup: OnceCleanup?,
    ) : OperationCancellation {
        override fun run() {
            job?.cancel()
            cleanup?.run()
        }
    }
}

class BackupViewModel internal constructor(
    private val operations: BackupOperations,
    private val settings: BackupSettingsStore,
    private val secrets: SecretStore,
    private val restartRequester: BackupRestartRequester,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hooks: BackupViewModelHooks = BackupViewModelHooks.None,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val synchronousIoForTests: Boolean = false,
) : ViewModel() {
    private val generation = AtomicLong(0)
    private val configRevision = AtomicLong(0)
    private val operationLock = Any()
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentJob: Job? = null
    private var currentOperationToken: Long? = null
    private var currentCleanup: OnceCleanup? = null
    private var activeWebDavRevision: Long? = null
    private var configMutationRevision: Long? = null
    private var restoreControl: RestoreControl? = null
    private var restoreBlocked = false
    private var webDavBlocked = false
    private var cleared = false
    private var initialized = false

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    constructor(application: Application) : this(
        operations = RepositoryBackupOperations(BackupRepository(application)),
        settings = SharedPreferencesBackupSettingsStore(
            application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        secrets = (application as NexaraApplication).secretStore,
        restartRequester = BackupRestartRequester { RestoreRelayActivity.requestRestart(application) },
    )

    init {
        if (synchronousIoForTests) {
            initializeCanonicalWebDavAuth()
            initialized = true
        } else {
            _uiState.update { it.copy(operation = BackupOperation.Initializing) }
            operationScope.launch {
                try {
                    withContext(ioDispatcher) { initializeCanonicalWebDavAuth() }
                } catch (error: Error) {
                    publishFatalConfigFailure(error)
                    throw error
                } finally {
                    synchronized(operationLock) {
                        initialized = true
                        if (_uiState.value.operation is BackupOperation.Initializing) {
                            _uiState.update { it.copy(operation = BackupOperation.Idle) }
                        }
                    }
                }
            }
        }
    }

    fun setWebdavEnabled(enabled: Boolean) {
        val reservation = synchronized(operationLock) {
            if (_uiState.value.webdavEnabled == enabled) return
            reserveWebDavMutationLocked() ?: return
        }
        try {
            reservation.cancellation.run()
            settings.webDavEnabled = enabled
            finishWebDavMutation(reservation.revision) { it.copy(webdavEnabled = enabled) }
        } catch (error: Throwable) {
            handleWebDavMutationFailure(reservation.revision, error)
            throw error
        }
    }

    fun setAutoBackup(enabled: Boolean) {
        synchronized(operationLock) {
            if (!initialized || restoreControl?.restartAuthorized == true || restoreBlocked || cleared) return
        }
        settings.autoBackup = enabled
        _uiState.update { it.copy(autoBackup = enabled) }
    }

    fun setIncludeKeys(include: Boolean) {
        synchronized(operationLock) {
            if (!initialized || restoreControl?.restartAuthorized == true || restoreBlocked || cleared) return
        }
        _uiState.update { it.copy(includeKeys = include) }
    }

    /** password 非空时由本方法取得所有权，并在返回前清零；null 表示保留既有密码。 */
    fun saveWebDavConfig(url: String, user: String, password: CharArray?): Boolean {
        val ownedPassword = password?.copyOf()
        password?.fill('\u0000')
        val reservation = synchronized(operationLock) {
            val changed = url != _uiState.value.webdavUrl || user != _uiState.value.webdavUser || ownedPassword != null
            if (!changed) return true
            reserveWebDavMutationLocked()
        }
        if (reservation == null) {
            ownedPassword?.fill('\u0000')
            return false
        }
        if (!synchronousIoForTests) {
            _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
            operationScope.launch {
                try {
                    withContext(ioDispatcher) { saveWebDavConfigNow(url, user, ownedPassword, reservation) }
                } catch (error: Exception) {
                    publishConfigFailureIfNeeded("保存 WebDAV 配置失败")
                } catch (error: Error) {
                    publishFatalConfigFailure(error)
                    throw error
                } finally {
                    ownedPassword?.fill('\u0000')
                }
            }
            return true
        }
        return try {
            saveWebDavConfigNow(url, user, ownedPassword, reservation)
        } finally {
            ownedPassword?.fill('\u0000')
        }
    }

    private fun saveWebDavConfigNow(
        url: String,
        user: String,
        password: CharArray?,
        reservation: ConfigMutationReservation,
        releaseReservation: Boolean = true,
        manageFailure: Boolean = true,
    ): Boolean {
        try {
            reservation.cancellation.run()
            val existingPassword = if (password == null) readCanonicalAuth().useAndCopyPassword() else null
            val effectivePassword = password ?: existingPassword
            var encoded = ByteArray(0)
            try {
                encoded = WebDavAuthRecordCodec.encode(url, user, effectivePassword)
                secrets.put(SecretCatalog.webDavAuthRecord, encoded)
            } finally {
                encoded.fill(0)
                existingPassword?.fill('\u0000')
            }
            val hasPassword = effectivePassword?.isNotEmpty() == true
            if (releaseReservation) {
                finishWebDavMutation(reservation.revision) {
                    it.copy(
                        webdavUrl = url,
                        webdavUser = user,
                        hasWebDavPassword = hasPassword,
                        operation = BackupOperation.Success("WebDAV 配置已安全保存"),
                    )
                }
            } else synchronized(operationLock) {
                check(configMutationRevision == reservation.revision)
                _uiState.update { it.copy(
                    webdavUrl = url,
                    webdavUser = user,
                    hasWebDavPassword = hasPassword,
                    operation = BackupOperation.Testing,
                ) }
            }
            cleanupWebDavCachesBestEffort(url, user)
            return true
        } catch (error: Throwable) {
            if (manageFailure) handleWebDavMutationFailure(reservation.revision, error)
            throw error
        }
    }

    /** 保存与连接测试是同一个异步操作，不会用到旧 revision。 */
    fun saveAndTestWebDavConfig(url: String, user: String, password: CharArray?): Boolean {
        val ownedPassword = password?.copyOf()
        password?.fill('\u0000')
        val reservation = synchronized(operationLock) { reserveWebDavMutationLocked() }
        if (reservation == null) {
            ownedPassword?.fill('\u0000')
            return false
        }
        _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
        operationScope.launch {
            try {
                withContext(ioDispatcher) {
                    saveWebDavConfigNow(
                        url,
                        user,
                        ownedPassword,
                        reservation,
                        releaseReservation = false,
                        manageFailure = false,
                    )
                    val config = readCanonicalWebDavConfig()
                    operations.testRemote(config).getOrThrow()
                }
                completeConfigMutation(
                    reservation.revision,
                    BackupOperation.Success("连接测试成功"),
                )
            } catch (error: Exception) {
                withContext(ioDispatcher) { resolveSaveAndTestFailure(reservation.revision, fatal = false) }
            } catch (error: Error) {
                try {
                    withContext(NonCancellable + ioDispatcher) {
                        resolveSaveAndTestFailure(reservation.revision, fatal = true)
                    }
                } catch (resolutionError: Error) {
                    if (resolutionError !== error) error.addSuppressed(resolutionError)
                }
                throw error
            } finally {
                ownedPassword?.fill('\u0000')
            }
        }
        return true
    }

    fun deleteWebDavPassword(): Boolean {
        val reservation = synchronized(operationLock) { reserveWebDavMutationLocked() } ?: return false
        if (!synchronousIoForTests) {
            _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
            operationScope.launch {
                try {
                    withContext(ioDispatcher) { deleteWebDavPasswordNow(reservation) }
                } catch (error: Exception) {
                    publishConfigFailureIfNeeded("删除 WebDAV 密码失败")
                } catch (error: Error) {
                    publishFatalConfigFailure(error)
                    throw error
                }
            }
            return true
        }
        return deleteWebDavPasswordNow(reservation)
    }

    private fun deleteWebDavPasswordNow(reservation: ConfigMutationReservation): Boolean {
        try {
            reservation.cancellation.run()
            val current = readCanonicalAuth()
            current.use {
                val encoded = WebDavAuthRecordCodec.encode(it.endpoint, it.username, null)
                try {
                    secrets.put(SecretCatalog.webDavAuthRecord, encoded)
                } finally {
                    encoded.fill(0)
                }
                // canonical 先发布，prefs 只是可修复的公开缓存。
            }
            finishWebDavMutation(reservation.revision) {
                it.copy(
                    hasWebDavPassword = false,
                    operation = BackupOperation.Success("WebDAV 密码已删除"),
                )
            }
            cleanupWebDavCachesBestEffort(current.endpoint, current.username)
            return true
        } catch (error: Throwable) {
            handleWebDavMutationFailure(reservation.revision, error)
            throw error
        }
    }

    /** 仅用于 canonical 损坏后的显式安全恢复；不会回退读取 legacy 密码。 */
    fun resetWebDavAuth(): Boolean {
        val revision = synchronized(operationLock) {
            if (!initialized || configMutationRevision != null || currentJob != null || restoreControl != null || cleared) return false
            configRevision.incrementAndGet().also { configMutationRevision = it }
        }
        if (!synchronousIoForTests) {
            _uiState.update { it.copy(operation = BackupOperation.SavingConfig) }
            operationScope.launch {
                try {
                    withContext(ioDispatcher) { resetWebDavAuthNow(revision) }
                } catch (error: Error) {
                    publishFatalConfigFailure(error)
                    throw error
                }
            }
            return true
        }
        return resetWebDavAuthNow(revision)
    }

    private fun resetWebDavAuthNow(revision: Long): Boolean {
        return try {
            val encoded = WebDavAuthRecordCodec.encode("", "", null)
            try {
                secrets.put(SecretCatalog.webDavAuthRecord, encoded)
            } finally {
                encoded.fill(0)
            }
            synchronized(operationLock) {
                check(configMutationRevision == revision)
                webDavBlocked = false
                configMutationRevision = null
                _uiState.update { it.copy(
                    webdavUrl = "",
                    webdavUser = "",
                    hasWebDavPassword = false,
                    remoteBackups = emptyList(),
                    selectedRemote = null,
                    remoteConfigRevision = null,
                    operation = BackupOperation.Success("WebDAV 安全配置已重置"),
                ) }
            }
            cleanupWebDavCachesBestEffort("", "")
            true
        } catch (error: Throwable) {
            val recovered = try {
                readCanonicalAuth().use { it.endpoint.isEmpty() && it.username.isEmpty() && it.passwordBytes.isEmpty() }
            } catch (_: Exception) {
                false
            }
            synchronized(operationLock) {
                if (configMutationRevision == revision) configMutationRevision = null
                webDavBlocked = !recovered
                _uiState.update { state -> state.copy(
                    operation = if (recovered) BackupOperation.Success("WebDAV 安全配置已重置")
                    else BackupOperation.Blocked(
                        BackupErrorCode.CONNECTION_FAILED,
                        "WebDAV 安全配置重置失败",
                    ),
                    webdavUrl = if (recovered) "" else state.webdavUrl,
                    webdavUser = if (recovered) "" else state.webdavUser,
                    hasWebDavPassword = if (recovered) false else state.hasWebDavPassword,
                ) }
            }
            if (recovered) cleanupWebDavCachesBestEffort("", "")
            if (error is Error) throw error
            recovered
        }
    }

    fun testConnection(): Boolean {
        val revision = captureWebDavRevision() ?: return false
        return startOperation(BackupOperation.Testing, webDavRevision = revision) { token ->
            val bound = readBoundWebDavConfig(revision)
            operations.testRemote(bound.config).getOrThrow()
            hooks.beforeStateCommit(BackupOperation.Testing)
            complete(token, BackupOperation.Success("连接测试成功"), bound.revision)
        }
    }

    fun listRemote(): Boolean {
        val revision = captureWebDavRevision() ?: return false
        return startOperation(BackupOperation.ListingRemote, webDavRevision = revision) { token ->
            val bound = readBoundWebDavConfig(revision)
            val backups = operations.listRemote(bound.config)
            hooks.beforeStateCommit(BackupOperation.ListingRemote)
            synchronized(operationLock) {
                if (isCurrentLocked(token, bound.revision)) {
                    _uiState.update {
                        it.copy(
                            remoteBackups = backups,
                            selectedRemote = null,
                            remoteConfigRevision = bound.revision,
                            operation = BackupOperation.Success("已读取 ${backups.size} 个远程备份"),
                        )
                    }
                }
            }
        }
    }

    fun selectRemote(remote: RemoteBackup): Boolean {
        synchronized(operationLock) {
            if (currentJob != null || configMutationRevision != null || restoreControl != null) return false
            val state = _uiState.value
            if (state.remoteConfigRevision != configRevision.get()) return false
            val accepted = state.remoteBackups.singleOrNull { it == remote } ?: return false
            _uiState.update { it.copy(selectedRemote = accepted) }
            return true
        }
    }

    /** password 由本方法取得所有权并清零。 */
    fun export(output: OutputStream, password: CharArray?, confirmation: CharArray?): Boolean =
        startPasswordOperation(
            BackupOperation.Exporting,
            password,
            confirmation,
            externalCleanup = { output.close() },
        ) { token, options ->
            val bytes = operations.export(options)
            try {
                output.write(bytes)
            } finally {
                bytes.fill(0)
            }
            hooks.beforeStateCommit(BackupOperation.Exporting)
            recordBackupSuccess(token, "导出成功")
        }

    /** password/confirmation 由本方法取得所有权并清零。 */
    fun upload(password: CharArray?, confirmation: CharArray?): Boolean {
        val revision = captureWebDavRevision()
        if (revision == null) {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
            return false
        }
        return startPasswordOperation(
            BackupOperation.Uploading,
            password,
            confirmation,
            webDavRevision = revision,
        ) { token, options ->
            val bound = readBoundWebDavConfig(revision)
            val receipt = operations.upload(bound.config, options)
            hooks.beforeStateCommit(BackupOperation.Uploading)
            recordBackupSuccess(
                token,
                if (receipt.pruneWarning == null) "云端上传成功" else "云端上传成功，但旧备份清理不完整",
                cleanupWarning = receipt.pruneWarning != null,
                webDavRevision = bound.revision,
            )
        }
    }

    /** password 由本方法取得所有权并清零。 */
    fun restoreLocal(input: InputStream, password: CharArray?): Boolean =
        startRestore(password, completion = { input.close() }) { operationId, owned ->
            operations.stageLocalRestore(operationId, input, owned)
        }

    /** 必须先 list 并按完整 RemoteBackup 对象选中；password 由本方法取得所有权并清零。 */
    fun restoreSelectedRemote(password: CharArray?): Boolean {
        var rejectedAsBusy = false
        val selectedRevision = synchronized(operationLock) {
            if (currentJob != null || configMutationRevision != null || restoreControl != null) {
                rejectedAsBusy = true
                null
            } else {
                val state = _uiState.value
                val revision = state.remoteConfigRevision
                val selected = state.selectedRemote
                if (revision == null || revision != configRevision.get() || selected !in state.remoteBackups) null
                else selected!! to revision
            }
        }
        if (selectedRevision == null) {
            password?.fill('\u0000')
            if (rejectedAsBusy) return false
            _uiState.update {
                it.copy(operation = BackupOperation.Error(BackupErrorCode.STALE_SELECTION, "远程备份选择已失效，请刷新后重试"))
            }
            return false
        }
        val revision = captureWebDavRevision()
        if (revision == null || revision != selectedRevision.second) {
            password?.fill('\u0000')
            return false
        }
        val selected = selectedRevision.first
        return startRestore(password, webDavRevision = revision) { operationId, owned ->
            val bound = readBoundWebDavConfig(revision)
            operations.stageRemoteRestore(operationId, bound.config, selected, owned)
        }
    }

    fun cancelOperation() {
        var restoreOperationId: String? = null
        val cancellation = synchronized(operationLock) {
            val restore = restoreControl
            if (restore != null) {
                if (restore.restartAuthorized) return@synchronized OperationCancellation.None
                restoreOperationId = restore.operationId
                generation.incrementAndGet()
                _uiState.update { state -> state.copy(operation = BackupOperation.CancellingRestore) }
                OperationCancellation.Active(currentJob, currentCleanup)
            } else {
                generation.incrementAndGet()
                takeCurrentOperationLocked().also {
                    _uiState.update { state -> state.copy(operation = BackupOperation.Idle) }
                }
            }
        }
        try {
            cancellation.run()
        } catch (error: Throwable) {
            restoreOperationId?.let(::markRestoreCleanupBlocked)
            if (restoreOperationId == null || error is Error) throw error
        }
    }

    override fun onCleared() {
        synchronized(operationLock) { cleared = true }
        cancelOperation()
        synchronized(operationLock) {
            if (currentJob == null) operationScope.cancel()
        }
        super.onCleared()
    }

    private fun startRestore(
        password: CharArray?,
        completion: () -> Unit = {},
        webDavRevision: Long? = null,
        stage: suspend (String, CharArray?) -> PendingRestoreMetadata,
    ): Boolean {
        val operationId = operations.newRestoreOperationId()
        val owned = password?.copyOf()
        password?.fill('\u0000')
        val started = startOperation(
            phase = BackupOperation.StagingRestore,
            onCompletion = {
                owned?.fill('\u0000')
                completion()
            },
            webDavRevision = webDavRevision,
            restoreOperationId = operationId,
        ) { token ->
            try {
                stage(operationId, owned)
                hooks.beforeStateCommit(BackupOperation.StagingRestore)
                if (authorizeRestart(token, webDavRevision, operationId)) {
                    try {
                        operations.authorizePendingRestore(operationId)
                        synchronized(operationLock) {
                            if (restoreControl?.operationId == operationId) restoreControl?.durableAuthorized = true
                        }
                        requestAuthorizedRestart(operationId)
                    } catch (error: Exception) {
                        markRestartAuthorizationBlocked(operationId)
                    }
                }
            } finally {
                if (!isRestartAuthorized(operationId)) {
                    withContext(NonCancellable) {
                        try {
                            operations.discardPendingRestore(operationId)
                        } catch (error: Throwable) {
                            markRestoreCleanupBlocked(operationId)
                            throw error
                        }
                    }
                }
            }
        }
        if (!started) {
            owned?.fill('\u0000')
            completion()
        }
        return started
    }

    private fun startPasswordOperation(
        phase: BackupOperation,
        password: CharArray?,
        confirmation: CharArray?,
        externalCleanup: () -> Unit = {},
        webDavRevision: Long? = null,
        block: suspend (Long, BackupExportOptions) -> Unit,
    ): Boolean {
        if (isBusy()) {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
            externalCleanup()
            return false
        }
        val include = _uiState.value.includeKeys
        val ownedPassword = password?.copyOf()
        val ownedConfirmation = confirmation?.copyOf()
        password?.fill('\u0000')
        confirmation?.fill('\u0000')
        if (include && (ownedPassword == null || ownedPassword.isEmpty() ||
                ownedConfirmation == null || ownedConfirmation.isEmpty())) {
            ownedPassword?.fill('\u0000')
            ownedConfirmation?.fill('\u0000')
            _uiState.update {
                it.copy(operation = BackupOperation.Error(BackupErrorCode.PASSWORD_REQUIRED, "包含密钥时必须输入并确认备份密码"))
            }
            externalCleanup()
            return false
        }
        if (include && !constantTimeEquals(ownedPassword!!, ownedConfirmation!!)) {
            ownedPassword.fill('\u0000')
            ownedConfirmation.fill('\u0000')
            _uiState.update {
                it.copy(operation = BackupOperation.Error(BackupErrorCode.PASSWORD_MISMATCH, "两次输入的备份密码不一致"))
            }
            externalCleanup()
            return false
        }
        val started = startOperation(
            phase = phase,
            onCompletion = {
                ownedPassword?.fill('\u0000')
                ownedConfirmation?.fill('\u0000')
                externalCleanup()
            },
            webDavRevision = webDavRevision,
        ) { token ->
            block(
                token,
                BackupExportOptions(
                    includeSecrets = include,
                    password = if (include) ownedPassword else null,
                    passwordConfirmation = if (include) ownedConfirmation else null,
                ),
            )
        }
        if (!started) {
            ownedPassword?.fill('\u0000')
            ownedConfirmation?.fill('\u0000')
            externalCleanup()
        }
        return started
    }

    private fun startOperation(
        phase: BackupOperation,
        onCompletion: () -> Unit = {},
        webDavRevision: Long? = null,
        restoreOperationId: String? = null,
        block: suspend (Long) -> Unit,
    ): Boolean {
        val token: Long
        val cleanup = OnceCleanup(onCompletion)
        val launched = synchronized(operationLock) {
            if (!initialized || currentJob != null || restoreBlocked || cleared || restoreControl?.restartAuthorized == true) return false
            if (webDavRevision != null && configMutationRevision != null) return false
            if (webDavRevision != null && webDavRevision != configRevision.get()) return false
            token = generation.incrementAndGet()
            _uiState.update { it.copy(operation = phase) }
            if (restoreOperationId != null) restoreControl = RestoreControl(restoreOperationId)
            operationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    try {
                        block(token)
                    } finally {
                        cleanup.run()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail(token, errorCodeFor(phase), errorMessageFor(phase))
                } finally {
                    synchronized(operationLock) {
                        if (currentOperationToken == token) {
                            currentJob = null
                            currentOperationToken = null
                            currentCleanup = null
                            activeWebDavRevision = null
                            val restore = restoreControl
                            if (restore != null && !restore.restartAuthorized && !restoreBlocked) {
                                restoreControl = null
                                if (_uiState.value.operation is BackupOperation.CancellingRestore) {
                                    _uiState.update { it.copy(operation = BackupOperation.Idle) }
                                }
                            }
                            if (cleared) operationScope.cancel()
                        }
                    }
                }
            }.also {
                currentJob = it
                currentOperationToken = token
                currentCleanup = cleanup
                activeWebDavRevision = webDavRevision
            }
        }
        hooks.afterOperationRegistered(phase)
        launched.start()
        return true
    }

    private fun recordBackupSuccess(
        token: Long,
        message: String,
        cleanupWarning: Boolean = false,
        webDavRevision: Long? = null,
    ) {
        val now = clock()
        val committed = synchronized(operationLock) {
            if (!isCurrentLocked(token, webDavRevision)) false else {
                _uiState.update {
                    it.copy(lastBackupTime = now, operation = BackupOperation.Success(message, cleanupWarning))
                }
                true
            }
        }
        if (committed) settings.lastBackupTime = now
    }

    private fun complete(token: Long, operation: BackupOperation, webDavRevision: Long? = null) {
        synchronized(operationLock) {
            if (isCurrentLocked(token, webDavRevision)) _uiState.update { it.copy(operation = operation) }
        }
    }

    private fun fail(token: Long, code: BackupErrorCode, message: String) {
        synchronized(operationLock) {
            if (!restoreBlocked && !webDavBlocked && isCurrentLocked(token, null)) {
                _uiState.update { it.copy(operation = BackupOperation.Error(code, message)) }
            }
        }
    }

    private fun isCurrentLocked(token: Long, webDavRevision: Long?): Boolean =
        generation.get() == token && (webDavRevision == null || configRevision.get() == webDavRevision)

    private fun authorizeRestart(token: Long, webDavRevision: Long?, operationId: String): Boolean =
        synchronized(operationLock) {
            val control = restoreControl
            if (!isCurrentLocked(token, webDavRevision) || control?.operationId != operationId || cleared) return false
            control.restartAuthorized = true
            _uiState.update { it.copy(operation = BackupOperation.Restarting) }
            true
        }

    private fun requestAuthorizedRestart(operationId: String) {
        try {
            restartRequester.requestRestart()
        } catch (error: Throwable) {
            synchronized(operationLock) {
                if (restoreControl?.operationId == operationId && restoreControl?.restartAuthorized == true) {
                    restoreBlocked = true
                    _uiState.update {
                        it.copy(operation = BackupOperation.Blocked(
                            BackupErrorCode.RESTART_FAILED,
                            "恢复包已安全暂存，重启派发失败；只能重试或取消恢复",
                        ))
                    }
                }
            }
            if (error is Error) throw error
        }
    }

    fun retryRestart(): Boolean {
        val control = synchronized(operationLock) {
            val control = restoreControl ?: return false
            if (!control.restartAuthorized || _uiState.value.operation !is BackupOperation.Blocked) return false
            restoreBlocked = false
            _uiState.update { it.copy(operation = BackupOperation.Restarting) }
            control.copy()
        }
        operationScope.launch {
            try {
                if (!control.durableAuthorized) {
                    operations.authorizePendingRestore(control.operationId)
                    synchronized(operationLock) {
                        if (restoreControl?.operationId == control.operationId) restoreControl?.durableAuthorized = true
                    }
                }
                requestAuthorizedRestart(control.operationId)
            } catch (error: Exception) {
                markRestartAuthorizationBlocked(control.operationId)
            }
        }
        return true
    }

    fun cancelPendingRestart(): Boolean {
        val operationId = synchronized(operationLock) {
            val control = restoreControl ?: return false
            if (!control.restartAuthorized) return false
            control.restartAuthorized = false
            restoreBlocked = true
            _uiState.update { it.copy(operation = BackupOperation.CancellingRestore) }
            control.operationId
        }
        operationScope.launch {
            try {
                withContext(NonCancellable) { operations.discardPendingRestore(operationId) }
                synchronized(operationLock) {
                    if (restoreControl?.operationId == operationId) {
                        restoreControl = null
                        restoreBlocked = false
                        _uiState.update { it.copy(operation = BackupOperation.Idle) }
                    }
                }
            } catch (error: Exception) {
                markRestoreCleanupBlocked(operationId)
            }
        }
        return true
    }

    private fun isRestartAuthorized(operationId: String): Boolean = synchronized(operationLock) {
        restoreControl?.operationId == operationId && restoreControl?.restartAuthorized == true
    }

    private fun markRestoreCleanupBlocked(operationId: String) {
        synchronized(operationLock) {
            if (restoreControl?.operationId == operationId) {
                restoreBlocked = true
                _uiState.update {
                    it.copy(operation = BackupOperation.Blocked(
                        BackupErrorCode.RESTORE_CLEANUP_FAILED,
                        "已取消的恢复事务清理失败，已禁止继续操作",
                    ))
                }
            }
        }
    }

    private fun markRestartAuthorizationBlocked(operationId: String) {
        synchronized(operationLock) {
            if (restoreControl?.operationId == operationId && restoreControl?.restartAuthorized == true) {
                restoreBlocked = true
                _uiState.update { it.copy(operation = BackupOperation.Blocked(
                    BackupErrorCode.RESTART_FAILED,
                    "恢复事务授权或重启失败；只能重试或取消恢复",
                )) }
            }
        }
    }
    private fun isBusy(): Boolean = synchronized(operationLock) {
        currentJob != null || restoreControl != null || restoreBlocked || cleared
    }

    private fun captureWebDavRevision(): Long? = synchronized(operationLock) {
        if (!initialized || configMutationRevision != null || webDavBlocked || restoreControl != null) null
        else configRevision.get()
    }

    private suspend fun readBoundWebDavConfig(revision: Long): BoundWebDavConfig {
        val config = try {
            withContext(ioDispatcher) { readCanonicalWebDavConfig() }
        } catch (error: Exception) {
            synchronized(operationLock) {
                webDavBlocked = true
                _uiState.update { it.copy(operation = BackupOperation.Blocked(
                    BackupErrorCode.CONNECTION_FAILED,
                    "WebDAV 安全认证记录损坏，已禁止连接",
                )) }
            }
            throw error
        }
        synchronized(operationLock) {
            if (configMutationRevision != null || configRevision.get() != revision) {
                throw CancellationException("WebDAV 配置 revision 已变更")
            }
        }
        return BoundWebDavConfig(revision, config)
    }

    private fun reserveWebDavMutationLocked(): ConfigMutationReservation? {
        if (!initialized || configMutationRevision != null || webDavBlocked || restoreControl != null || cleared) return null
        val revision = configRevision.incrementAndGet()
        configMutationRevision = revision
        val cancellation = if (activeWebDavRevision != null) {
            generation.incrementAndGet()
            takeCurrentOperationLocked()
        } else {
            OperationCancellation.None
        }
        _uiState.update {
            it.copy(
                remoteBackups = emptyList(),
                selectedRemote = null,
                remoteConfigRevision = null,
                operation = if (currentJob != null) it.operation else BackupOperation.Idle,
            )
        }
        return ConfigMutationReservation(revision, cancellation)
    }

    private fun finishWebDavMutation(revision: Long, update: (BackupUiState) -> BackupUiState) {
        synchronized(operationLock) {
            check(configMutationRevision == revision) { "WebDAV 配置保存已失效" }
            _uiState.update(update)
            configMutationRevision = null
        }
    }

    private fun completeConfigMutation(revision: Long, operation: BackupOperation) {
        synchronized(operationLock) {
            if (configMutationRevision != revision) return
            configMutationRevision = null
            _uiState.update { it.copy(operation = operation) }
        }
    }

    private fun resolveSaveAndTestFailure(revision: Long, fatal: Boolean) {
        var readFailure: Throwable? = null
        val auth = try { readCanonicalAuth() } catch (error: Throwable) {
            readFailure = error
            null
        }
        var endpoint: String? = null
        var username: String? = null
        var hasPassword = false
        auth?.use {
            endpoint = it.endpoint
            username = it.username
            hasPassword = it.passwordBytes.isNotEmpty()
        }
        synchronized(operationLock) {
            if (configMutationRevision != revision) return
            configMutationRevision = null
            webDavBlocked = fatal || auth == null || readFailure is Error
            _uiState.update { state -> state.copy(
                webdavUrl = endpoint ?: state.webdavUrl,
                webdavUser = username ?: state.webdavUser,
                hasWebDavPassword = if (auth == null) state.hasWebDavPassword else hasPassword,
                operation = if (webDavBlocked) BackupOperation.Blocked(
                    BackupErrorCode.CONNECTION_FAILED,
                    "WebDAV 保存或测试失败，已故障关闭",
                ) else BackupOperation.Error(
                    BackupErrorCode.CONNECTION_FAILED,
                    "WebDAV 配置已按 canonical 结果保存，但连接测试失败",
                ),
            ) }
        }
        if (auth != null) cleanupWebDavCachesBestEffort(endpoint.orEmpty(), username.orEmpty())
        (readFailure as? Error)?.let { throw it }
    }

    private fun takeCurrentOperationLocked(): OperationCancellation {
        val cancellation = OperationCancellation.Active(currentJob, currentCleanup)
        currentJob = null
        currentOperationToken = null
        currentCleanup = null
        activeWebDavRevision = null
        return cancellation
    }

    private fun initializeCanonicalWebDavAuth() {
        _uiState.update { it.copy(
            webdavEnabled = settings.webDavEnabled,
            autoBackup = settings.autoBackup,
            lastBackupTime = settings.lastBackupTime,
        ) }
        try {
            val existing = secrets.get(SecretCatalog.webDavAuthRecord)
            val auth = if (existing != null) {
                try { WebDavAuthRecordCodec.decode(existing) } finally { existing.fill(0) }
            } else {
                val legacyBytes = secrets.get(SecretCatalog.webDavPassword)
                val plaintext = settings.webDavPasswordPlaintext
                val password = try {
                    when {
                        legacyBytes != null -> decodeUtf8ToChars(legacyBytes)
                        plaintext != null -> plaintext.toCharArray()
                        else -> null
                    }
                } finally {
                    legacyBytes?.fill(0)
                }
                try {
                    val encoded = WebDavAuthRecordCodec.encode(settings.webDavUrl, settings.webDavUser, password)
                    try { secrets.put(SecretCatalog.webDavAuthRecord, encoded) } finally { encoded.fill(0) }
                    readCanonicalAuth()
                } finally {
                    password?.fill('\u0000')
                }
            }
            auth.use {
                _uiState.update { state -> state.copy(
                    webdavUrl = it.endpoint,
                    webdavUser = it.username,
                    hasWebDavPassword = it.passwordBytes.isNotEmpty(),
                ) }
                cleanupWebDavCachesBestEffort(it.endpoint, it.username)
            }
        } catch (_: Exception) {
            synchronized(operationLock) {
                webDavBlocked = true
                _uiState.update { it.copy(operation = BackupOperation.Blocked(
                    BackupErrorCode.CONNECTION_FAILED,
                    "WebDAV 安全认证记录无法读取，已故障关闭",
                )) }
            }
        }
    }

    private fun readCanonicalAuth(): WebDavAuthRecord {
        val encoded = secrets.get(SecretCatalog.webDavAuthRecord)
            ?: throw IllegalStateException("WebDAV 安全认证记录缺失")
        return try { WebDavAuthRecordCodec.decode(encoded) } finally { encoded.fill(0) }
    }

    private fun readCanonicalWebDavConfig(): WebDavConfig {
        val auth = readCanonicalAuth()
        auth.use {
            val password = decodeUtf8ToChars(it.passwordBytes)
            return try {
                WebDavConfig(it.endpoint, it.username, password.concatToString())
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun WebDavAuthRecord.useAndCopyPassword(): CharArray = use {
        decodeUtf8ToChars(it.passwordBytes)
    }

    private fun decodeUtf8ToChars(bytes: ByteArray): CharArray {
        val chars = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        return try {
            CharArray(chars.remaining()).also(chars::get)
        } finally {
            for (index in 0 until chars.limit()) chars.put(index, '\u0000')
        }
    }

    private fun repairPublicWebDavCache(url: String, user: String) {
        settings.webDavUrl = url
        settings.webDavUser = user
    }

    private fun removeLegacyWebDavSecrets() {
        secrets.remove(SecretCatalog.webDavPassword)
        settings.webDavPasswordPlaintext = null
    }

    private fun cleanupWebDavCachesBestEffort(url: String, user: String) {
        try {
            repairPublicWebDavCache(url, user)
            removeLegacyWebDavSecrets()
        } catch (_: Exception) {
            // canonical 记录已经是唯一事实源；下次重建会再次修复缓存。
        }
    }

    private fun publishCanonicalAfterAmbiguousWrite(revision: Long): Boolean {
        val auth = try { readCanonicalAuth() } catch (_: Exception) { return false }
        auth.use {
            synchronized(operationLock) {
                if (configMutationRevision != revision) return true
                _uiState.update { state -> state.copy(
                    webdavUrl = it.endpoint,
                    webdavUser = it.username,
                    hasWebDavPassword = it.passwordBytes.isNotEmpty(),
                ) }
                configMutationRevision = null
            }
            cleanupWebDavCachesBestEffort(it.endpoint, it.username)
        }
        return true
    }

    private fun blockOrReleaseWebDavMutation(revision: Long, error: Throwable) {
        synchronized(operationLock) {
            if (configMutationRevision == revision) {
                configMutationRevision = null
                if (error is Error) {
                    webDavBlocked = true
                    _uiState.update { it.copy(operation = BackupOperation.Blocked(
                        BackupErrorCode.CONNECTION_FAILED,
                        "WebDAV 配置保存遭遇严重错误，已故障关闭",
                    )) }
                }
            }
        }
    }

    private fun handleWebDavMutationFailure(revision: Long, original: Throwable) {
        var reconciled = false
        try {
            reconciled = publishCanonicalAfterAmbiguousWrite(revision)
        } catch (reconcileFailure: Throwable) {
            if (reconcileFailure !== original) original.addSuppressed(reconcileFailure)
        } finally {
            if (!reconciled) blockOrReleaseWebDavMutation(revision, original)
        }
    }

    private fun publishConfigFailureIfNeeded(message: String) {
        synchronized(operationLock) {
            if (_uiState.value.operation is BackupOperation.SavingConfig) {
                _uiState.update { it.copy(operation = BackupOperation.Error(
                    BackupErrorCode.CONFIGURATION_MISSING,
                    message,
                )) }
            }
        }
    }

    private fun publishFatalConfigFailure(error: Error) {
        synchronized(operationLock) {
            configMutationRevision = null
            webDavBlocked = true
            _uiState.update { it.copy(operation = BackupOperation.Blocked(
                BackupErrorCode.CONFIGURATION_MISSING,
                "WebDAV 安全配置遇到严重错误：${error::class.java.simpleName}",
            )) }
        }
    }

    private fun constantTimeEquals(left: CharArray, right: CharArray): Boolean {
        var difference = left.size xor right.size
        val size = maxOf(left.size, right.size)
        repeat(size) { index ->
            difference = difference or ((left.getOrNull(index)?.code ?: 0) xor (right.getOrNull(index)?.code ?: 0))
        }
        return difference == 0
    }

    private fun errorCodeFor(phase: BackupOperation) = when (phase) {
        BackupOperation.Testing -> BackupErrorCode.CONNECTION_FAILED
        BackupOperation.ListingRemote -> BackupErrorCode.REMOTE_LIST_FAILED
        BackupOperation.Exporting -> BackupErrorCode.EXPORT_FAILED
        BackupOperation.Uploading -> BackupErrorCode.UPLOAD_FAILED
        BackupOperation.StagingRestore -> BackupErrorCode.RESTORE_FAILED
        else -> BackupErrorCode.RESTORE_FAILED
    }

    private fun errorMessageFor(phase: BackupOperation) = when (phase) {
        BackupOperation.Testing -> "连接测试失败，请检查地址、账号和密码"
        BackupOperation.ListingRemote -> "读取远程备份失败"
        BackupOperation.Exporting -> "导出失败"
        BackupOperation.Uploading -> "上传失败"
        BackupOperation.StagingRestore -> "恢复包验证或暂存失败"
        else -> "操作失败"
    }

    companion object {
        private const val PREFS_NAME = "nexara_backup_settings"

        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(application) as T
        }
    }
}

internal fun BackupUiState.withKeysIncluded(include: Boolean): BackupUiState = copy(includeKeys = include)

private class RepositoryBackupOperations(private val repository: BackupRepository) : BackupOperations {
    override suspend fun export(options: BackupExportOptions) = repository.export(options)
    override suspend fun upload(config: WebDavConfig, options: BackupExportOptions) = repository.upload(config, options)
    override suspend fun testRemote(config: WebDavConfig) = repository.testRemote(config)
    override suspend fun listRemote(config: WebDavConfig) = repository.listRemote(config)
    override suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?) =
        repository.stageLocalRestore(operationId, input, password)
    override suspend fun stageRemoteRestore(
        operationId: String,
        config: WebDavConfig,
        selected: RemoteBackup,
        password: CharArray?,
    ) = repository.stageRemoteRestore(operationId, config, selected, password)
    override suspend fun discardPendingRestore(operationId: String) = repository.discardPendingRestore(operationId)
    override suspend fun authorizePendingRestore(operationId: String) {
        repository.authorizePendingRestore(operationId)
    }
}

private class SharedPreferencesBackupSettingsStore(private val prefs: SharedPreferences) : BackupSettingsStore {
    override var webDavEnabled: Boolean
        get() = prefs.getBoolean("webdav_enabled", false)
        set(value) { prefs.edit().putBoolean("webdav_enabled", value).apply() }
    override var autoBackup: Boolean
        get() = prefs.getBoolean("auto_backup", false)
        set(value) { prefs.edit().putBoolean("auto_backup", value).apply() }
    override var webDavUrl: String
        get() = prefs.getString("webdav_url", "").orEmpty()
        set(value) { prefs.edit().putString("webdav_url", value).apply() }
    override var webDavUser: String
        get() = prefs.getString("webdav_user", "").orEmpty()
        set(value) { prefs.edit().putString("webdav_user", value).apply() }
    override var lastBackupTime: Long
        get() = prefs.getLong("last_backup_time", 0)
        set(value) { prefs.edit().putLong("last_backup_time", value).apply() }
    override var webDavPasswordPlaintext: String?
        get() = prefs.getString("webdav_pass", null)
        set(value) {
            prefs.edit().also { editor ->
                if (value == null) editor.remove("webdav_pass") else editor.putString("webdav_pass", value)
            }.apply()
        }
}
