package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val sessionsChecked: Boolean = true,
    val libraryChecked: Boolean = true,
    val filesChecked: Boolean = true,
    val settingsChecked: Boolean = true,
    val keysChecked: Boolean = true,
    val webdavEnabled: Boolean = false,
    val autoBackup: Boolean = false,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPass: String = "",
    val lastBackupTime: Long = 0,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val error: String? = null
)

import com.promenar.nexara.data.repository.BackupRepository
import java.io.InputStream
import java.io.OutputStream

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
                _uiState.update { it.copy(isExporting = true, error = null) }
                val data = repository.prepareBackupPackage(_uiState.value)
                outputStream.use { it.write(data) }
                _uiState.update { it.copy(isExporting = false, lastBackupTime = System.currentTimeMillis()) }
                prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, error = e.message) }
            }
        }
    }

    fun performImport(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isImporting = true, error = null) }
                repository.restoreFromPackage(inputStream)
                _uiState.update { it.copy(isImporting = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, error = e.message) }
            }
        }
    }

    fun uploadToCloud() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isExporting = true, error = null) }
                val success = repository.uploadToWebDav(_uiState.value)
                if (success) {
                    _uiState.update { it.copy(isExporting = false, lastBackupTime = System.currentTimeMillis()) }
                    prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply()
                } else {
                    _uiState.update { it.copy(isExporting = false, error = "WebDAV Upload Failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, error = e.message) }
            }
        }
    }

    fun downloadFromCloud(remoteFileName: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isImporting = true, error = null) }
                val success = repository.restoreFromWebDav(_uiState.value, remoteFileName)
                if (success) {
                    _uiState.update { it.copy(isImporting = false) }
                } else {
                    _uiState.update { it.copy(isImporting = false, error = "WebDAV Restore Failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, error = e.message) }
            }
        }
    }

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
