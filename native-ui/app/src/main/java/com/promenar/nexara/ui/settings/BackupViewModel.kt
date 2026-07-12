package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
}

sealed interface BackupOperation {
    data object Idle : BackupOperation
    data object Testing : BackupOperation
    data object ListingRemote : BackupOperation
    data object Exporting : BackupOperation
    data object Uploading : BackupOperation
    data object StagingRestore : BackupOperation
    data object Restarting : BackupOperation
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
        BackupOperation.Testing -> "正在测试连接…"
        BackupOperation.ListingRemote -> "正在读取远程备份…"
        BackupOperation.Exporting -> "正在导出备份…"
        BackupOperation.Uploading -> "正在上传备份…"
        BackupOperation.StagingRestore -> "正在验证并暂存恢复包…"
        BackupOperation.Restarting -> "恢复包已暂存，正在安全重启…"
        is BackupOperation.Success -> current.message
        is BackupOperation.Error -> current.message
    }
    val error: String? get() = (operation as? BackupOperation.Error)?.message
    val canExecute: Boolean get() = operation !is BackupOperation.Testing &&
        operation !is BackupOperation.ListingRemote &&
        operation !is BackupOperation.Exporting &&
        operation !is BackupOperation.Uploading &&
        operation !is BackupOperation.StagingRestore &&
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
    suspend fun stageLocalRestore(input: InputStream, password: CharArray?): PendingRestoreMetadata
    suspend fun stageRemoteRestore(config: WebDavConfig, selected: RemoteBackup, password: CharArray?): PendingRestoreMetadata
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
private data class SelectedRemote(val remote: RemoteBackup, val bound: BoundWebDavConfig)
private data class ConfigMutationReservation(val revision: Long, val cancellation: OperationCancellation)

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
) : ViewModel() {
    private val generation = AtomicLong(0)
    private val configRevision = AtomicLong(0)
    private val operationLock = Any()
    private var currentJob: Job? = null
    private var currentCleanup: OnceCleanup? = null
    private var activeWebDavRevision: Long? = null
    private var configMutationRevision: Long? = null

    private val _uiState = MutableStateFlow(loadInitialState())
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
        migratePlaintextPassword()
        _uiState.update { it.copy(hasWebDavPassword = secrets.contains(SecretCatalog.webDavPassword)) }
    }

    fun setWebdavEnabled(enabled: Boolean) {
        val reservation = synchronized(operationLock) {
            if (_uiState.value.webdavEnabled == enabled) return
            reserveWebDavMutationLocked() ?: return
        }
        reservation.cancellation.run()
        try {
            settings.webDavEnabled = enabled
            finishWebDavMutation(reservation.revision) { it.copy(webdavEnabled = enabled) }
        } catch (error: Exception) {
            abortWebDavMutation(reservation.revision)
            throw error
        }
    }

    fun setAutoBackup(enabled: Boolean) {
        settings.autoBackup = enabled
        _uiState.update { it.copy(autoBackup = enabled) }
    }

    fun setIncludeKeys(include: Boolean) {
        _uiState.update { it.copy(includeKeys = include) }
    }

    /** password 非空时由本方法取得所有权，并在返回前清零；null 表示保留既有密码。 */
    fun saveWebDavConfig(url: String, user: String, password: CharArray?): Boolean {
        val reservation = synchronized(operationLock) {
            val changed = url != _uiState.value.webdavUrl || user != _uiState.value.webdavUser || password != null
            if (!changed) return true
            reserveWebDavMutationLocked()
        }
        if (reservation == null) {
            password?.fill('\u0000')
            return false
        }
        reservation.cancellation.run()
        try {
            if (password != null) {
                if (password.isEmpty()) {
                    secrets.remove(SecretCatalog.webDavPassword)
                } else {
                    val encoded = encodeUtf8(password)
                    try {
                        secrets.put(SecretCatalog.webDavPassword, encoded)
                    } finally {
                        encoded.fill(0)
                    }
                }
                settings.webDavPasswordPlaintext = null
            }
            settings.webDavUrl = url
            settings.webDavUser = user
            val hasPassword = secrets.contains(SecretCatalog.webDavPassword)
            finishWebDavMutation(reservation.revision) {
                it.copy(webdavUrl = url, webdavUser = user, hasWebDavPassword = hasPassword)
            }
            return true
        } catch (error: Exception) {
            abortWebDavMutation(reservation.revision)
            throw error
        } finally {
            password?.fill('\u0000')
        }
    }

    fun deleteWebDavPassword(): Boolean {
        val reservation = synchronized(operationLock) { reserveWebDavMutationLocked() } ?: return false
        reservation.cancellation.run()
        try {
            secrets.remove(SecretCatalog.webDavPassword)
            settings.webDavPasswordPlaintext = null
            finishWebDavMutation(reservation.revision) { it.copy(hasWebDavPassword = false) }
            return true
        } catch (error: Exception) {
            abortWebDavMutation(reservation.revision)
            throw error
        }
    }

    fun testConnection(): Boolean {
        val bound = captureWebDavConfig() ?: return false
        return startOperation(BackupOperation.Testing, webDavRevision = bound.revision) { token ->
            operations.testRemote(bound.config).getOrThrow()
            hooks.beforeStateCommit(BackupOperation.Testing)
            complete(token, BackupOperation.Success("连接测试成功"), bound.revision)
        }
    }

    fun listRemote(): Boolean {
        val bound = captureWebDavConfig() ?: return false
        return startOperation(BackupOperation.ListingRemote, webDavRevision = bound.revision) { token ->
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
            if (currentJob != null || configMutationRevision != null) return false
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
        val bound = captureWebDavConfig()
        if (bound == null) {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
            return false
        }
        return startPasswordOperation(
            BackupOperation.Uploading,
            password,
            confirmation,
            webDavRevision = bound.revision,
        ) { token, options ->
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
        startRestore(password, completion = { input.close() }) { owned ->
            operations.stageLocalRestore(input, owned)
        }

    /** 必须先 list 并按完整 RemoteBackup 对象选中；password 由本方法取得所有权并清零。 */
    fun restoreSelectedRemote(password: CharArray?): Boolean {
        var rejectedAsBusy = false
        val selectedRevision = synchronized(operationLock) {
            if (currentJob != null || configMutationRevision != null) {
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
        val bound = captureWebDavConfig()
        if (bound == null || bound.revision != selectedRevision.second) {
            password?.fill('\u0000')
            return false
        }
        val selection = SelectedRemote(selectedRevision.first, bound)
        return startRestore(password, webDavRevision = selection.bound.revision) { owned ->
            operations.stageRemoteRestore(selection.bound.config, selection.remote, owned)
        }
    }

    fun cancelOperation() {
        val cancellation = synchronized(operationLock) {
            generation.incrementAndGet()
            takeCurrentOperationLocked().also {
                _uiState.update { state -> state.copy(operation = BackupOperation.Idle) }
            }
        }
        cancellation.run()
    }

    override fun onCleared() {
        cancelOperation()
        super.onCleared()
    }

    private fun startRestore(
        password: CharArray?,
        completion: () -> Unit = {},
        webDavRevision: Long? = null,
        stage: suspend (CharArray?) -> PendingRestoreMetadata,
    ): Boolean {
        val owned = password?.copyOf()
        password?.fill('\u0000')
        val started = startOperation(
            phase = BackupOperation.StagingRestore,
            onCompletion = {
                owned?.fill('\u0000')
                completion()
            },
            webDavRevision = webDavRevision,
        ) { token ->
            stage(owned)
            hooks.beforeStateCommit(BackupOperation.StagingRestore)
            commitRestart(token, webDavRevision)
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
        block: suspend (Long) -> Unit,
    ): Boolean {
        val token: Long
        val cleanup = OnceCleanup(onCompletion)
        val launched = synchronized(operationLock) {
            if (currentJob != null) return false
            if (webDavRevision != null && configMutationRevision != null) return false
            if (webDavRevision != null && webDavRevision != configRevision.get()) return false
            token = generation.incrementAndGet()
            _uiState.update { it.copy(operation = phase) }
            viewModelScope.launch(start = CoroutineStart.LAZY) {
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
                        if (generation.get() == token) {
                            currentJob = null
                            currentCleanup = null
                            activeWebDavRevision = null
                        }
                    }
                }
            }.also {
                currentJob = it
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
            if (isCurrentLocked(token, null)) {
                _uiState.update { it.copy(operation = BackupOperation.Error(code, message)) }
            }
        }
    }

    private fun isCurrentLocked(token: Long, webDavRevision: Long?): Boolean =
        generation.get() == token && (webDavRevision == null || configRevision.get() == webDavRevision)

    private fun commitRestart(token: Long, webDavRevision: Long?) {
        synchronized(operationLock) {
            if (!isCurrentLocked(token, webDavRevision)) return
            try {
                restartRequester.requestRestart()
                _uiState.update { it.copy(operation = BackupOperation.Restarting) }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        operation = BackupOperation.Error(
                            BackupErrorCode.RESTART_FAILED,
                            "恢复包已暂存，但安全重启请求失败",
                        ),
                    )
                }
            }
        }
    }
    private fun isBusy(): Boolean = synchronized(operationLock) { currentJob != null }

    private fun captureWebDavConfig(): BoundWebDavConfig? {
        val snapshot = synchronized(operationLock) {
            if (configMutationRevision != null) return null
            val state = _uiState.value
            Triple(configRevision.get(), state.webdavUrl, state.webdavUser)
        }
        val secretBytes = secrets.get(SecretCatalog.webDavPassword)
        try {
            val password = secretBytes?.toString(Charsets.UTF_8).orEmpty()
            return synchronized(operationLock) {
                if (configMutationRevision != null || configRevision.get() != snapshot.first) null
                else BoundWebDavConfig(snapshot.first, WebDavConfig(snapshot.second, snapshot.third, password))
            }
        } finally {
            secretBytes?.fill(0)
        }
    }

    private fun reserveWebDavMutationLocked(): ConfigMutationReservation? {
        if (configMutationRevision != null) return null
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

    private fun abortWebDavMutation(revision: Long) {
        synchronized(operationLock) {
            if (configMutationRevision == revision) configMutationRevision = null
        }
    }

    private fun takeCurrentOperationLocked(): OperationCancellation {
        val cancellation = OperationCancellation.Active(currentJob, currentCleanup)
        currentJob = null
        currentCleanup = null
        activeWebDavRevision = null
        return cancellation
    }

    private fun migratePlaintextPassword() {
        val plaintext = settings.webDavPasswordPlaintext ?: return
        if (plaintext.isNotEmpty() && !secrets.contains(SecretCatalog.webDavPassword)) {
            val chars = plaintext.toCharArray()
            val bytes = encodeUtf8(chars)
            try {
                secrets.put(SecretCatalog.webDavPassword, bytes)
            } finally {
                bytes.fill(0)
                chars.fill('\u0000')
            }
        }
        settings.webDavPasswordPlaintext = null
    }

    private fun loadInitialState() = BackupUiState(
        webdavEnabled = settings.webDavEnabled,
        autoBackup = settings.autoBackup,
        webdavUrl = settings.webDavUrl,
        webdavUser = settings.webDavUser,
        hasWebDavPassword = secrets.contains(SecretCatalog.webDavPassword),
        lastBackupTime = settings.lastBackupTime,
    )

    private fun constantTimeEquals(left: CharArray, right: CharArray): Boolean {
        var difference = left.size xor right.size
        val size = maxOf(left.size, right.size)
        repeat(size) { index ->
            difference = difference or ((left.getOrNull(index)?.code ?: 0) xor (right.getOrNull(index)?.code ?: 0))
        }
        return difference == 0
    }

    private fun encodeUtf8(value: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        return try {
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
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
    override suspend fun stageLocalRestore(input: InputStream, password: CharArray?) =
        repository.stageLocalRestore(input, password)
    override suspend fun stageRemoteRestore(config: WebDavConfig, selected: RemoteBackup, password: CharArray?) =
        repository.stageRemoteRestore(config, selected, password)
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
