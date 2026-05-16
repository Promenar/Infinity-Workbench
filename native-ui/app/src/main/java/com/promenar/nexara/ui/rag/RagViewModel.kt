package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.VectorStats
import com.promenar.nexara.data.rag.VectorStatsService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.domain.model.Document
import com.promenar.nexara.domain.model.Folder
import com.promenar.nexara.domain.repository.IKnowledgeGraphRepository
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.MemoryVectorRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val keywordSearcher: KeywordSearcher
) : ViewModel() {

    private val app = application as NexaraApplication

    private val vectorStatsService = VectorStatsService(vectorRepository)

    /** RAG 知识库工作区物理根目录 */
    private val ragWorkspaceRoot: java.io.File by lazy {
        java.io.File(app.filesDir, "rag_workspace").also { it.mkdirs() }
    }

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

    private val _indexingStatus = MutableStateFlow<String?>(null)
    val indexingStatus: StateFlow<String?> = _indexingStatus.asStateFlow()

    private val _indexingSubStatus = MutableStateFlow<String?>(null)
    val indexingSubStatus: StateFlow<String?> = _indexingSubStatus.asStateFlow()

    private val _lastQueueError = MutableStateFlow<String?>(null)
    val lastQueueError: StateFlow<String?> = _lastQueueError.asStateFlow()

    /** 当前正在索引中的文件 UUID 集合 */
    private val _indexingDocIds = MutableStateFlow<Set<String>>(emptySet())
    val indexingDocIds: StateFlow<Set<String>> = _indexingDocIds.asStateFlow()

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

    /** 确保 RAG 工作区在数据库中有一个根目录 FileEntry */
    private fun ensureRagWorkspaceRoot() {
        viewModelScope.launch {
            try {
                val existingRoots = workspaceRepository.observeRoots().first()
                val rootEntry = existingRoots.firstOrNull { it.physicalRootPath == ragWorkspaceRoot.absolutePath }
                if (rootEntry != null) {
                    _workspaceRootUuid.value = rootEntry.uuid
                } else {
                    val uuid = java.util.UUID.randomUUID().toString()
                    workspaceRepository.createDirectory(
                        uuid = uuid,
                        name = "知识库",
                        parentUuid = null,
                        physicalRootPath = ragWorkspaceRoot.absolutePath,
                        materializedPath = "/"
                    )
                    _workspaceRootUuid.value = uuid
                }
            } catch (_: Exception) { }
        }
    }

    private fun observeQueue() {
        app.vectorizationQueue.setOnStateChange { queue, currentTask ->
            val isProcessing = app.vectorizationQueue.getState().isProcessing
            _isIndexing.value = isProcessing
            _indexingProgress.value = (currentTask?.progress ?: 0.0).toFloat() / 100f

            // 更新正在索引中的文件 UUID 集合
            _indexingDocIds.value = queue.filter { it.type == "document" && it.docId != null }
                .mapNotNull { it.docId }.toSet()

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

            if (currentTask?.status == "failed") {
                _lastQueueError.value = currentTask.error ?: "向量化失败，请检查 Embedding 模型配置"
                _isIndexing.value = true
            }
            if (currentTask != null && currentTask.status != "failed" && currentTask.status != "warning") {
                _lastQueueError.value = null
            }

            if (queue.isEmpty() && currentTask == null) {
                refreshStats()
                if (_lastQueueError.value == null) {
                    _isIndexing.value = false
                }
            }
        }
    }

    private fun startDataObservation() {
        viewModelScope.launch {
            workspaceRepository.observeRoots().collect { roots ->
                val allFiles = roots.filter { !it.isDirectory }
                _documents.value = allFiles.map { it.toDocument() }
            }
        }

        viewModelScope.launch {
            workspaceRepository.observeRoots().collect { entries ->
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
                val children = workspaceRepository.observeChildren(folder.id).first()
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
                val uuid = java.util.UUID.randomUUID().toString()
                val matPath = "/$name"
                workspaceRepository.createDirectory(
                    uuid = uuid,
                    name = name,
                    parentUuid = _workspaceRootUuid.value,
                    physicalRootPath = ragWorkspaceRoot.absolutePath,
                    materializedPath = matPath
                )
            } catch (_: Exception) { }
        }
    }

    fun loadDocumentsForFolder(folderId: String) {
        viewModelScope.launch {
            try {
                workspaceRepository.observeChildren(folderId).collect { entries ->
                    _documents.value = entries.filter { !it.isDirectory }.map { it.toDocument() }
                }
            } catch (_: Exception) { }
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            try {
                // 获取文件夹条目以获取其路径（用于级联删除子内容）
                val entry = workspaceRepository.getByUuid(id)
                if (entry != null && entry.isDirectory) {
                    // 先递归删除子树中的子文件（permanentDelete 内部已处理子树）
                    val childList = try {
                        workspaceRepository.observeChildren(id).first()
                    } catch (_: Exception) { emptyList() }
                    for (child in childList) {
                        try {
                            workspaceRepository.permanentDelete(child.uuid)
                        } catch (_: Exception) { }
                    }
                }
                workspaceRepository.permanentDelete(id)
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun renameFolder(id: String, newName: String) {
        viewModelScope.launch {
            try {
                val entry = workspaceRepository.getByUuid(id) ?: return@launch
                // 通过 DAO 直接更新 FileEntry 的名称字段
                val dao = app.database.fileEntryDao()
                val now = System.currentTimeMillis()
                val updatedMatPath = entry.materializedPath
                    .substringBeforeLast('/')
                    .let { if (it.isEmpty()) "/$newName" else "$it/$newName" }
                dao.update(
                    entry.copy(
                        name = newName,
                        materializedPath = updatedMatPath,
                        updatedAt = now
                    )
                )
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun deleteFolder(id: String) = deleteCollection(id)

    fun deleteDocuments(ids: List<String>) {
        viewModelScope.launch {
            try {
                for (id in ids) {
                    workspaceRepository.permanentDelete(id)
                }
                loadStats()
            } catch (_: Exception) { }
        }
    }

    fun importDocuments(uris: List<android.net.Uri>, folderId: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val rootPath = ragWorkspaceRoot.absolutePath
            val parentUuid = folderId ?: _workspaceRootUuid.value
            val importedFiles = mutableListOf<FileEntry>()

            for (uri in uris) {
                var fileName: String? = null
                try {
                    val contentResolver = app.contentResolver
                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                    fileName = resolveFileName(uri, contentResolver)
                        ?: "imported_${System.currentTimeMillis()}.bin"

                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: continue

                    // 根据文件类型选择提取器
                    val content = when {
                        mimeType == "application/pdf" || fileName!!.endsWith(".pdf", ignoreCase = true) -> {
                            val result = com.promenar.nexara.data.rag.PdfExtractor.extract(app, uri)
                            result.getOrNull()?.text ?: String(bytes, Charsets.UTF_8)
                        }
                        mimeType.contains("wordprocessingml") || fileName!!.endsWith(".docx", ignoreCase = true) -> {
                            com.promenar.nexara.data.rag.DocumentImporter.extractWord(uri, contentResolver)
                        }
                        mimeType.startsWith("text/") || isPlainTextFile(fileName!!) -> {
                            String(bytes, Charsets.UTF_8)
                        }
                        else -> {
                            // 二进制文件：存储原始内容
                            "Binary file: ${fileName!!} (${formatBytes(bytes.size.toLong())})"
                        }
                    }

                    val uuid = java.util.UUID.randomUUID().toString()
                    val matPath = "/${fileName}"

                    val entry = workspaceRepository.createFile(
                        uuid = uuid,
                        name = fileName!!,
                        content = content,
                        parentUuid = parentUuid,
                        physicalRootPath = rootPath,
                        materializedPath = matPath
                    )

                    importedFiles.add(entry)

                    // 触发自动向量化
                    if (!content.startsWith("Binary file:") && !content.startsWith("[Error]")) {
                        app.vectorizationQueue.enqueueDocument(
                            docId = uuid,
                            docTitle = fileName!!,
                            content = content
                        )
                    }
                } catch (e: Exception) {
                    _lastQueueError.value = "导入失败: ${fileName ?: "未知文件"} - ${e.message?.take(80)}"
                }
            }

            if (importedFiles.isNotEmpty()) {
                _lastQueueError.value = null
                loadStats()
            }
        }
    }

    /** 手动触发重新索引 */
    fun reindexFile(uuid: String) {
        viewModelScope.launch {
            try {
                val entry = workspaceRepository.getByUuid(uuid) ?: return@launch
                val result = fileOperationRepository.readFileRange(uuid)
                if (result.content.isNotBlank()) {
                    app.vectorizationQueue.enqueueDocument(
                        docId = uuid,
                        docTitle = entry.name,
                        content = result.content
                    )
                }
            } catch (_: Exception) { }
        }
    }

    /** 批量重新索引 */
    fun reindexDocuments(uuids: Collection<String>) {
        viewModelScope.launch {
            uuids.forEach { uuid ->
                try {
                    val entry = workspaceRepository.getByUuid(uuid) ?: return@forEach
                    val result = fileOperationRepository.readFileRange(uuid)
                    if (result.content.isNotBlank()) {
                        app.vectorizationQueue.enqueueDocument(
                            docId = uuid,
                            docTitle = entry.name,
                            content = result.content
                        )
                    }
                } catch (_: Exception) { }
            }
        }
    }

    /** 移动文件到指定目录 */
    fun moveFile(uuid: String, targetParentUuid: String) {
        viewModelScope.launch {
            try {
                val newParent = targetParentUuid.ifEmpty { null }
                workspaceRepository.updateParent(uuid, targetParentUuid)
            } catch (_: Exception) { }
        }
    }

    /** 触发单个文件的知识图谱抽取 */
    fun extractKG(uuid: String) {
        viewModelScope.launch {
            try {
                val entry = workspaceRepository.getByUuid(uuid) ?: return@launch
                val content = fileOperationRepository.readFileRange(uuid).content
                if (content.isNotBlank()) {
                    app.vectorizationQueue.enqueueDocument(
                        docId = uuid,
                        docTitle = entry.name,
                        content = content,
                        kgStrategy = "full"
                    )
                }
            } catch (_: Exception) { }
        }
    }

    /** 复制文件 */
    fun copyFile(uuid: String) {
        viewModelScope.launch {
            try {
                val entry = workspaceRepository.getByUuid(uuid) ?: return@launch
                val content = fileOperationRepository.readFileRange(uuid).content
                val newUuid = java.util.UUID.randomUUID().toString()
                val newName = "${entry.name.substringBeforeLast('.')} - 副本.${entry.name.substringAfterLast('.', "")}"
                val rootPath = ragWorkspaceRoot.absolutePath
                workspaceRepository.createFile(
                    uuid = newUuid,
                    name = newName,
                    content = content,
                    parentUuid = entry.parentUuid,
                    physicalRootPath = rootPath,
                    materializedPath = "/$newName"
                )
            } catch (_: Exception) { }
        }
    }

    /** 判断是否为纯文本文件扩展名 */
    private fun isPlainTextFile(fileName: String): Boolean {
        val textExtensions = setOf("txt", "md", "json", "xml", "csv", "yml", "yaml", "log", "kt", "java", "py", "js", "ts", "html", "css", "sql")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in textExtensions
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return "${"%.1f".format(value)} ${units[digitGroups]}"
    }

    /**
     * 从 Content URI 解析文件显示名。
     * 优先使用 OpenableColumns.DISPLAY_NAME，回退到 URI 最后一段。
     */
    private fun resolveFileName(uri: android.net.Uri, resolver: android.content.ContentResolver): String? {
        var name: String? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIdx)
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        return name
    }

    fun extractKnowledgeGraph(docId: String, kgStrategy: String) {
        viewModelScope.launch {
            try {
                val result = fileOperationRepository.readFileRange(docId)
                val content = result.content
                kgRepository.extractFromContent(content, docId)
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
