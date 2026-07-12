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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
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

class BackupViewModel internal constructor(
    private val operations: BackupOperations,
    private val settings: BackupSettingsStore,
    private val secrets: SecretStore,
    private val restartRequester: BackupRestartRequester,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val generation = AtomicLong(0)
    private val operationLock = Any()
    private var currentJob: Job? = null

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
        settings.webDavEnabled = enabled
        _uiState.update { it.copy(webdavEnabled = enabled) }
    }

    fun setAutoBackup(enabled: Boolean) {
        settings.autoBackup = enabled
        _uiState.update { it.copy(autoBackup = enabled) }
    }

    fun setIncludeKeys(include: Boolean) {
        _uiState.update { it.copy(includeKeys = include) }
    }

    /** password 非空时由本方法取得所有权，并在返回前清零；null 表示保留既有密码。 */
    fun saveWebDavConfig(url: String, user: String, password: CharArray?) {
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
        } finally {
            password?.fill('\u0000')
        }
        _uiState.update {
            it.copy(
                webdavUrl = url,
                webdavUser = user,
                hasWebDavPassword = secrets.contains(SecretCatalog.webDavPassword),
            )
        }
    }

    fun deleteWebDavPassword() {
        secrets.remove(SecretCatalog.webDavPassword)
        settings.webDavPasswordPlaintext = null
        _uiState.update { it.copy(hasWebDavPassword = false) }
    }

    fun testConnection(): Boolean = startOperation(BackupOperation.Testing) { token ->
        withWebDavConfig { config ->
            val result = operations.testRemote(config)
            result.getOrThrow()
        }
        complete(token, BackupOperation.Success("连接测试成功"))
    }

    fun listRemote(): Boolean = startOperation(BackupOperation.ListingRemote) { token ->
        val backups = withWebDavConfig { operations.listRemote(it) }
        if (isCurrent(token)) {
            _uiState.update {
                it.copy(
                    remoteBackups = backups,
                    selectedRemote = null,
                    operation = BackupOperation.Success("已读取 ${backups.size} 个远程备份"),
                )
            }
        }
    }

    fun selectRemote(remote: RemoteBackup): Boolean {
        if (isBusy()) return false
        val accepted = _uiState.value.remoteBackups.singleOrNull { it == remote } ?: return false
        _uiState.update { it.copy(selectedRemote = accepted) }
        return true
    }

    /** password 由本方法取得所有权并清零。 */
    fun export(output: OutputStream, password: CharArray?, confirmation: CharArray?): Boolean =
        startPasswordOperation(BackupOperation.Exporting, password, confirmation) { token, options ->
            val bytes = operations.export(options)
            try {
                output.use { it.write(bytes) }
            } finally {
                bytes.fill(0)
            }
            recordBackupSuccess(token, "导出成功")
        }

    /** password/confirmation 由本方法取得所有权并清零。 */
    fun upload(password: CharArray?, confirmation: CharArray?): Boolean =
        startPasswordOperation(BackupOperation.Uploading, password, confirmation) { token, options ->
            val receipt = withWebDavConfig { operations.upload(it, options) }
            recordBackupSuccess(
                token,
                if (receipt.pruneWarning == null) "云端上传成功" else "云端上传成功，但旧备份清理不完整",
                cleanupWarning = receipt.pruneWarning != null,
            )
        }

    /** password 由本方法取得所有权并清零。 */
    fun restoreLocal(input: InputStream, password: CharArray?): Boolean =
        startRestore(password, completion = { runCatching { input.close() } }) { owned ->
            operations.stageLocalRestore(input, owned)
        }

    /** 必须先 list 并按完整 RemoteBackup 对象选中；password 由本方法取得所有权并清零。 */
    fun restoreSelectedRemote(password: CharArray?): Boolean {
        if (isBusy()) {
            password?.fill('\u0000')
            return false
        }
        val selected = _uiState.value.selectedRemote
        if (selected == null || selected !in _uiState.value.remoteBackups) {
            password?.fill('\u0000')
            _uiState.update {
                it.copy(operation = BackupOperation.Error(BackupErrorCode.STALE_SELECTION, "远程备份选择已失效，请刷新后重试"))
            }
            return false
        }
        return startRestore(password) { owned ->
            withWebDavConfig { operations.stageRemoteRestore(it, selected, owned) }
        }
    }

    fun cancelOperation() {
        val job = synchronized(operationLock) {
            generation.incrementAndGet()
            currentJob.also { currentJob = null }
        }
        job?.cancel()
        _uiState.update { it.copy(operation = BackupOperation.Idle) }
    }

    override fun onCleared() {
        cancelOperation()
        super.onCleared()
    }

    private fun startRestore(
        password: CharArray?,
        completion: () -> Unit = {},
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
        ) { token ->
            stage(owned)
            if (!isCurrent(token)) return@startOperation
            try {
                restartRequester.requestRestart()
            } catch (_: Exception) {
                fail(token, BackupErrorCode.RESTART_FAILED, "恢复包已暂存，但安全重启请求失败")
                return@startOperation
            }
            complete(token, BackupOperation.Restarting)
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
        block: suspend (Long, BackupExportOptions) -> Unit,
    ): Boolean {
        if (isBusy()) {
            password?.fill('\u0000')
            confirmation?.fill('\u0000')
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
            return false
        }
        if (include && !constantTimeEquals(ownedPassword!!, ownedConfirmation!!)) {
            ownedPassword.fill('\u0000')
            ownedConfirmation.fill('\u0000')
            _uiState.update {
                it.copy(operation = BackupOperation.Error(BackupErrorCode.PASSWORD_MISMATCH, "两次输入的备份密码不一致"))
            }
            return false
        }
        val started = startOperation(
            phase = phase,
            onCompletion = {
                ownedPassword?.fill('\u0000')
                ownedConfirmation?.fill('\u0000')
            },
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
        }
        return started
    }

    private fun startOperation(
        phase: BackupOperation,
        onCompletion: () -> Unit = {},
        block: suspend (Long) -> Unit,
    ): Boolean {
        val token: Long
        synchronized(operationLock) {
            if (currentJob?.isActive == true) return false
            token = generation.incrementAndGet()
            _uiState.update { it.copy(operation = phase) }
            val launched = viewModelScope.launch {
                try {
                    block(token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail(token, errorCodeFor(phase), errorMessageFor(phase))
                } finally {
                    synchronized(operationLock) {
                        if (generation.get() == token) currentJob = null
                    }
                }
            }
            launched.invokeOnCompletion { onCompletion() }
            currentJob = launched
        }
        return true
    }

    private fun recordBackupSuccess(token: Long, message: String, cleanupWarning: Boolean = false) {
        if (!isCurrent(token)) return
        val now = clock()
        settings.lastBackupTime = now
        _uiState.update {
            it.copy(lastBackupTime = now, operation = BackupOperation.Success(message, cleanupWarning))
        }
    }

    private fun complete(token: Long, operation: BackupOperation) {
        if (isCurrent(token)) _uiState.update { it.copy(operation = operation) }
    }

    private fun fail(token: Long, code: BackupErrorCode, message: String) {
        if (isCurrent(token)) _uiState.update { it.copy(operation = BackupOperation.Error(code, message)) }
    }

    private fun isCurrent(token: Long): Boolean = generation.get() == token
    private fun isBusy(): Boolean = synchronized(operationLock) { currentJob?.isActive == true }

    private inline fun <T> withWebDavConfig(block: (WebDavConfig) -> T): T {
        val state = _uiState.value
        val secretBytes = secrets.get(SecretCatalog.webDavPassword)
        try {
            val password = secretBytes?.toString(Charsets.UTF_8).orEmpty()
            return block(WebDavConfig(state.webdavUrl, state.webdavUser, password))
        } finally {
            secretBytes?.fill(0)
        }
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
