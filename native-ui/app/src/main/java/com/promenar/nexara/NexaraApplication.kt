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
import com.promenar.nexara.utils.NexaraLogger
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.ImageService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MicroGraphExtractor
import com.promenar.nexara.data.rag.MicroGraphKgAdapter
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.RerankClient
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ProviderConfig
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

class NexaraApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        lateinit var instance: NexaraApplication
            private set
    }

    val database: NexaraDatabase by lazy {
        Room.databaseBuilder(this, NexaraDatabase::class.java, "nexara.db")
            .addMigrations(
                NexaraDatabase.MIGRATION_4_5,
                NexaraDatabase.MIGRATION_6_7,
                NexaraDatabase.MIGRATION_7_8,
                NexaraDatabase.MIGRATION_8_9,
                NexaraDatabase.MIGRATION_9_10,
                NexaraDatabase.MIGRATION_10_11,
                NexaraDatabase.MIGRATION_11_12,
                NexaraDatabase.MIGRATION_12_13,
                NexaraDatabase.MIGRATION_13_14,
                NexaraDatabase.MIGRATION_14_15,
                NexaraDatabase.MIGRATION_15_16
            )
            .fallbackToDestructiveMigration()
            .setQueryCallback(
                androidx.room.RoomDatabase.QueryCallback { sqlQuery, bindArgs ->
                    if (com.promenar.nexara.BuildConfig.DEBUG) {
                        try {
                            if (sqlQuery.contains("Message", ignoreCase = true) || 
                                sqlQuery.contains("TaskNodeEntity", ignoreCase = true) ||
                                sqlQuery.contains("Session", ignoreCase = true)
                            ) {
                                val json = org.json.JSONObject().apply {
                                    put("sql", sqlQuery)
                                    val argsArray = org.json.JSONArray()
                                    bindArgs.forEach { argsArray.put(it?.toString() ?: "null") }
                                    put("bindArgs", argsArray)
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
        TaskRepository(database.taskNodeDao())
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
            register(WebSearchSkill(this@NexaraApplication, httpClient))
            register(WebSearchTavilySkill(this@NexaraApplication, httpClient))
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

    private lateinit var _llmProvider: MutableStateFlow<LlmProvider>
    val llmProvider: LlmProvider get() = _llmProvider.value
    val llmProviderFlow: StateFlow<LlmProvider> get() = _llmProvider

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

        // 初始化统一数据源（必须在 buildProviderFromPrefs 之前）
        ProviderManager.init(this)

        _llmProvider = MutableStateFlow(buildProviderFromPrefs())

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

    private var _embeddingClient: EmbeddingClient? = null
    val embeddingClient: EmbeddingClient
        get() = _embeddingClient ?: buildEmbeddingClient().also { _embeddingClient = it }

    private fun buildEmbeddingClient(): EmbeddingClient {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        // 1. 优先读取显式手动配置的 embedding_base_url (nexara_provider)
        var baseUrl = prefs.getString("embedding_base_url", "") ?: ""
        var apiKey = prefs.getString("embedding_api_key", "") ?: ""
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
        
        // 3. 兜底：回退到主 LLM 提供商配置
        if (baseUrl.isBlank()) {
            baseUrl = prefs.getString("base_url", "") ?: ""
            apiKey = prefs.getString("api_key", "") ?: ""
            if (baseUrl.isNotBlank()) resolvedBy = "main-provider"
        }
        
        // 4. 二次兜底：遍历所有已配置提供商（覆盖纯额外提供商场景）
        if (baseUrl.isBlank()) {
            val pm = ProviderManager.getInstance()
            for (provider in pm.providers.value) {
                val config = pm.getProviderConfig(provider.id)
                if (config != null && config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) {
                    baseUrl = config.baseUrl
                    apiKey = config.apiKey
                    resolvedBy = "any-provider-fallback(${provider.id})"
                    break
                }
            }
        }
        
        val model = prefs.getString("embedding_model", "")?.ifBlank { presetModel } ?: presetModel
        NexaraLogger.log("[EmbeddingClient] 构建: model=$model resolvedBy=$resolvedBy baseUrlSet=${baseUrl.isNotBlank()} apiKeySet=${apiKey.isNotBlank()} baseUrl=${if (baseUrl.isNotBlank()) baseUrl.take(50) + "..." else "(empty)"}")
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
        var apiKey = prefs.getString("embedding_api_key", "") ?: ""
        
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
            apiKey = prefs.getString("api_key", "") ?: ""
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
        if (key == "base_url" || key == "api_key" || key == "embedding_base_url" || key == "embedding_api_key") {
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
        // 额外提供商配置变更（extra_provider_N_base_url/api_key 等）也需要重建客户端
        if (key?.startsWith("extra_provider_") == true && (key.endsWith("_base_url") || key.endsWith("_api_key"))) {
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
        WebSearchContextProvider(this, httpClient)
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
        ProviderManager.getInstance().updateMainProvider(protocolType, baseUrl, apiKey, model, name)
        // 重建 LlmProvider
        _llmProvider.value = when (protocolType) {
            is ProtocolType.Local -> LlmProvider.local(localInferenceEngine, model)
            else -> LlmProvider.builder()
                .protocolType(protocolType)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .build()
        }
        // 同步重建 Embedding / Rerank 客户端（使用最新的 baseUrl/apiKey）
        rebuildEmbeddingClient()
        rebuildRerankClient()
        _vectorizationQueue = null  // 让 VectorizationQueue 下次访问时重新捕获新的 embeddingClient
        _unifiedLlmClient = null
    }

    fun switchToLocalProvider(modelName: String = "") {
        _llmProvider.value = LlmProvider.local(localInferenceEngine, modelName)
    }

    fun getSavedProviderConfig(): ProviderConfig? {
        return ProviderManager.getInstance().getMainProviderConfig()
    }

    private fun buildUnifiedLlmClient(): com.promenar.nexara.data.remote.UnifiedLlmClient? {
        val config = getSavedProviderConfig() ?: return null
        if (config.apiKey.isBlank() && config.protocolType !is ProtocolType.Local) return null
        val uConfig = com.promenar.nexara.data.remote.UnifiedProviderConfig(
            protocolType = config.protocolType,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            defaultModel = config.model
        )
        val middlewares = if (com.promenar.nexara.BuildConfig.DEBUG) {
            listOf(com.promenar.nexara.data.remote.middleware.MetroLoggingMiddleware())
        } else {
            emptyList()
        }
        return com.promenar.nexara.data.remote.UnifiedLlmClient(uConfig, middlewares)
    }

    private fun buildProviderFromPrefs(): LlmProvider {
        val config = getSavedProviderConfig()
        return if (config != null && config.apiKey.isNotBlank()) {
            if (config.protocolType is ProtocolType.Local) {
                LlmProvider.local(localInferenceEngine, config.model)
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
