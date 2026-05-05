package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.DocumentEntity
import com.promenar.nexara.data.local.db.entity.FolderEntity
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.VectorStats
import com.promenar.nexara.data.rag.VectorStatsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RagStats(
    val documentCount: Int = 0,
    val memoryBytes: Long = 0L,
    val graphEntityCount: Int = 0
)

class RagViewModel(
    application: Application
) : ViewModel() {

    private val app = application as NexaraApplication
    private val database = app.database
    private val folderDao = database.folderDao()
    private val documentDao = database.documentDao()
    private val vectorDao = database.vectorDao()
    private val kgNodeDao = database.kgNodeDao()
    private val kgEdgeDao = database.kgEdgeDao()

    private val vectorStatsService = VectorStatsService(vectorDao)

    val folders: StateFlow<List<FolderEntity>> = folderDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _folderStats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderStats: StateFlow<Map<String, Int>> = _folderStats.asStateFlow()

    private val _documents = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val documents: StateFlow<List<DocumentEntity>> = _documents.asStateFlow()

    private val _searchResults = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val searchResults: StateFlow<List<DocumentEntity>> = _searchResults.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexingProgress = MutableStateFlow(0f)
    val indexingProgress: StateFlow<Float> = _indexingProgress.asStateFlow()

    private val _config = MutableStateFlow(RagConfiguration())
    val config: StateFlow<RagConfiguration> = _config.asStateFlow()

    private val _stats = MutableStateFlow(RagStats())
    val stats: StateFlow<RagStats> = _stats.asStateFlow()

    private val _vectorStats = MutableStateFlow<VectorStats?>(null)
    val vectorStats: StateFlow<VectorStats?> = _vectorStats.asStateFlow()

    init {
        loadStats()
        loadDocuments()
        loadVectorStats()
        loadFolderStats()
    }

    fun loadCollections() {
        viewModelScope.launch {
            loadStats()
        }
    }

    private fun loadDocuments() {
        viewModelScope.launch {
            try {
                documentDao.observeAll().collect { list ->
                    _documents.value = list
                }
            } catch (_: Exception) { }
        }
    }

    private fun loadVectorStats() {
        viewModelScope.launch {
            try {
                _vectorStats.value = vectorStatsService.getStats()
            } catch (_: Exception) { }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val docCount = _documents.value.size
                val nodeCount = kgNodeDao.getCount()
                val vStats = vectorStatsService.getStats()
                _vectorStats.value = vStats
                _stats.value = RagStats(
                    documentCount = docCount,
                    memoryBytes = (vStats.total * 4L * 1536),
                    graphEntityCount = nodeCount
                )
            } catch (_: Exception) { }
        }
    }

    private fun loadFolderStats() {
        viewModelScope.launch {
            try {
                folders.collect { folderList ->
                    val stats = mutableMapOf<String, Int>()
                    for (folder in folderList) {
                        stats[folder.id] = documentDao.countByFolderId(folder.id)
                    }
                    _folderStats.value = stats
                }
            } catch (_: Exception) { }
        }
    }

    fun loadDocumentsForFolder(folderId: String) {
        viewModelScope.launch {
            try {
                _documents.value = documentDao.getByFolderId(folderId)
            } catch (_: Exception) { }
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            try {
                val folder = folderDao.getById(id) ?: return@launch
                folderDao.delete(folder)
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun deleteDocuments(ids: List<String>) {
        viewModelScope.launch {
            try {
                for (docId in ids) {
                    documentDao.deleteById(docId)
                }
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                _searchResults.value = emptyList()
                return@launch
            }
            try {
                val allDocs = _documents.value
                _searchResults.value = allDocs.filter {
                    it.title?.contains(query, ignoreCase = true) == true
                }
            } catch (_: Exception) { }
        }
    }

    fun updateConfig(config: RagConfiguration) {
        _config.update { config }
    }

    fun updateConfig(transform: (RagConfiguration) -> RagConfiguration) {
        _config.update(transform)
    }

    fun applyPreset(preset: String) {
        _config.update { current ->
            when (preset) {
                "Balanced" -> current.copy(
                    docChunkSize = 800,
                    chunkOverlap = 100,
                    contextWindow = 20,
                    summaryThreshold = 10
                )
                "Writing" -> current.copy(
                    docChunkSize = 1200,
                    chunkOverlap = 200,
                    contextWindow = 32,
                    summaryThreshold = 15
                )
                "Coding" -> current.copy(
                    docChunkSize = 500,
                    chunkOverlap = 50,
                    contextWindow = 16,
                    summaryThreshold = 8
                )
                else -> current
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return RagViewModel(application) as T
                }
            }
    }
}
