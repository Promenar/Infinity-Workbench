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

data class QueueState(
    val queueLength: Int = 0,
    val isProcessing: Boolean = false,
    val progress: Float = 0f
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

    private val _availableModels = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableModels: StateFlow<List<Pair<String, String>>> = _availableModels.asStateFlow()

    private val prefs = app.getSharedPreferences("rag_settings", 0)
    private val settingsPrefs = app.getSharedPreferences("nexara_settings", 0)

    private val _stats = MutableStateFlow(RagStats())
    val stats: StateFlow<RagStats> = _stats.asStateFlow()

    private val _vectorStats = MutableStateFlow<VectorStats?>(null)
    val vectorStats: StateFlow<VectorStats?> = _vectorStats.asStateFlow()

    init {
        loadConfig()
        loadAvailableModels()
        loadStats()
        loadDocuments()
        loadVectorStats()
        loadFolderStats()
        observeQueue()
    }

    private fun observeQueue() {
        app.vectorizationQueue.setOnStateChange { queue, currentTask ->
            _isIndexing.value = app.vectorizationQueue.getState().isProcessing
            _indexingProgress.value = (currentTask?.progress ?: 0.0).toFloat() / 100f
            if (queue.isEmpty() && currentTask == null) {
                loadStats()
                loadDocuments()
            }
        }
    }

    private fun loadConfig() {
        _config.value = RagConfiguration(
            enableMemory = prefs.getBoolean("enable_memory", true),
            enableDocs = prefs.getBoolean("enable_docs", true),
            enableKnowledgeGraph = prefs.getBoolean("enable_kg", false),
            enableQueryRewrite = prefs.getBoolean("enable_query_rewrite", false),
            enableHybridSearch = prefs.getBoolean("enable_hybrid_search", false),
            enableRerank = prefs.getBoolean("enable_rerank", false),
            enableIncrementalHash = prefs.getBoolean("enable_incremental_hash", true),
            enableLocalPreprocess = prefs.getBoolean("enable_local_preprocess", false),
            queryRewriteStrategy = prefs.getString("query_rewrite_strategy", "multi-query") ?: "multi-query",
            queryRewriteModel = prefs.getString("query_rewrite_model", null),
            queryRewriteCount = prefs.getInt("query_rewrite_count", 3),
            memoryLimit = prefs.getInt("memory_limit", 5),
            memoryThreshold = prefs.getFloat("memory_threshold", 0.7f),
            docLimit = prefs.getInt("doc_limit", 8),
            docThreshold = prefs.getFloat("doc_threshold", 0.45f),
            docChunkSize = prefs.getInt("doc_chunk_size", 800),
            chunkOverlap = prefs.getInt("chunk_overlap", 100),
            memoryChunkSize = prefs.getInt("memory_chunk_size", 1000),
            rerankTopK = prefs.getInt("rerank_top_k", 30),
            rerankFinalK = prefs.getInt("rerank_final_k", 5),
            hybridAlpha = prefs.getFloat("hybrid_alpha", 0.6f),
            hybridBM25Boost = prefs.getFloat("hybrid_bm25_boost", 1.0f),
            kgExtractionModel = prefs.getString("kg_model", null),
            kgExtractionPrompt = prefs.getString("kg_prompt", null),
            kgFreeMode = prefs.getBoolean("kg_free_mode", false),
            kgDomainAuto = prefs.getBoolean("kg_domain_auto", false),
            costStrategy = prefs.getString("cost_strategy", "on-demand") ?: "on-demand",
            jitMaxChunks = prefs.getInt("jit_max_chunks", 0),
            contextWindow = prefs.getInt("context_window", 20),
            summaryThreshold = prefs.getInt("summary_threshold", 10)
        )
    }

    private fun saveConfig(config: RagConfiguration) {
        prefs.edit()
            .putBoolean("enable_memory", config.enableMemory)
            .putBoolean("enable_docs", config.enableDocs)
            .putBoolean("enable_kg", config.enableKnowledgeGraph)
            .putBoolean("enable_query_rewrite", config.enableQueryRewrite)
            .putBoolean("enable_hybrid_search", config.enableHybridSearch)
            .putBoolean("enable_rerank", config.enableRerank)
            .putBoolean("enable_incremental_hash", config.enableIncrementalHash)
            .putBoolean("enable_local_preprocess", config.enableLocalPreprocess)
            .putString("query_rewrite_strategy", config.queryRewriteStrategy)
            .putString("query_rewrite_model", config.queryRewriteModel)
            .putInt("query_rewrite_count", config.queryRewriteCount)
            .putInt("memory_limit", config.memoryLimit)
            .putFloat("memory_threshold", config.memoryThreshold)
            .putInt("doc_limit", config.docLimit)
            .putFloat("doc_threshold", config.docThreshold)
            .putInt("doc_chunk_size", config.docChunkSize)
            .putInt("chunk_overlap", config.chunkOverlap)
            .putInt("memory_chunk_size", config.memoryChunkSize)
            .putInt("rerank_top_k", config.rerankTopK)
            .putInt("rerank_final_k", config.rerankFinalK)
            .putFloat("hybrid_alpha", config.hybridAlpha)
            .putFloat("hybrid_bm25_boost", config.hybridBM25Boost)
            .putString("kg_model", config.kgExtractionModel)
            .putString("kg_prompt", config.kgExtractionPrompt)
            .putBoolean("kg_free_mode", config.kgFreeMode)
            .putBoolean("kg_domain_auto", config.kgDomainAuto)
            .putString("cost_strategy", config.costStrategy)
            .putInt("jit_max_chunks", config.jitMaxChunks)
            .putInt("context_window", config.contextWindow)
            .putInt("summary_threshold", config.summaryThreshold)
            .apply()
    }

    private fun loadAvailableModels() {
        val allIds = settingsPrefs.getStringSet("all_models", null) ?: emptySet()
        val models = allIds.map { id ->
            val name = settingsPrefs.getString("model_info_${id}_name", id) ?: id
            name to id
        }.sortedBy { it.first }
        _availableModels.value = models
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
                val docCount = documentDao.getCount()
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
                    vectorDao.deleteByDocId(docId)
                }
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun importDocuments(uris: List<android.net.Uri>, folderId: String? = null) {
        viewModelScope.launch {
            app.documentImporter.importFromUris(uris, folderId)
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
        saveConfig(config)
    }

    fun updateConfig(transform: (RagConfiguration) -> RagConfiguration) {
        _config.update { current ->
            val newConfig = transform(current)
            saveConfig(newConfig)
            newConfig
        }
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
