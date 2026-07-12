package com.promenar.nexara

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.promenar.nexara.data.local.inference.LocalInferenceEngine
import com.promenar.nexara.data.local.inference.SlotType
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.backup.BackupRuntime
import com.promenar.nexara.utils.NexaraLogger
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.ImageService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MicroGraphExtractor
import com.promenar.nexara.data.rag.MicroGraphKgAdapter
import com.promenar.nexara.data.rag.QueryRewriter
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.RerankClient
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.security.AndroidKeystoreSecretStore
import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.model.toCredentialUpdate
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.FileOperationRepository
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.data.repository.VectorRepository
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.ui.chat.manager.WebSearchContextProvider
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import com.promenar.nexara.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.ModularSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.UserSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.skills.CalculatorSkill
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSearXNGSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchTavilySkill
import com.promenar.nexara.ui.chat.manager.skills.WebFetchSkill
import com.promenar.nexara.ui.chat.manager.skills.CreateToolSkill
import com.promenar.nexara.ui.chat.manager.skills.ImageGenerationSkill
import com.promenar.nexara.ui.chat.manager.skills.FileReadSkill
import com.promenar.nexara.ui.chat.manager.skills.FileWriteSkill
import com.promenar.nexara.ui.chat.manager.skills.FileDiffSkill
import com.promenar.nexara.ui.chat.manager.skills.FilePatchSkill
import com.promenar.nexara.ui.chat.manager.skills.FileListSkill
import com.promenar.nexara.ui.chat.manager.skills.FileSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.ExecJsSkill
import com.promenar.nexara.ui.chat.manager.skills.InitializePlanSkill
import com.promenar.nexara.ui.chat.manager.skills.UpdatePlanSkill
import com.promenar.nexara.ui.chat.manager.skills.GetPlanSkill
import com.promenar.nexara.ui.chat.manager.skills.DropPlanSkill
import com.promenar.nexara.data.repository.TaskRepository
import com.promenar.nexara.data.repository.SkillRepository
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.data.repository.TokenStatsRepository
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.util.LocaleHelper
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

