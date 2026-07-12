package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.repository.BackupRepository
import com.promenar.nexara.data.backup.BackupExportOptions
import com.promenar.nexara.data.backup.BackupValidationException
import com.promenar.nexara.data.backup.RestoreRelayActivity
import com.promenar.nexara.data.remote.webdav.WebDavConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

data class BackupUiState(
    val sessionsChecked: Boolean = true,
    val libraryChecked: Boolean = true,
    val filesChecked: Boolean = true,
    val settingsChecked: Boolean = true,
    val keysChecked: Boolean = false,
    val webdavEnabled: Boolean = false,
    val autoBackup: Boolean = false,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPass: String = "",
    val lastBackupTime: Long = 0,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val progress: Float = 0f,
    val statusMessage: String? = null,
    val error: String? = null
)

class BackupViewModel(application: Application) : ViewModel() {
    private val app = application as NexaraApplication
    private val prefs: SharedPreferences = application.getSharedPreferences("nexara_backup_settings", Context.MODE_PRIVATE)
    private val repository = BackupRepository(application)

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.update {
            it.copy(
                webdavEnabled = prefs.getBoolean("webdav_enabled", false),
                autoBackup = prefs.getBoolean("auto_backup", false),
                webdavUrl = prefs.getString("webdav_url", "") ?: "",
                webdavUser = prefs.getString("webdav_user", "") ?: "",
                webdavPass = prefs.getString("webdav_pass", "") ?: "",
                lastBackupTime = prefs.getLong("last_backup_time", 0)
            )
        }
    }

    fun updateWebdavConfig(url: String, user: String, pass: String) {
        _uiState.update { it.copy(webdavUrl = url, webdavUser = user, webdavPass = pass) }
        prefs.edit().apply {
            putString("webdav_url", url)
            putString("webdav_user", user)
            putString("webdav_pass", pass)
            apply()
        }
    }

    fun setWebdavEnabled(enabled: Boolean) {
        _uiState.update { it.copy(webdavEnabled = enabled) }
        prefs.edit().putBoolean("webdav_enabled", enabled).apply()
    }

    fun setAutoBackup(enabled: Boolean) {
        _uiState.update { it.copy(autoBackup = enabled) }
        prefs.edit().putBoolean("auto_backup", enabled).apply()
    }

    fun toggleCheck(type: String, checked: Boolean) {
        _uiState.update {
            when (type) {
                "sessions" -> it.copy(sessionsChecked = checked)
                "library" -> it.copy(libraryChecked = checked)
                "files" -> it.copy(filesChecked = checked)
                "settings" -> it.copy(settingsChecked = checked)
                "keys" -> it.copy(keysChecked = checked)
                else -> it
            }
        }
    }

    fun performExport(outputStream: OutputStream) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isExporting = true, error = null, progress = 0f, statusMessage = "Starting Export...") }
                val data = repository.export(exportOptions(_uiState.value))
                try {
                    outputStream.use { it.write(data) }
                } finally {
                    data.fill(0)
                }
                _uiState.update { it.copy(isExporting = false, lastBackupTime = System.currentTimeMillis(), statusMessage = "Export Success") }
                prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, error = e.message, statusMessage = "Export Failed") }
            }
        }
    }

    fun performImport(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isImporting = true, error = null, progress = 0f, statusMessage = "Starting Import...") }
                repository.stageLocalRestore(inputStream)
                _uiState.update { it.copy(statusMessage = "Restore staged; restarting safely") }
                RestoreRelayActivity.requestRestart(app)
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, error = e.message, statusMessage = "Import Failed") }
            }
        }
    }

    fun uploadToCloud() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isExporting = true, error = null, progress = 0f, statusMessage = "Preparing Upload...") }
                val receipt = repository.upload(webDavConfig(_uiState.value), exportOptions(_uiState.value))
                val status = if (receipt.pruneWarning == null) "Cloud Upload Success" else "Cloud Upload Success (cleanup warning)"
                _uiState.update { it.copy(isExporting = false, lastBackupTime = System.currentTimeMillis(), statusMessage = status) }
                prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, error = e.message, statusMessage = null) }
            }
        }
    }

    fun downloadFromCloud(remoteFileName: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isImporting = true, error = null, progress = 0f, statusMessage = "Connecting to Cloud...") }
                val config = webDavConfig(_uiState.value)
                val selected = repository.listRemote(config).singleOrNull { it.fileName == remoteFileName }
                    ?: throw BackupValidationException("Remote backup selection is stale; refresh and retry")
                repository.stageRemoteRestore(config, selected)
                _uiState.update { it.copy(statusMessage = "Restore staged; restarting safely") }
                RestoreRelayActivity.requestRestart(app)
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, error = e.message, statusMessage = null) }
            }
        }
    }

    private fun exportOptions(state: BackupUiState): BackupExportOptions {
        if (state.keysChecked) {
            throw BackupValidationException("Including API keys requires a backup password and confirmation; this UI will support it in the next step")
        }
        return BackupExportOptions()
    }

    private fun webDavConfig(state: BackupUiState) = WebDavConfig(
        baseUrl = state.webdavUrl,
        username = state.webdavUser,
        password = state.webdavPass,
    )

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BackupViewModel(application) as T
                }
            }
    }
}
