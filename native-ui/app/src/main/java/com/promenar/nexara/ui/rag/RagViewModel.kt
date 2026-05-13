package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.VectorStats
import com.promenar.nexara.data.rag.VectorStatsService
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.IDocumentRepository
import com.promenar.nexara.domain.repository.IFolderRepository
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.promenar.nexara.domain.usecase.DeleteDocumentUseCase
import com.promenar.nexara.domain.usecase.IdGenerator
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.promenar.nexara.ui.common.ModelItem
import com.promenar.nexara.ui.common.ModelCapability

data class RagStats(
    val documentCount: Int = 0,
    val memoryCount: Int = 0,
    val graphEntityCount: Int = 0
)

data class QueueState(
    val queueLength: Int = 0,
    val isProcessing: Boolean = false,
    val progress: Float = 0f
)

class RagViewModel(
    application: Application,
    private val documentRepository: IDocumentRepository,
    private val vectorRepository: IVectorRepository,
    private val kgRepository: IKnowledgeGraphRepository,
    private val folderRepository: IFolderRepository,
    private val deleteDocumentUseCase: DeleteDocumentUseCase,
    private val ragConfigPersistence: RagConfigPersistence
) : ViewModel() {

    private val app = application as NexaraApplication

    private val vectorStatsService = VectorStatsService(vectorRepository)

    val folders: StateFlow<List<Folder>> = folderRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _folderStats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderStats: StateFlow<Map<String, Int>> = _folderStats.asStateFlow()

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents: StateFlow<List<Document>> = _documents.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Document>>(emptyList())
    val searchResults: StateFlow<List<Document>> = _searchResults.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexingProgress = MutableStateFlow(0f)
    val indexingProgress: StateFlow<Float> = _indexingProgress.asStateFlow()

    private val _indexingStatus = MutableStateFlow<String?>(null)
    val indexingStatus: StateFlow<String?> = _indexingStatus.asStateFlow()

    private val _indexingSubStatus = MutableStateFlow<String?>(null)
    val indexingSubStatus: StateFlow<String?> = _indexingSubStatus.asStateFlow()

    private val _lastQueueError = MutableStateFlow<String?>(null)
    val lastQueueError: StateFlow<String?> = _lastQueueError.asStateFlow()

    private val _config = MutableStateFlow(RagConfiguration())
    val config: StateFlow<RagConfiguration> = _config.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelItem>>(emptyList())
    val availableModels: StateFlow<List<ModelItem>> = _availableModels.asStateFlow()

    private val prefs = app.getSharedPreferences("rag_settings", 0)
    private val settingsPrefs = app.getSharedPreferences("nexara_settings", 0)

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())

    private val _stats = MutableStateFlow(RagStats())
    val stats: StateFlow<RagStats> = _stats.asStateFlow()

    private val _vectorStats = MutableStateFlow<VectorStats?>(null)
    val vectorStats: StateFlow<VectorStats?> = _vectorStats.asStateFlow()

    init {
        loadConfig()
        loadAvailableModels()
        loadStats()
        startDataObservation()
        observeQueue()
    }

    private fun observeQueue() {
        app.vectorizationQueue.setOnStateChange { queue, currentTask ->
            val isProcessing = app.vectorizationQueue.getState().isProcessing
            _isIndexing.value = isProcessing
            _indexingProgress.value = (currentTask?.progress ?: 0.0).toFloat() / 100f

            val statusText = currentTask?.let { task ->
                when (task.status) {
                    "pending" -> "等待队列中..."
                    "chunking" -> "正在对文档进行语义切块..."
                    "vectorizing" -> "正在发送切块至模型处理..."
                    "saving" -> "正在接受并持久化向量数据..."
                    "extracting" -> "正在构建知识图谱节点..."
                    "failed" -> "失败: ${task.error?.take(15) ?: "未知错误"}"
                    "completed" -> "任务已完成"
                    "warning" -> "完成 (存在部分提取警告)"
                    else -> task.status
                }
            }
            _indexingStatus.value = statusText
            _indexingSubStatus.value = currentTask?.subStatus

            // 任务失败时持久化错误信息，防止进度条消失后用户看不到失败原因
            if (currentTask?.status == "failed") {
                _lastQueueError.value = currentTask.error ?: "向量化失败，请检查 Embedding 模型配置"
                _isIndexing.value = true  // 保持可见
            }
            // 新任务开始或完成时清除错误
            if (currentTask != null && currentTask.status != "failed" && currentTask.status != "warning") {
                _lastQueueError.value = null
            }

            if (queue.isEmpty() && currentTask == null) {
                refreshStats()
                // 延迟关闭进度条，让用户有时间看到最后的状态
                if (_lastQueueError.value == null) {
                    _isIndexing.value = false
                }
            }
        }
    }

    private fun startDataObservation() {
        viewModelScope.launch {
            documentRepository.observeAll().collect { list ->
                _documents.value = list
            }
        }
        
        viewModelScope.launch {
            folderRepository.observeAll().collect { list ->
                _folders.value = list
                updateFolderStats(list)
            }
        }
    }

    private fun refreshStats() {
        viewModelScope.launch {
            loadStats()
            updateFolderStats(_folders.value)
        }
    }

    private fun updateFolderStats(folderList: List<Folder>) {
        viewModelScope.launch {
            val stats = mutableMapOf<String, Int>()
            for (folder in folderList) {
                stats[folder.id] = documentRepository.countByFolderId(folder.id)
            }
            _folderStats.value = stats
        }
    }

    private fun loadConfig() {
        val rag = ragConfigPersistence.loadRagConfig()
        val retrieval = ragConfigPersistence.loadRetrievalConfig()
        _config.value = RagConfiguration(
            enableMemory = retrieval.enableMemory,
            enableDocs = retrieval.enableDocs,
            enableKnowledgeGraph = retrieval.enableKnowledgeGraph,
            enableQueryRewrite = retrieval.enableQueryRewrite,
            enableHybridSearch = retrieval.enableHybridSearch,
            enableRerank = retrieval.enableRerank,
            enableIncrementalHash = prefs.getBoolean(RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH, true),
            enableLocalPreprocess = prefs.getBoolean(RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS, false),
            queryRewriteStrategy = retrieval.queryRewriteStrategy,
            queryRewriteModel = retrieval.queryRewriteModel,
            queryRewriteCount = retrieval.queryRewriteCount,
            memoryLimit = retrieval.memoryLimit,
            memoryThreshold = retrieval.memoryThreshold,
            docLimit = retrieval.docLimit,
            docThreshold = retrieval.docThreshold,
            docChunkSize = rag.docChunkSize,
            chunkOverlap = rag.chunkOverlap,
            memoryChunkSize = rag.memoryChunkSize,
            rerankTopK = retrieval.rerankTopK,
            rerankFinalK = retrieval.rerankFinalK,
            hybridAlpha = retrieval.hybridAlpha,
            hybridBM25Boost = retrieval.hybridBM25Boost,
            kgExtractionModel = retrieval.kgExtractionModel,
            kgExtractionPrompt = retrieval.kgExtractionPrompt,
            kgFreeMode = retrieval.kgFreeMode,
            kgDomainAuto = retrieval.kgDomainAuto,
            costStrategy = prefs.getString(RagConfigPersistence.KEY_COST_STRATEGY, "on-demand") ?: "on-demand",
            jitMaxChunks = retrieval.jitMaxChunks,
            showRetrievalProgress = prefs.getBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_PROGRESS, true),
            showRetrievalDetails = prefs.getBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_DETAILS, true),
            trackRetrievalMetrics = prefs.getBoolean(RagConfigPersistence.KEY_TRACK_RETRIEVAL_METRICS, false),
            contextWindow = rag.contextWindow,
            summaryThreshold = rag.summaryThreshold
        )
    }

    private fun saveConfig(config: RagConfiguration) {
        ragConfigPersistence.saveRagConfig(
            com.promenar.nexara.data.agent.AgentRagConfig(
                docChunkSize = config.docChunkSize,
                chunkOverlap = config.chunkOverlap,
                memoryChunkSize = config.memoryChunkSize,
                contextWindow = config.contextWindow,
                summaryThreshold = config.summaryThreshold
            )
        )
        ragConfigPersistence.saveRetrievalConfig(
            com.promenar.nexara.data.agent.AgentRetrievalConfig(
                memoryLimit = config.memoryLimit,
                memoryThreshold = config.memoryThreshold,
                docLimit = config.docLimit,
                docThreshold = config.docThreshold,
                enableRerank = config.enableRerank,
                rerankTopK = config.rerankTopK,
                rerankFinalK = config.rerankFinalK,
                enableQueryRewrite = config.enableQueryRewrite,
                queryRewriteStrategy = config.queryRewriteStrategy,
                queryRewriteCount = config.queryRewriteCount,
                enableHybridSearch = config.enableHybridSearch,
                hybridAlpha = config.hybridAlpha,
                hybridBM25Boost = config.hybridBM25Boost,
                enableMemory = config.enableMemory,
                enableDocs = config.enableDocs,
                enableKnowledgeGraph = config.enableKnowledgeGraph,
                queryRewriteModel = config.queryRewriteModel,
                kgExtractionModel = config.kgExtractionModel,
                kgExtractionPrompt = config.kgExtractionPrompt,
                kgFreeMode = config.kgFreeMode,
                kgDomainAuto = config.kgDomainAuto,
                jitMaxChunks = config.jitMaxChunks
            )
        )
        prefs.edit()
            .putBoolean(RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH, config.enableIncrementalHash)
            .putBoolean(RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS, config.enableLocalPreprocess)
            .putString(RagConfigPersistence.KEY_COST_STRATEGY, config.costStrategy)
            .putBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_PROGRESS, config.showRetrievalProgress)
            .putBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_DETAILS, config.showRetrievalDetails)
            .putBoolean(RagConfigPersistence.KEY_TRACK_RETRIEVAL_METRICS, config.trackRetrievalMetrics)
            .apply()
    }

    private fun loadAvailableModels() {
        val allIds = settingsPrefs.getStringSet("all_models", null) ?: emptySet()
        val models = allIds.map { id ->
            val name = settingsPrefs.getString("model_info_${id}_name", id) ?: id
            val type = settingsPrefs.getString("model_info_${id}_type", "chat") ?: "chat"
            val provider = settingsPrefs.getString("model_info_${id}_provider", "Cloud") ?: "Cloud"
            val contextLength = settingsPrefs.getInt("model_info_${id}_context", 8192)
            val caps = settingsPrefs.getStringSet("model_info_${id}_caps", emptySet()) ?: emptySet()
            
            ModelItem(
                id = id,
                name = name,
                providerName = provider,
                contextLength = contextLength,
                capabilities = buildList {
                    when (type) {
                        "chat" -> add(ModelCapability.CHAT)
                        "reasoning" -> add(ModelCapability.REASONING)
                        "vision" -> add(ModelCapability.VISION)
                        "internet" -> add(ModelCapability.WEB)
                        "embedding" -> add(ModelCapability.EMBEDDING)
                        "rerank" -> add(ModelCapability.RERANK)
                        "image" -> add(ModelCapability.IMAGE)
                    }
                    caps.forEach { capStr ->
                        try { add(ModelCapability.valueOf(capStr.uppercase())) } catch (_: Exception) {}
                    }
                    if (contains(ModelCapability.REASONING) && !contains(ModelCapability.CHAT)) {
                        add(ModelCapability.CHAT)
                    }
                }.distinct()
            )
        }.sortedBy { it.name }
        _availableModels.value = models
    }

    fun loadCollections() {
        viewModelScope.launch {
            loadStats()
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
                val docCount = documentRepository.getCount()
                val nodeCount = kgRepository.getNodeCount()
                val vStats = vectorStatsService.getStats()
                _vectorStats.value = vStats
                _stats.value = RagStats(
                    documentCount = docCount,
                    memoryCount = vStats.byType.memory,
                    graphEntityCount = nodeCount
                )
            } catch (_: Exception) { }
        }
    }


    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                val folder = Folder(
                    id = IdGenerator.uuid(),
                    name = name,
                    createdAt = System.currentTimeMillis()
                )
                folderRepository.create(folder)
            } catch (_: Exception) { }
        }
    }

    fun loadDocumentsForFolder(folderId: String) {
        viewModelScope.launch {
            try {
                _documents.value = documentRepository.getByFolderId(folderId)
            } catch (_: Exception) { }
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            try {
                val folder = folderRepository.getById(id) ?: return@launch
                folderRepository.delete(folder)
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun deleteDocuments(ids: List<String>) {
        viewModelScope.launch {
            try {
                deleteDocumentUseCase(ids)
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun importDocuments(uris: List<android.net.Uri>, folderId: String? = null) {
        viewModelScope.launch {
            app.documentImporter.importFromUris(uris, folderId)
        }
    }

    /**
     * 手动触发文档的知识图谱抽取。
     * 复用 VectorizationQueue 的 KG 抽取管线（分块→GraphExtractor→GraphStore）。
     * @param kgStrategy "full" 全量抽取 / "summary-first" 摘要优先（仅采样首中尾三块）
     */
    fun extractKnowledgeGraph(docId: String, kgStrategy: String) {
        viewModelScope.launch {
            try {
                val doc = documentRepository.getById(docId) ?: return@launch
                val content = doc.content ?: return@launch
                app.vectorizationQueue.enqueueDocument(
                    docId = docId,
                    docTitle = doc.title,
                    content = content,
                    kgStrategy = kgStrategy,
                    skipVectorization = true  // 向量已存在，仅做 KG 抽取
                )
            } catch (e: Exception) {
                _indexingSubStatus.value = "KG extraction failed: ${e.message?.take(60)}"
            }
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
                    it.title.contains(query, ignoreCase = true)
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
                    val app = application as NexaraApplication
                    val documentRepo = com.promenar.nexara.data.repository.DocumentRepository(
                        documentDao = app.database.documentDao(),
                        folderDao = app.database.folderDao()
                    )
                    val vectorRepo = com.promenar.nexara.data.repository.VectorRepository(
                        vectorDao = app.database.vectorDao(),
                        embeddingClient = app.embeddingClient
                    )
                    val ragPrefs = app.getSharedPreferences("rag_settings", 0)
                    return RagViewModel(
                        application = application,
                        documentRepository = documentRepo,
                        vectorRepository = vectorRepo,
                        kgRepository = com.promenar.nexara.data.repository.KnowledgeGraphRepository(
                            kgNodeDao = app.database.kgNodeDao(),
                            kgEdgeDao = app.database.kgEdgeDao(),
                            graphExtractor = app.graphExtractor,
                            documentDao = app.database.documentDao()
                        ),
                        folderRepository = com.promenar.nexara.data.repository.FolderRepository(
                            folderDao = app.database.folderDao()
                        ),
                        deleteDocumentUseCase = DeleteDocumentUseCase(documentRepo, vectorRepo),
                        ragConfigPersistence = RagConfigPersistence(ragPrefs)
                    ) as T
                }
            }
    }
}