open class NexaraApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        lateinit var instance: NexaraApplication
            private set
    }

    open val secretStore: SecretStore by lazy { AndroidKeystoreSecretStore(this) }

    lateinit var backupRuntime: BackupRuntime
        private set

    val database: NexaraDatabase by lazy {
        Room.databaseBuilder(this, NexaraDatabase::class.java, "nexara_v2.db")
            .setQueryCallback(
                androidx.room.RoomDatabase.QueryCallback { sqlQuery, bindArgs ->
                    if (com.promenar.nexara.BuildConfig.DEBUG) {
                        try {
                            if (sqlQuery.contains("Message", ignoreCase = true) || 
                                sqlQuery.contains("TaskNodeEntity", ignoreCase = true) ||
                                sqlQuery.contains("Session", ignoreCase = true)
                            ) {
                                val json = org.json.JSONObject().apply {
                                    put("operation", sqlQuery.trimStart().substringBefore(' ').uppercase())
                                    put("argumentCount", bindArgs.size)
                                }
                                android.util.Log.d("NEXARA_METRO", "EVENT_START|DB_QUERY|${json}|EVENT_END")
                            }
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                },
                java.util.concurrent.Executors.newSingleThreadExecutor()
            )
            .build()
    }

    val localInferenceEngine: LocalInferenceEngine by lazy {
        LocalInferenceEngine(this)
    }

    val agentRepository: com.promenar.nexara.data.repository.AgentRepository by lazy {
        com.promenar.nexara.data.repository.AgentRepository(database.agentDao())
    }

    val configResolver: com.promenar.nexara.domain.usecase.AgentConfigResolver by lazy {
        com.promenar.nexara.domain.usecase.AgentConfigResolver(
            getSharedPreferences("nexara_settings", MODE_PRIVATE)
        )
    }

    val createAgentUseCase: com.promenar.nexara.domain.usecase.CreateAgentUseCase by lazy {
        com.promenar.nexara.domain.usecase.CreateAgentUseCase(agentRepository)
    }

    val sessionRepository: ISessionRepository by lazy {
        SessionRepository(database.sessionDao(), database.messageDao())
    }

    val messageRepository: IMessageRepository by lazy {
        MessageRepository(database.messageDao())
    }

    val chatStore: ChatStore by lazy { ChatStore() }

    private var _vectorRepository: com.promenar.nexara.domain.repository.IVectorRepository? = null
    val vectorRepository: com.promenar.nexara.domain.repository.IVectorRepository
        get() = _vectorRepository ?: VectorRepository(database.vectorDao(), embeddingClient).also { _vectorRepository = it }

    val skillRepository: ISkillRepository by lazy {
        com.promenar.nexara.data.repository.SkillRepository(database.skillDao())
    }

    val tokenStatsRepository: ITokenStatsRepository by lazy {
        TokenStatsRepository(database.messageDao())
    }

    val workspaceRepository: com.promenar.nexara.domain.repository.IWorkspaceRepository by lazy {
        WorkspaceRepository(database.fileEntryDao(), database.workspaceSeqDao())
    }

    val fileOperationRepository: com.promenar.nexara.domain.repository.IFileOperationRepository by lazy {
        FileOperationRepository(database.fileEntryDao())
    }

    val taskRepository: com.promenar.nexara.domain.repository.ITaskRepository by lazy {
        TaskRepository(database.taskNodeDao(), database)
    }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addInterceptor(com.promenar.nexara.utils.MetroLogInterceptor())
            }
        }
    }

    val presetSkillRegistry: DefaultSkillRegistry by lazy {
        DefaultSkillRegistry().apply {
            register(CalculatorSkill())
            register(WebSearchSkill(this@NexaraApplication, httpClient, secretStore))
            register(WebSearchTavilySkill(this@NexaraApplication, httpClient, secretStore))
            register(WebSearchSearXNGSkill(this@NexaraApplication, httpClient))
            register(WebFetchSkill(httpClient))
            register(CreateToolSkill(database.skillDao()))
            register(ImageGenerationSkill(this@NexaraApplication, ProviderManager.getInstance()))
            register(FileReadSkill(fileOperationRepository))
            register(FileWriteSkill(fileOperationRepository))
            register(FileDiffSkill(fileOperationRepository))
            register(FilePatchSkill(fileOperationRepository))
            register(FileListSkill(workspaceRepository))
            register(FileSearchSkill(workspaceRepository))
            register(ExecJsSkill(this@NexaraApplication))
            register(InitializePlanSkill(taskRepository))
            register(UpdatePlanSkill(taskRepository))
            register(GetPlanSkill(taskRepository))
            register(DropPlanSkill(taskRepository))
        }
    }

    val userSkillRegistry: UserSkillRegistry by lazy {
        UserSkillRegistry(skillRepository as SkillRepository)
    }

    val mcpSkillRegistry: McpSkillRegistry by lazy {
        McpSkillRegistry(skillRepository as SkillRepository, httpClient)
    }

    val skillRegistry: SkillRegistry by lazy {
        ModularSkillRegistry(
            listOf(presetSkillRegistry, userSkillRegistry, mcpSkillRegistry)
        )
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("nexara_provider", MODE_PRIVATE)
    }

    private val _providerConfigurationVersion = MutableStateFlow(0L)
    val providerConfigurationVersion: StateFlow<Long> = _providerConfigurationVersion
    private var localProviderOverride: String? = null
    val llmProvider: LlmProvider get() = buildCredentialResolvingProvider()

    private var _unifiedLlmClient: com.promenar.nexara.data.remote.UnifiedLlmClient? = null
    val unifiedLlmClient: com.promenar.nexara.data.remote.UnifiedLlmClient?
        get() = _unifiedLlmClient ?: buildUnifiedLlmClient().also { _unifiedLlmClient = it }

    var hapticEnabled: Boolean = true
        internal set

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    override fun attachBaseContext(base: Context) {
        val lang = LocaleHelper.getSavedLanguage(base)
        super.attachBaseContext(LocaleHelper.applyLanguage(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.promenar.nexara.utils.NexaraLogger.init(this)

        // 初始化应用级别的 WorkSpace 物理目录
        val workSpaceDir = java.io.File(filesDir, "WorkSpace")
        if (!workSpaceDir.exists()) {
            workSpaceDir.mkdirs()
        }

        // 安全恢复必须在 ProviderManager、listener、本地模型与向量化等 writer 启动前同步完成。
        // recovery 在当前启动调用链同步等待，完成前绝不开放 writer；失败抛出脱敏异常并中止初始化。
        backupRuntime = createBackupRuntime()
        backupRuntime.recoverBeforeWriters()

        // 初始化统一数据源（必须在 buildProviderFromPrefs 之前）
        val providerManager = ProviderManager.init(this, secretStore)

        CoroutineScope(Dispatchers.Default).launch {
            providerManager.configurationChanges.collect {
                _providerConfigurationVersion.value += 1
                rebuildEmbeddingClient()
                rebuildRerankClient()
                _vectorizationQueue = null
                _unifiedLlmClient = null
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val mainPath = localInferenceEngine.mainSlot.value.modelPath
                if (mainPath != null) {
                    prefs.edit().putString("last_local_model", mainPath).apply()
                }
            }
        })

        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        hapticEnabled = settingsPrefs.getBoolean("haptic_enabled", true)
        if (settingsPrefs.getBoolean("local_models_enabled", false) &&
            settingsPrefs.getBoolean("local_auto_load", false)) {
            val lastModel = prefs.getString("last_local_model", null)
            if (lastModel != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    localInferenceEngine.loadModel(SlotType.MAIN, lastModel)
                }
            }
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            vectorizationQueue.resumeInterruptedTasks()
        }

        prefs.registerOnSharedPreferenceChangeListener(providerListener)
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
    }

    /** 仅供测试应用替换 AndroidKeyStore/真实文件系统依赖；生产始终使用安全 runtime。 */
    protected open fun createBackupRuntime(): BackupRuntime =
        BackupRuntime.createAndroid(this, database, secretStore)

    private var _embeddingClient: EmbeddingClient? = null
    val embeddingClient: EmbeddingClient
        get() = _embeddingClient ?: buildEmbeddingClient().also { _embeddingClient = it }

    private fun buildEmbeddingClient(): EmbeddingClient {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        // 1. 优先读取显式手动配置的 embedding_base_url (nexara_provider)
        var baseUrl = prefs.getString("embedding_base_url", "") ?: ""
        var apiKey = readSecret(SecretCatalog.embeddingApiKey)
        var resolvedBy = if (baseUrl.isNotBlank()) "manual" else ""
        
        // 2. 如果手动配置为空，则根据预设模型自动查找所属提供商配置
        val presetModel = settingsPrefs.getString("preset_embedding_model", "") ?: ""
        if (baseUrl.isBlank() && presetModel.isNotBlank()) {
            val config = ProviderManager.getInstance().getProviderConfigByModelId(presetModel)
            if (config != null) {
                baseUrl = config.baseUrl
                apiKey = config.apiKey
                resolvedBy = "preset-model"
            }
        }
        
        val model = prefs.getString("embedding_model", "")?.ifBlank { presetModel } ?: presetModel
        NexaraLogger.log("[EmbeddingClient] 构建: model=$model resolvedBy=$resolvedBy baseUrlSet=${baseUrl.isNotBlank()} apiKeySet=${apiKey.isNotBlank()}")
        return EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model, localEngine = localInferenceEngine)
    }

    fun rebuildEmbeddingClient() {
        _embeddingClient = buildEmbeddingClient()
        rebuildMemoryManager()
        rebuildGraphExtractor()
        _vectorRepository = null
        _imageService = null
        _microGraphExtractor = null
        _kgProvider = null
    }

    private var _rerankClient: RerankClient? = null
    val rerankClient: RerankClient
        get() = _rerankClient ?: buildRerankClient().also { _rerankClient = it }

    private fun buildRerankClient(): RerankClient {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        var baseUrl = prefs.getString("embedding_base_url", "") ?: ""
        var apiKey = readSecret(SecretCatalog.embeddingApiKey)
        
        val presetModel = settingsPrefs.getString("preset_rerank_model", "") ?: ""
        if (baseUrl.isBlank() && presetModel.isNotBlank()) {
            val config = ProviderManager.getInstance().getProviderConfigByModelId(presetModel)
            if (config != null) {
                baseUrl = config.baseUrl
                apiKey = config.apiKey
            }
        }
        
        if (baseUrl.isBlank()) {
            baseUrl = prefs.getString("base_url", "") ?: ""
            apiKey = getSavedProviderConfig()?.apiKey.orEmpty()
        }
        
        val savedConfig = getSavedProviderConfig()
        val maxPerCall = ragConfigPersistence.loadFullConfig().rerankMaxPerCall
        NexaraLogger.log("[RerankClient] 构建: model=$presetModel hasBaseUrl=${baseUrl.isNotBlank()} maxPerCall=$maxPerCall")
        return RerankClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = presetModel,
            llmProtocol = savedConfig?.let {
                try { llmProvider.protocol } catch (_: Exception) { null }
            },
            llmModelId = savedConfig?.model,
            maxPerCall = maxPerCall
        )
    }

    fun rebuildRerankClient() {
        _rerankClient = buildRerankClient()
        rebuildMemoryManager()
    }

    private val providerListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "base_url" || key == "embedding_base_url") {
            rebuildEmbeddingClient()
            rebuildRerankClient()
            _vectorizationQueue = null
        }
        if (key == "model") {
            rebuildEmbeddingClient()
            _vectorizationQueue = null
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "preset_embedding_model" || key == "all_models" || key == "enabled_models") {
            rebuildEmbeddingClient()
            _vectorizationQueue = null
        }
        if (key == "preset_rerank_model" || key == "all_models") {
            rebuildRerankClient()
        }
        // 非敏感额外提供商地址变化仍由 prefs 监听；凭证变化走 ProviderManager.configurationChanges。
        if (key?.startsWith("extra_provider_") == true && key.endsWith("_base_url")) {
            rebuildEmbeddingClient()
            rebuildRerankClient()
            _vectorizationQueue = null
        }
    }

    private var _imageService: ImageService? = null
    val imageService: ImageService
        get() = _imageService ?: ImageService(
            context = this,
            embeddingClient = embeddingClient,
            vectorStore = vectorStore,
            textSplitter = textSplitter
        ).also { _imageService = it }

    val vectorStore: VectorStore by lazy {
        VectorStore(
            vectorDao = database.vectorDao(),
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao()
        )
    }

    val graphStore: GraphStore by lazy {
        GraphStore(
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao()
        )
    }

    val keywordSearcher: KeywordSearcher by lazy {
        KeywordSearcher(vectorDao = database.vectorDao())
    }

    /** RAG 配置持久化读取器，与 RagViewModel 共用同一个 SharedPreferences */
    private val ragConfigPersistence: RagConfigPersistence by lazy {
        RagConfigPersistence(getSharedPreferences("rag_settings", MODE_PRIVATE))
    }

    private var _memoryManager: MemoryManager? = null
    val memoryManager: MemoryManager
        get() = _memoryManager ?: MemoryManager(
            vectorStore = vectorStore,
            keywordSearcher = keywordSearcher,
            graphStore = graphStore,
            embeddingClient = embeddingClient,
            rerankClient = rerankClient,
            queryRewriter = QueryRewriter(llmProvider.protocol, modelId = getSavedProviderConfig()?.model),
            ragConfig = ragConfigPersistence.loadFullConfig()  // P0: 从用户保存的配置读取，不再硬编码默认值
        ).also { _memoryManager = it }

    fun rebuildMemoryManager() {
        _memoryManager = null
    }

    val textSplitter: RecursiveCharacterTextSplitter by lazy {
        RecursiveCharacterTextSplitter(chunkSize = 800, chunkOverlap = 100)
    }

    private var _microGraphExtractor: MicroGraphExtractor? = null
    val microGraphExtractor: MicroGraphExtractor
        get() = _microGraphExtractor ?: MicroGraphExtractor(
            protocol = llmProvider.protocol,
            graphStore = graphStore,
            jitCacheDao = database.kgJitCacheDao(),
            modelId = getSavedProviderConfig()?.model
        ).also { _microGraphExtractor = it }

    private var _kgProvider: KgProvider? = null
    val kgProvider: KgProvider
        get() = _kgProvider ?: MicroGraphKgAdapter(microGraphExtractor).also { _kgProvider = it }

    val webSearchContextProvider: WebSearchProvider by lazy {
        WebSearchContextProvider(this, httpClient, secretStore)
    }

    private var _graphExtractor: com.promenar.nexara.data.rag.GraphExtractor? = null
    val graphExtractor: com.promenar.nexara.data.rag.GraphExtractor
        get() = _graphExtractor ?: run {
            val config = ragConfigPersistence.loadFullConfig()
            // P1: kgExtractionModel 优先 → 降级 summary model → 降级主 LLM 模型
            val effectiveModel = config.kgExtractionModel?.takeIf { it.isNotBlank() }
                ?: getSharedPreferences("nexara_settings", MODE_PRIVATE)
                    .getString("preset_summary_model", "")?.takeIf { it.isNotBlank() }
                ?: getSavedProviderConfig()?.model
            // P1: kgExtractionPrompt 优先 → 降级 DEFAULT_KG_PROMPT
            val effectivePrompt = config.kgExtractionPrompt?.takeIf { it.isNotBlank() }
                ?: com.promenar.nexara.data.rag.GraphExtractor.DEFAULT_KG_PROMPT

            com.promenar.nexara.data.rag.GraphExtractor(
                protocol = llmProvider.protocol,
                graphStore = graphStore,
                modelId = effectiveModel,
                systemPrompt = effectivePrompt,
                chunkSize = config.docChunkSize.coerceAtLeast(400),
                chunkOverlap = config.chunkOverlap.coerceAtMost(200),
                timeoutMs = (config.kgExtractionTimeoutSeconds.coerceIn(5, 300) * 1000L)
            ).also { _graphExtractor = it }
        }

    fun rebuildGraphExtractor() {
        _graphExtractor = null
    }

    private var _vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue? = null
    val vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue
        get() = _vectorizationQueue ?: com.promenar.nexara.data.rag.VectorizationQueue(
            vectorStore = vectorStore,
            embeddingClient = embeddingClient,
            graphExtractor = graphExtractor,
            vectorDao = database.vectorDao(),
            vectorizationTaskDao = database.vectorizationTaskDao(),
            fileEntryDao = database.fileEntryDao()
        ).also { _vectorizationQueue = it }

    val defaultAgents: List<com.promenar.nexara.domain.model.Agent> by lazy {
        listOf(
            com.promenar.nexara.domain.model.Agent(id = "default", name = "Nexara 助手", description = "通用 AI 助手，支持流式对话与知识检索", icon = "✨", color = "#C0C1FF", isPinned = true),
            com.promenar.nexara.domain.model.Agent(id = "coder", name = "编程专家", description = "精通全栈开发与架构设计", icon = "💻", color = "#6366F1"),
            com.promenar.nexara.domain.model.Agent(id = "writer", name = "创意写作", description = "文学创作、翻译与润色", icon = "📝", color = "#10B981")
        )
    }

    fun updateProvider(
        protocolType: ProtocolType,
        baseUrl: String,
        apiKey: String,
        model: String,
        name: String? = null
    ) {
        // 委托 ProviderManager 持久化
        ProviderManager.getInstance().updateMainProvider(
            protocolType,
            baseUrl,
            apiKey.toCredentialUpdate(),
            model,
            name,
        )
        localProviderOverride = null
        _providerConfigurationVersion.value += 1
        // 同步重建 Embedding / Rerank 客户端（使用最新的 baseUrl/apiKey）
        rebuildEmbeddingClient()
        rebuildRerankClient()
        _vectorizationQueue = null  // 让 VectorizationQueue 下次访问时重新捕获新的 embeddingClient
        _unifiedLlmClient = null
    }

    fun switchToLocalProvider(modelName: String = "") {
        localProviderOverride = modelName
        _providerConfigurationVersion.value += 1
    }

    fun getSavedProviderConfig(): ProviderConfig? {
        return ProviderManager.getInstance().getMainProviderConfig()
    }

    private fun buildUnifiedLlmClient(): com.promenar.nexara.data.remote.UnifiedLlmClient? {
        val summary = ProviderManager.getInstance().getProviderSummary("default") ?: return null
        if (!summary.hasApiKey && !summary.hasVertexCredentials && summary.protocolType !is ProtocolType.Local) return null
        val middlewares = if (com.promenar.nexara.BuildConfig.DEBUG) {
            listOf(com.promenar.nexara.data.remote.middleware.MetroLoggingMiddleware())
        } else {
            emptyList()
        }
        return com.promenar.nexara.data.remote.UnifiedLlmClient(
            providerConfigResolver = { buildUnifiedProviderConfig() },
            middlewares = middlewares,
        )
    }

    private fun buildUnifiedProviderConfig(): com.promenar.nexara.data.remote.UnifiedProviderConfig? {
        val config = getSavedProviderConfig() ?: return null
        if (config.apiKey.isBlank() && config.vertexServiceAccountJson.isBlank() && config.protocolType !is ProtocolType.Local) return null
        return com.promenar.nexara.data.remote.UnifiedProviderConfig(
            protocolType = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            defaultModel = config.model,
            serviceAccountJson = config.vertexServiceAccountJson,
            projectId = extractVertexProjectId(config.vertexServiceAccountJson),
        )
    }

    private fun buildCredentialResolvingProvider(): LlmProvider {
        localProviderOverride?.let { return LlmProvider.local(localInferenceEngine, it) }
        val summary = ProviderManager.getInstance().getProviderSummary("default")
        if (summary?.protocolType is ProtocolType.Local) {
            return LlmProvider.local(localInferenceEngine, summary.model)
        }
        val protocolType = summary?.protocolType ?: ProtocolType.OpenAI_ChatCompletions
        return LlmProvider.resolving(protocolType) { buildProviderFromPrefs().protocol }
    }

    private fun buildProviderFromPrefs(): LlmProvider {
        val config = getSavedProviderConfig()
        return if (config != null && (
                config.apiKey.isNotBlank() ||
                    config.vertexServiceAccountJson.isNotBlank() ||
                    config.protocolType is ProtocolType.Local
                )) {
            if (config.protocolType is ProtocolType.Local) {
                LlmProvider.local(localInferenceEngine, config.model)
            } else if (config.protocolType is ProtocolType.Google_VertexAI) {
                LlmProvider.builder()
                    .protocolType(config.protocolType)
                    .serviceAccountJson(config.vertexServiceAccountJson)
                    .projectId(extractVertexProjectId(config.vertexServiceAccountJson))
                    .model(config.model)
                    .build()
            } else {
                LlmProvider.builder()
                    .protocolType(config.protocolType)
                    .baseUrl(config.baseUrl)
                    .apiKey(config.apiKey)
                    .model(config.model)
                    .build()
            }
        } else {
            LlmProvider.builder()
                .protocolType(ProtocolType.OpenAI_ChatCompletions)
                .baseUrl("")
                .apiKey("")
                .model("")
                .build()
        }
    }

    private fun readSecret(id: SecretId): String =
        secretStore.get(id)?.toString(Charsets.UTF_8).orEmpty()

    private fun extractVertexProjectId(serviceAccountJson: String): String =
        Regex("\\\"project_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(serviceAccountJson)?.groupValues?.get(1).orEmpty()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                CoroutineScope(Dispatchers.IO).launch {
                    localInferenceEngine.unloadModel(SlotType.RERANK)
                    localInferenceEngine.unloadModel(SlotType.EMBEDDING)
                }
            }
        }
    }

    // ProviderConfig 已迁移至 data/model/ProviderModels.kt
}
