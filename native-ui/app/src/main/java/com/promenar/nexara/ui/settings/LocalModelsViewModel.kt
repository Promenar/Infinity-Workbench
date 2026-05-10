package com.promenar.nexara.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.data.local.inference.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LocalModelsViewModel(application: Application) : ViewModel() {

    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences("nexara_settings", 0)

    private val engine: LocalInferenceEngine = LocalInferenceEngine(appContext)
    private val storageManager: ModelStorageManager = ModelStorageManager(appContext)
    private val downloader: ModelDownloader = ModelDownloader(appContext)

    val mainSlot: StateFlow<SlotState> = engine.mainSlot
    val embeddingSlot: StateFlow<SlotState> = engine.embeddingSlot
    val rerankSlot: StateFlow<SlotState> = engine.rerankSlot

    val gpuAvailable: Boolean = GpuDetector.supportsVulkan()

    private val _isEngineEnabled = MutableStateFlow(
        prefs.getBoolean("local_models_enabled", false)
    )
    val isEngineEnabled: StateFlow<Boolean> = _isEngineEnabled.asStateFlow()

    private val _availableModels = MutableStateFlow<List<StoredModel>>(emptyList())
    val availableModels: StateFlow<List<StoredModel>> = _availableModels.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloader.DownloadState?>(null)
    val downloadState: StateFlow<ModelDownloader.DownloadState?> = _downloadState.asStateFlow()

    init {
        refreshModelList()
    }

    fun refreshModelList() {
        viewModelScope.launch {
            _availableModels.value = storageManager.listModels()
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            storageManager.importModel(uri)
                .onSuccess {
                    refreshModelList()
                }
                .onFailure { e ->
                    _importError.value = e.message
                }
            _isImporting.value = false
        }
    }

    fun deleteModel(filePath: String) {
        viewModelScope.launch {
            if (isModelLoadedInSlot(filePath)) {
                val slot = findSlotForModel(filePath)
                if (slot != null) engine.unloadModel(slot)
            }
            storageManager.deleteModel(filePath)
                .onSuccess { refreshModelList() }
        }
    }

    fun loadModel(slot: SlotType, modelPath: String) {
        viewModelScope.launch {
            engine.loadModel(slot, modelPath)
        }
    }

    fun unloadModel(slot: SlotType) {
        viewModelScope.launch {
            engine.unloadModel(slot)
        }
    }

    fun downloadModel(url: String, fileName: String) {
        viewModelScope.launch {
            _downloadState.value = ModelDownloader.DownloadState(
                url = url,
                fileName = fileName,
                isDownloading = true
            )
            downloader.download(url, fileName) { progress ->
                _downloadState.value = _downloadState.value?.copy(
                    downloadedBytes = ((_downloadState.value?.totalBytes ?: 0L) * progress).toLong()
                )
            }.onSuccess {
                _downloadState.value = _downloadState.value?.copy(
                    isDownloading = false,
                    isComplete = true
                )
                refreshModelList()
            }.onFailure { e ->
                _downloadState.value = _downloadState.value?.copy(
                    isDownloading = false,
                    error = e.message
                )
            }
        }
    }

    fun cancelDownload() {
        downloader.cancel()
    }

    fun clearDownloadState() {
        _downloadState.value = null
    }

    fun setEngineEnabled(enabled: Boolean) {
        _isEngineEnabled.value = enabled
        prefs.edit().putBoolean("local_models_enabled", enabled).apply()
        if (!enabled) {
            viewModelScope.launch {
                engine.unloadModel(SlotType.MAIN)
                engine.unloadModel(SlotType.EMBEDDING)
                engine.unloadModel(SlotType.RERANK)
            }
        }
    }

    fun isModelLoadedInSlot(filePath: String): Boolean {
        return mainSlot.value.modelPath == filePath ||
                embeddingSlot.value.modelPath == filePath ||
                rerankSlot.value.modelPath == filePath
    }

    fun findSlotForModel(filePath: String): SlotType? {
        return when (filePath) {
            mainSlot.value.modelPath -> SlotType.MAIN
            embeddingSlot.value.modelPath -> SlotType.EMBEDDING
            rerankSlot.value.modelPath -> SlotType.RERANK
            else -> null
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LocalModelsViewModel(application) as T
                }
            }
    }
}
