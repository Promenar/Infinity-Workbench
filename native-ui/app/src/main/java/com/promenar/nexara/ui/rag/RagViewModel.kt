package com.promenar.nexara.ui.rag

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.ShareRequest
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.VectorStats
import com.promenar.nexara.data.rag.VectorStatsService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.utils.NexaraLogger
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.RenameResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.promenar.nexara.ui.common.ModelItem
import com.promenar.nexara.ui.common.ModelCapability
import com.promenar.nexara.ui.common.KgStatus
import com.promenar.nexara.ui.common.status.NoticeSeverity
import com.promenar.nexara.ui.common.status.UiStatusNotice
import com.promenar.nexara.share.core.AndroidSafContentSource
import com.promenar.nexara.share.core.AndroidSafImportRequestFactory
import com.promenar.nexara.share.core.AndroidShareIndexScheduler
import com.promenar.nexara.share.core.ShareImportItem
import com.promenar.nexara.share.core.SharedFileImporter
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext

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

data class RagSearchResult(
    val document: Document,
    val snippet: String? = null
)

class RagViewModel(
    application: Application,
    private val workspaceRepository: IWorkspaceRepository,
    private val vectorRepository: IVectorRepository,
    private val kgRepository: IKnowledgeGraphRepository,
    private val fileOperationRepository: IFileOperationRepository,
    private val ragConfigPersistence: RagConfigPersistence,
    private val keywordSearcher: KeywordSearcher,
    injectedImporter: SharedFileImporter? = null,
    injectedRequestFactory: ((Uri, String) -> ShareRequest)? = null,
    ragWorkspaceIoContext: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {

    private val app = application as NexaraApplication

    private val sharedFileImporter = injectedImporter ?: SharedFileImporter(
        source = AndroidSafContentSource(app.contentResolver),
        workspace = workspaceRepository,
        indexScheduler = AndroidShareIndexScheduler(app),
    )
    private val importRequestFactory = injectedRequestFactory
        ?: AndroidSafImportRequestFactory(app.contentResolver)::create
    private val ragWorkspaceIoContext = ragWorkspaceIoContext

    private val vectorStatsService = VectorStatsService(vectorRepository)

    /** 当前工作区根目录的 FileEntry UUID（首个根目录，用于 FilesPanel） */
    private val _workspaceRootUuid = MutableStateFlow<String?>(null)
    val workspaceRootUuid: StateFlow<String?> = _workspaceRootUuid.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _folderStats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderStats: StateFlow<Map<String, Int>> = _folderStats.asStateFlow()

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents: StateFlow<List<Document>> = _documents.asStateFlow()

    private val _searchResults = MutableStateFlow<List<RagSearchResult>>(emptyList())
    val searchResults: StateFlow<List<RagSearchResult>> = _searchResults.asStateFlow()

    private val _memoryVectors = MutableStateFlow<List<MemoryVectorRecord>>(emptyList())
    val memoryVectors: StateFlow<List<MemoryVectorRecord>> = _memoryVectors.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexingProgress = MutableStateFlow(0f)
    val indexingProgress: StateFlow<Float> = _indexingProgress.asStateFlow()

    private val _indexingNotice = MutableStateFlow<UiStatusNotice?>(null)
    val indexingNotice: StateFlow<UiStatusNotice?> = _indexingNotice.asStateFlow()

    private val _canRetryLastFailedIndex = MutableStateFlow(false)
    val canRetryLastFailedIndex: StateFlow<Boolean> = _canRetryLastFailedIndex.asStateFlow()

    private val _isRetryingLastFailedIndex = MutableStateFlow(false)
    val isRetryingLastFailedIndex: StateFlow<Boolean> = _isRetryingLastFailedIndex.asStateFlow()

    private val _isMovingDocuments = MutableStateFlow(false)
    val isMovingDocuments: StateFlow<Boolean> = _isMovingDocuments.asStateFlow()

    private val _isDeletingDocuments = MutableStateFlow(false)
    val isDeletingDocuments: StateFlow<Boolean> = _isDeletingDocuments.asStateFlow()

    /** 当前正在索引中的文件 UUID 集合 */
    private val _indexingDocIds = MutableStateFlow<Set<String>>(emptySet())
    val indexingDocIds: StateFlow<Set<String>> = _indexingDocIds.asStateFlow()

    private val _kgExtractionStates = MutableStateFlow<Map<String, KgStatus>>(emptyMap())
    val kgExtractionStates: StateFlow<Map<String, KgStatus>> = _kgExtractionStates.asStateFlow()

    private val _kgExtractingIds = mutableSetOf<String>()

    private val _config = MutableStateFlow(RagConfiguration())
    val config: StateFlow<RagConfiguration> = _config.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelItem>>(emptyList())
    val availableModels: StateFlow<List<ModelItem>> = _availableModels.asStateFlow()

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
        startDataObservation()
        observeQueue()
        ensureRagWorkspaceRoot()
    }

    /** 为全局知识库建立不可见的系统 Session owner，再通过统一 repository 认领真实 root。 */
    private fun ensureRagWorkspaceRoot() {
        viewModelScope.launch {
            try {
                _workspaceRootUuid.value = RagWorkspaceProvisioner(
                    filesDir = app.filesDir,
                    database = app.database,
                    workspaceRepository = workspaceRepository,
                    ioContext = ragWorkspaceIoContext,
                ).ensureRoot().uuid
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                NexaraLogger.logError("[RagViewModel] ensureRagWorkspaceRoot failed", failure)
                _indexingNotice.value = UiStatusNotice(
                    severity = NoticeSeverity.Error,
                    code = IndexingNotice.CODE_FAILED,
                    technical = failure::class.simpleName,
                )
            }
        }
    }

    private fun observeQueue() {
        app.vectorizationQueue.setOnStateChange { queue, currentTask ->
            val isProcessing = app.vectorizationQueue.state.value.isProcessing
            _isIndexing.value = isProcessing
            // 使用 currentTask 的进度；若队列空但有残留错误，保持上次进度
            _indexingProgress.value = (currentTask?.progress ?: 0.0).toFloat() / 100f

            // 更新正在索引中的文件 UUID 集合
            _indexingDocIds.value = queue.filter {
                it.type in setOf("document", com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE) &&
                    it.docId != null
            }
                .mapNotNull { it.docId }.toSet()

            val queueNotice = currentTask?.let(IndexingNotice::fromTask)
            if (queueNotice != null) {
                _indexingNotice.value = queueNotice
            } else if (!shouldKeepNotice(_indexingNotice.value)) {
                _indexingNotice.value = null
            }

            val task = currentTask
            if (task != null && task.status in setOf("failed", "partial")) {
                lastFailedIndexTarget = task.toRetryTarget()
                _canRetryLastFailedIndex.value = lastFailedIndexTarget != null
                _isIndexing.value = true  // 保持错误卡片可见
                _indexingProgress.value = (task.progress / 100.0).toFloat()
            } else if (task != null) {
                clearRetryTarget()
            }

            // 队列空 & 无当前任务 & 无错误留存 → 停止展示
            if (queue.isEmpty() && currentTask == null) {
                refreshStats()
                if (!shouldKeepNotice(_indexingNotice.value)) {
                    _isIndexing.value = false
                    _indexingNotice.value = null
                }
                if (shouldKeepNotice(_indexingNotice.value)) {
                    _isIndexing.value = true
                }
            }

            // KG extraction state tracking
            val kgDocId = currentTask?.docId
            if (kgDocId != null && kgDocId in _kgExtractingIds) {
                when (currentTask!!.status) {
                    "completed", "warning" -> {
                        _kgExtractingIds.remove(kgDocId)
                        _kgExtractionStates.value = _kgExtractionStates.value + (kgDocId to KgStatus.COMPLETED)
                    }
                    "failed" -> {
                        _kgExtractingIds.remove(kgDocId)
                        _kgExtractionStates.value = _kgExtractionStates.value + (kgDocId to KgStatus.FAILED)
                    }
                }
            }
        }
    }

    private sealed interface FailedIndexTarget {
        val workspaceRootUuid: String
        val docId: String

        data class Document(
            override val workspaceRootUuid: String,
            override val docId: String,
        ) : FailedIndexTarget

        data class DocumentReference(
            override val workspaceRootUuid: String,
            override val docId: String,
        ) : FailedIndexTarget
    }

    private var lastFailedIndexTarget: FailedIndexTarget? = null

    private fun com.promenar.nexara.data.rag.VectorizationTask.toRetryTarget(): FailedIndexTarget? {
        val root = workspaceRootUuid ?: return null
        val documentId = docId ?: return null
        return when (type) {
            "document" -> FailedIndexTarget.Document(root, documentId)
            com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE ->
                FailedIndexTarget.DocumentReference(root, documentId)
            else -> null
        }
    }

    fun retryLastFailedIndex() {
        if (_isRetryingLastFailedIndex.value) return
        val target = lastFailedIndexTarget ?: return
        _isRetryingLastFailedIndex.value = true
        viewModelScope.launch {
            try {
                val submitted = when (target) {
                    is FailedIndexTarget.Document -> retryDocument(target)
                    is FailedIndexTarget.DocumentReference -> app.vectorizationQueue.retryDocumentReference(
                        target.workspaceRootUuid,
                        target.docId,
                    )
                }
                if (submitted) {
                    clearRetryTarget()
                    _indexingNotice.value = null
                    _isIndexing.value = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _indexingNotice.value = UiStatusNotice(
                    severity = NoticeSeverity.Error,
                    code = IndexingNotice.CODE_FAILED,
                    technical = failure.message?.take(160),
                )
            } finally {
                _isRetryingLastFailedIndex.value = false
            }
        }
    }

    private suspend fun retryDocument(target: FailedIndexTarget.Document): Boolean {
        val entry = workspaceRepository.getByUuid(target.workspaceRootUuid, target.docId) ?: return false
        val content = fileOperationRepository.readFileRange(target.workspaceRootUuid, target.docId).content
        if (content.isBlank()) return false
        app.vectorizationQueue.cancel(target.docId)
        app.vectorizationQueue.enqueueDocument(
            workspaceRootUuid = target.workspaceRootUuid,
            docId = target.docId,
            docTitle = entry.name,
            content = content,
            kgStrategy = if (_config.value.enableKnowledgeGraph) "full" else null,
        )
        return true
    }

    private fun clearRetryTarget() {
        lastFailedIndexTarget = null
        _canRetryLastFailedIndex.value = false
    }

    private fun discardFailedIndexTarget() {
        val discardedTarget = lastFailedIndexTarget
        clearRetryTarget()
        discardedTarget?.let { app.vectorizationQueue.cancel(it.docId) }
    }

    /** 手动关闭错误提示（用户点击关闭后清除） */
    fun dismissQueueError() {
        if (_isRetryingLastFailedIndex.value) return
        discardFailedIndexTarget()
        _indexingNotice.value = null
        _isIndexing.value = false
    }

    private fun shouldKeepNotice(notice: UiStatusNotice?): Boolean = when (notice?.code) {
        IndexingNotice.CODE_FAILED,
        IndexingNotice.CODE_PARTIAL,
        IndexingNotice.CODE_WARNING,
        IndexingNotice.CODE_IMPORT_FAILED,
        IndexingNotice.CODE_MOVE_FAILED,
        IndexingNotice.CODE_DELETE_FAILED -> true
        else -> false
    }

    private fun startDataObservation() {
        viewModelScope.launch {
            workspaceRootUuid.filterNotNull().flatMapLatest { root ->
                workspaceRepository.observeChildren(root, root)
            }.collect { roots ->
                val allFiles = roots.filter { !it.isDirectory }
                _documents.value = allFiles.map { it.toDocument() }
            }
        }

        viewModelScope.launch {
            workspaceRootUuid.filterNotNull().flatMapLatest { root ->
                workspaceRepository.observeChildren(root, root)
            }.collect { entries ->
                val dirs = entries.filter { it.isDirectory }
                _folders.value = dirs.map { it.toFolder() }
                updateFolderStats(_folders.value)
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
                val root = _workspaceRootUuid.value ?: continue
                val children = workspaceRepository.observeChildren(root, folder.id).first()
                stats[folder.id] = children.size
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
            kgExtractionTimeoutSeconds = retrieval.kgExtractionTimeoutSeconds,
            costStrategy = prefs.getString(RagConfigPersistence.KEY_COST_STRATEGY, "on-demand") ?: "on-demand",
            jitMaxChunks = retrieval.jitMaxChunks,
            showRetrievalProgress = prefs.getBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_PROGRESS, true),
            showRetrievalDetails = prefs.getBoolean(RagConfigPersistence.KEY_SHOW_RETRIEVAL_DETAILS, true),
            trackRetrievalMetrics = prefs.getBoolean(RagConfigPersistence.KEY_TRACK_RETRIEVAL_METRICS, false),
            summaryTemplate = rag.summaryTemplate.ifEmpty {
                RagConfiguration().summaryTemplate
            },
            currentPreset = rag.currentPreset,
            embedDimension = prefs.getInt("embed_dimension", -1).takeIf { it > 0 },
            maxEmbedTokensPerCall = prefs.getInt("max_embed_tokens_per_call", 8192),
            rerankMaxPerCall = prefs.getInt("rerank_max_per_call", 100)
        )
    }

    private fun saveConfig(config: RagConfiguration) {
        ragConfigPersistence.saveRagConfig(
            com.promenar.nexara.data.agent.AgentRagConfig(
                docChunkSize = config.docChunkSize,
                chunkOverlap = config.chunkOverlap,
                memoryChunkSize = config.memoryChunkSize,
                summaryTemplate = config.summaryTemplate,
                currentPreset = config.currentPreset
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
                kgExtractionTimeoutSeconds = config.kgExtractionTimeoutSeconds,
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
            .putInt("embed_dimension", config.embedDimension ?: -1)
            .putInt("max_embed_tokens_per_call", config.maxEmbedTokensPerCall)
            .putInt("rerank_max_per_call", config.rerankMaxPerCall)
            .apply()

        // P0: 用户修改配置后立即重建 MemoryManager，使新参数即时生效
        app.rebuildMemoryManager()
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
                        "internet" -> add(ModelCapability.INTERNET)
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

    /** 暴露工作区仓库供 UI 层 FilesPanel 使用 */
    fun getWorkspaceRepo(): IWorkspaceRepository = workspaceRepository

    fun loadCollections() {
        viewModelScope.launch {
            loadStats()
        }
    }

    private fun loadVectorStats() {
        viewModelScope.launch {
            try {
                _vectorStats.value = vectorStatsService.getStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val docCount = _documents.value.size
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
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val uuid = java.util.UUID.randomUUID().toString()
                val matPath = "/$name"
                workspaceRepository.createDirectoryInWorkspace(
                    workspaceRootUuid = rootUuid,
                    uuid = uuid,
                    name = name,
                    parentUuid = rootUuid,
                    materializedPath = matPath
                )
            } catch (_: Exception) { }
        }
    }

    fun loadDocumentsForFolder(folderId: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                workspaceRepository.observeChildren(rootUuid, folderId).collect { entries ->
                    _documents.value = entries.filter { !it.isDirectory }.map { it.toDocument() }
                }
            } catch (_: Exception) { }
        }
    }

    fun deleteCollection(id: String, onResult: ((Boolean) -> Unit)? = null) =
        deleteDocuments(listOf(id)) { succeeded, _ -> onResult?.invoke(succeeded) }

    fun renameFolder(id: String, newName: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                when (workspaceRepository.rename(rootUuid, id, newName)) {
                    is RenameResult.Success -> loadStats()
                    is RenameResult.Conflict,
                    RenameResult.NotFound,
                    -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { }
        }
    }

    fun deleteFolder(id: String) = deleteCollection(id)

    fun deleteDocuments(
        ids: Collection<String>,
        onComplete: ((Boolean, List<String>) -> Unit)? = null,
    ) {
        val attemptedIds = ids.distinct()
        if (attemptedIds.isEmpty()) {
            onComplete?.invoke(false, emptyList())
            return
        }
        if (_isDeletingDocuments.value) {
            onComplete?.invoke(false, attemptedIds)
            return
        }
        _isDeletingDocuments.value = true
        discardFailedIndexTarget()
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            val technicalFailures = mutableListOf<String>()
            try {
                val rootUuid = _workspaceRootUuid.value
                if (rootUuid == null) {
                    failed += attemptedIds
                } else {
                    attemptedIds.forEach { id ->
                        try {
                            val entry = workspaceRepository.getByUuid(rootUuid, id)
                            val entriesToClean = if (entry?.isDirectory == true) {
                                workspaceRepository.getSubtree(rootUuid, entry.materializedPath)
                            } else {
                                listOfNotNull(entry)
                            }
                            workspaceRepository.permanentDelete(rootUuid, id)
                            val runtimeIds = entriesToClean.filterNot { it.isDirectory }.map { it.uuid }
                                .ifEmpty { if (entry?.isDirectory == true) emptyList() else listOf(id) }
                            runtimeIds.forEach(::clearDocumentRuntimeState)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            failed += id
                            technicalFailures += "$id:${failure::class.simpleName}"
                        }
                    }
                }

                _indexingNotice.value = when {
                    failed.isEmpty() -> null
                    else -> UiStatusNotice(
                        severity = if (failed.size == attemptedIds.size) NoticeSeverity.Error else NoticeSeverity.Warning,
                        code = IndexingNotice.CODE_DELETE_FAILED,
                        technical = technicalFailures.joinToString(",").take(160).takeIf { it.isNotBlank() },
                    )
                }
                if (failed.size < attemptedIds.size) loadStats()
                onComplete?.invoke(failed.isEmpty(), failed.toList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _isDeletingDocuments.value = false
            }
        }
    }

    fun importDocuments(uris: List<Uri>, folderId: String? = null) {
        if (uris.isEmpty()) return
        discardFailedIndexTarget()
        viewModelScope.launch {
            val rootUuid = _workspaceRootUuid.value ?: return@launch
            val parentUuid = folderId ?: rootUuid
            val imported = mutableListOf<ShareImportItem>()
            val failures = mutableListOf<String>()
            _indexingNotice.value = null
            _isIndexing.value = app.vectorizationQueue.state.value.isProcessing

            for (uri in uris.distinct()) {
                try {
                    val request = importRequestFactory(uri, rootUuid)
                    val result = sharedFileImporter.import(
                        request = request,
                        workspaceRootUuid = rootUuid,
                        parentUuid = parentUuid,
                    )
                    imported += result.created
                    failures += result.rejected.map { item ->
                        "${item.displayName} (${item.reason?.name ?: "Rejected"})"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures += "${uri.lastPathSegment ?: "未知文件"} - ${failure.message?.take(80) ?: "未知错误"}"
                }
            }

            if (failures.isNotEmpty()) {
                _indexingNotice.value = IndexingNotice.importFailed(
                    technical = failures.joinToString("；").take(240)
                )
                _isIndexing.value = true
            } else {
                _isIndexing.value = app.vectorizationQueue.state.value.isProcessing
            }
            if (imported.isNotEmpty()) {
                loadStats()
            }
        }
    }

    /** 手动触发重新索引 */
    fun reindexFile(uuid: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val entry = workspaceRepository.getByUuid(rootUuid, uuid) ?: return@launch
                val result = fileOperationRepository.readFileRange(rootUuid, uuid)
                if (result.content.isNotBlank()) {
                    val kgStrategy = if (_config.value.enableKnowledgeGraph) "full" else null
                    app.vectorizationQueue.enqueueDocument(
                        workspaceRootUuid = rootUuid,
                        docId = uuid,
                        docTitle = entry.name,
                        content = result.content,
                        kgStrategy = kgStrategy
                    )
                }
            } catch (_: Exception) { }
        }
    }

    /** 批量重新索引 */
    fun reindexDocuments(uuids: Collection<String>) {
        if (uuids.isEmpty()) {
            _indexingNotice.value = UiStatusNotice(
                severity = NoticeSeverity.Warning,
                code = IndexingNotice.CODE_WARNING,
            )
            return
        }
        discardFailedIndexTarget()
        viewModelScope.launch {
            val rootUuid = _workspaceRootUuid.value ?: return@launch
            var submitted = 0
            var failed = 0
            uuids.forEach { uuid ->
                try {
                    val entry = workspaceRepository.getByUuid(rootUuid, uuid) ?: return@forEach
                    val result = fileOperationRepository.readFileRange(rootUuid, uuid)
                    if (result.content.isNotBlank()) {
                        submitted += 1
                        app.vectorizationQueue.enqueueDocument(
                            workspaceRootUuid = rootUuid,
                            docId = uuid,
                            docTitle = entry.name,
                            content = result.content,
                            kgStrategy = if (_config.value.enableKnowledgeGraph) "full" else null
                        )
                    } else {
                        failed += 1
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    NexaraLogger.logError("[RagViewModel] reindexDocuments failed: uuid=$uuid", e)
                    failed += 1
                }
            }
            _indexingNotice.value = when {
                failed == 0 -> null
                submitted == 0 -> UiStatusNotice(NoticeSeverity.Error, IndexingNotice.CODE_FAILED)
                else -> UiStatusNotice(NoticeSeverity.Warning, IndexingNotice.CODE_WARNING)
            }
        }
    }

    /** 移动文件到指定目录 */
    fun moveFile(uuid: String, targetParentUuid: String, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                moveFileInternal(rootUuid, uuid, targetParentUuid)
                onResult?.invoke(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onResult?.invoke(false)
            }
        }
    }

    fun moveDocuments(
        uuids: Collection<String>,
        targetParentUuid: String,
        onComplete: ((Boolean, List<String>) -> Unit)? = null,
    ) {
        if (uuids.isEmpty()) {
            onComplete?.invoke(false, emptyList())
            _indexingNotice.value = UiStatusNotice(
                severity = NoticeSeverity.Warning,
                code = IndexingNotice.CODE_WARNING,
            )
            return
        }
        if (_isMovingDocuments.value) {
            onComplete?.invoke(false, uuids.distinct())
            return
        }
        _isMovingDocuments.value = true
        discardFailedIndexTarget()
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value
                if (rootUuid == null) {
                    _indexingNotice.value = UiStatusNotice(
                        severity = NoticeSeverity.Error,
                        code = IndexingNotice.CODE_MOVE_FAILED,
                    )
                    onComplete?.invoke(false, uuids.toList())
                    return@launch
                }
                val failed = mutableListOf<String>()
                val technicalFailures = mutableListOf<String>()
                var moved = 0
                uuids.distinct().forEach { uuid ->
                    try {
                        moveFileInternal(rootUuid, uuid, targetParentUuid)
                        moved += 1
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failed += uuid
                        technicalFailures += "$uuid:${failure::class.simpleName}"
                    }
                }

                val success = moved > 0 && failed.isEmpty()
                _indexingNotice.value = when {
                    failed.isEmpty() -> null
                    moved == 0 -> UiStatusNotice(
                        severity = NoticeSeverity.Error,
                        code = IndexingNotice.CODE_MOVE_FAILED,
                        technical = technicalFailures.joinToString(",").take(160),
                    )
                    else -> UiStatusNotice(
                        severity = NoticeSeverity.Warning,
                        code = IndexingNotice.CODE_MOVE_FAILED,
                        technical = technicalFailures.joinToString(",").take(160),
                    )
                }
                if (failed.isEmpty()) {
                    _isIndexing.value = app.vectorizationQueue.state.value.isProcessing
                }
                if (moved > 0) {
                    loadStats()
                }
                onComplete?.invoke(success, failed.toList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _isMovingDocuments.value = false
            }
        }
    }

    private suspend fun moveFileInternal(rootUuid: String, uuid: String, targetParentUuid: String) {
        val newParent = targetParentUuid.ifEmpty { rootUuid }
        workspaceRepository.updateParent(rootUuid, uuid, newParent)
    }

    /** 触发单个文件的知识图谱抽取 — 直接调用 GraphExtractor，独立于向量化管线 */
    fun extractKG(uuid: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val entry = workspaceRepository.getByUuid(rootUuid, uuid) ?: return@launch
                val content = fileOperationRepository.readFileRange(rootUuid, uuid).content
                if (content.isNotBlank()) {
                    _kgExtractionStates.value = _kgExtractionStates.value + (uuid to KgStatus.IN_PROGRESS)
                    NexaraLogger.log("[RagViewModel] extractKG start: uuid=$uuid name=${entry.name} contentLen=${content.length}")
                    val result = app.graphExtractor.extractAndSave(content, uuid)
                    if (result.error != null) {
                        NexaraLogger.log("[RagViewModel] extractKG error: uuid=$uuid error=${result.error}")
                        _kgExtractionStates.value = _kgExtractionStates.value + (uuid to KgStatus.FAILED)
                    } else if (result.nodes.isEmpty() && result.edges.isEmpty()) {
                        NexaraLogger.log("[RagViewModel] extractKG empty: uuid=$uuid — LLM returned no entities/edges")
                        _kgExtractionStates.value = _kgExtractionStates.value + (uuid to KgStatus.FAILED)
                    } else {
                        NexaraLogger.log("[RagViewModel] extractKG success: uuid=$uuid nodes=${result.nodes.size} edges=${result.edges.size}")
                        _kgExtractionStates.value = _kgExtractionStates.value + (uuid to KgStatus.COMPLETED)
                    }
                }
            } catch (e: Exception) {
                NexaraLogger.logError("[RagViewModel] extractKG exception: uuid=$uuid", e)
                _kgExtractionStates.value = _kgExtractionStates.value + (uuid to KgStatus.FAILED)
            }
        }
    }

    /** 复制文件 */
    fun copyFile(uuid: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val entry = workspaceRepository.getByUuid(rootUuid, uuid) ?: return@launch
                val content = fileOperationRepository.readFileRange(rootUuid, uuid).content
                val newUuid = java.util.UUID.randomUUID().toString()
                val newName = "${entry.name.substringBeforeLast('.')} - 副本.${entry.name.substringAfterLast('.', "")}"
                val parentUuid = entry.parentUuid ?: rootUuid
                val parentPath = workspaceRepository.getByUuid(rootUuid, parentUuid)?.materializedPath ?: return@launch
                workspaceRepository.createFileInWorkspace(
                    workspaceRootUuid = rootUuid,
                    uuid = newUuid,
                    name = newName,
                    content = content,
                    parentUuid = parentUuid,
                    materializedPath = parentPath.trimEnd('/') + "/$newName"
                )
            } catch (_: Exception) { }
        }
    }

    fun extractKnowledgeGraph(docId: String, kgStrategy: String) {
        viewModelScope.launch {
            try {
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                val result = fileOperationRepository.readFileRange(rootUuid, docId)
                val content = result.content
                kgRepository.extractFromContent(content, docId)
            } catch (e: Exception) {
                _indexingNotice.value = UiStatusNotice(
                    severity = NoticeSeverity.Warning,
                    code = IndexingNotice.CODE_WARNING,
                    technical = e.message?.take(80),
                )
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
                val titleMatches = _documents.value.filter {
                    it.title.contains(query, ignoreCase = true)
                }
                val ftsResults = keywordSearcher.search(query, limit = 20)
                val ftsDocIds = ftsResults.mapNotNull { it.docId }.toSet()
                val ftsDocs = _documents.value.filter { it.id in ftsDocIds }
                val titleMatchIds = titleMatches.map { it.id }.toSet()
                val ftsOnlyDocs = ftsDocs.filter { it.id !in titleMatchIds }
                val snippets = ftsResults.associate { it.docId to it.content.take(100) }
                val merged = titleMatches.map { RagSearchResult(it, null) } +
                    ftsOnlyDocs.map { RagSearchResult(it, snippets[it.id]) }
                _searchResults.value = merged.distinctBy { it.document.id }
            } catch (_: Exception) {
                _searchResults.value = _documents.value.filter {
                    it.title.contains(query, ignoreCase = true)
                }.map { RagSearchResult(it, null) }
            }
        }
    }

    fun loadMemoryVectors() {
        viewModelScope.launch {
            try {
                _memoryVectors.value = vectorRepository.getMemoryVectors()
            } catch (_: Exception) { }
        }
    }

    fun deleteMemoryVector(id: String) {
        viewModelScope.launch {
            try {
                vectorRepository.deleteVector(id)
                _memoryVectors.value = _memoryVectors.value.filter { it.id != id }
                loadStats()
            } catch (_: Exception) { }
        }
    }

    private fun clearDocumentRuntimeState(docId: String) {
        app.vectorizationQueue.cancel(docId)
        _indexingDocIds.update { ids -> ids - docId }
        _kgExtractionStates.update { states -> states - docId }
        _kgExtractingIds.remove(docId)
    }

    fun clearAllVectors(withGraph: Boolean = false) {
        viewModelScope.launch {
            try {
                vectorRepository.deleteAll()
                if (withGraph) {
                    kgRepository.clear()
                }
                val rootUuid = _workspaceRootUuid.value ?: return@launch
                workspaceRepository.resetAllRAGStatus(rootUuid)
                loadStats()
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
            when (preset.lowercase()) {
                "balanced" -> current.copy(
                    currentPreset = "balanced",
                    docChunkSize = 800,
                    chunkOverlap = 100
                )
                "writing" -> current.copy(
                    currentPreset = "writing",
                    docChunkSize = 1200,
                    chunkOverlap = 200
                )
                "coding" -> current.copy(
                    currentPreset = "coding",
                    docChunkSize = 500,
                    chunkOverlap = 50
                )
                else -> current
            }
        }
        saveConfig(_config.value)
    }

    private fun FileEntry.toFolder() = Folder(
        id = uuid,
        name = name,
        parentId = parentUuid,
        createdAt = createdAt
    )

    private fun FileEntry.toDocument() = Document(
        id = uuid,
        folderId = parentUuid ?: "",
        title = name,
        content = "",
        hash = hash,
        fileSize = sizeBytes,
        vectorized = if (vectorizedAt != null) 2 else 0,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as NexaraApplication
                    val vectorRepo = com.promenar.nexara.data.repository.VectorRepository(
                        vectorDao = app.database.vectorDao(),
                        embeddingClient = app.embeddingClient
                    )
                    val ragPrefs = app.getSharedPreferences("rag_settings", 0)
                    return RagViewModel(
                        application = application,
                        workspaceRepository = app.workspaceRepository,
                        vectorRepository = vectorRepo,
                        kgRepository = com.promenar.nexara.data.repository.KnowledgeGraphRepository(
                            kgNodeDao = app.database.kgNodeDao(),
                            kgEdgeDao = app.database.kgEdgeDao(),
                            graphExtractor = app.graphExtractor
                        ),
                        fileOperationRepository = app.fileOperationRepository,
                        ragConfigPersistence = RagConfigPersistence(ragPrefs),
                        keywordSearcher = app.keywordSearcher
                    ) as T
                }
            }
    }
}
