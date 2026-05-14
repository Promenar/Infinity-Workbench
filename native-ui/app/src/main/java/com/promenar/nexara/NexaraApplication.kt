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
import com.promenar.nexara.data.rag.EmbeddingClient
import com.promenar.nexara.data.rag.GraphStore
import com.promenar.nexara.data.rag.ImageService
import com.promenar.nexara.data.rag.KeywordSearcher
import com.promenar.nexara.data.rag.MemoryManager
import com.promenar.nexara.data.rag.MicroGraphExtractor
import com.promenar.nexara.data.rag.MicroGraphKgAdapter
import com.promenar.nexara.data.rag.RagConfiguration
import com.promenar.nexara.data.rag.RerankClient
import com.promenar.nexara.data.rag.RecursiveCharacterTextSplitter
import com.promenar.nexara.data.rag.VectorStore
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.remote.provider.LlmProvider
import com.promenar.nexara.data.repository.DocumentRepository
import com.promenar.nexara.data.repository.IMessageRepository
import com.promenar.nexara.data.repository.ISessionRepository
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.SessionRepository
import com.promenar.nexara.data.repository.VectorRepository
import com.promenar.nexara.ui.chat.manager.WebSearchContextProvider
import com.promenar.nexara.ui.chat.manager.WebSearchProvider
import com.promenar.nexara.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.ModularSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.UserSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import com.promenar.nexara.ui.chat.manager.skills.CalculatorSkill
import com.promenar.nexara.ui.chat.manager.skills.CurrentTimeSkill
import com.promenar.nexara.ui.chat.ChatStore
import com.promenar.nexara.ui.chat.manager.KgProvider
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchSearXNGSkill
import com.promenar.nexara.ui.chat.manager.skills.WebSearchTavilySkill
import com.promenar.nexara.ui.chat.manager.skills.CreateToolSkill
import com.promenar.nexara.ui.chat.manager.skills.ImageGenerationSkill
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
            .addMigrations(NexaraDatabase.MIGRATION_4_5)
            .fallbackToDestructiveMigration()
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
    
    val documentRepository: com.promenar.nexara.domain.repository.IDocumentRepository by lazy {
        DocumentRepository(database.documentDao(), database.folderDao())
    }

    val vectorRepository: com.promenar.nexara.domain.repository.IVectorRepository by lazy {
        VectorRepository(database.vectorDao(), embeddingClient)
    }

    val skillRepository: ISkillRepository by lazy {
        com.promenar.nexara.data.repository.SkillRepository(database.skillDao())
    }

    val tokenStatsRepository: ITokenStatsRepository by lazy {
        TokenStatsRepository(database.messageDao())
    }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    val presetSkillRegistry: DefaultSkillRegistry by lazy {
        DefaultSkillRegistry().apply {
            register(CurrentTimeSkill())
            register(CalculatorSkill())
            register(WebSearchSkill(this@NexaraApplication, httpClient))
            register(WebSearchTavilySkill(this@NexaraApplication, httpClient))
            register(WebSearchSearXNGSkill(this@NexaraApplication, httpClient))
            register(CreateToolSkill(database.skillDao()))
            register(ImageGenerationSkill(this@NexaraApplication, ProviderManager.getInstance()))
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
    }

    val embeddingClient: EmbeddingClient by lazy {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        // 优先读取专用的 embedding_* 键，为空时回退到主 LLM 提供商的 base_url/api_key
        val baseUrl = prefs.getString("embedding_base_url", "")?.ifBlank {
            prefs.getString("base_url", "") ?: ""
        } ?: ""
        val apiKey = prefs.getString("embedding_api_key", "")?.ifBlank {
            prefs.getString("api_key", "") ?: ""
        } ?: ""
        val presetModel = settingsPrefs.getString("preset_embedding_model", "") ?: ""
        val model = prefs.getString("embedding_model", "")?.ifBlank { presetModel } ?: presetModel
        EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
    }

    val rerankClient: RerankClient by lazy {
        val settingsPrefs = getSharedPreferences("nexara_settings", MODE_PRIVATE)
        val baseUrl = prefs.getString("embedding_base_url", "")?.ifBlank {
            prefs.getString("base_url", "") ?: ""
        } ?: ""
        val apiKey = prefs.getString("embedding_api_key", "")?.ifBlank {
            prefs.getString("api_key", "") ?: ""
        } ?: ""
        val presetModel = settingsPrefs.getString("preset_rerank_model", "") ?: ""
        val savedConfig = getSavedProviderConfig()
        RerankClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = presetModel,
            llmProtocol = savedConfig?.let {
                try { llmProvider.protocol } catch (_: Exception) { null }
            },
            llmModelId = savedConfig?.model
        )
    }

    val imageService: ImageService by lazy {
        ImageService(
            context = this,
            embeddingClient = embeddingClient,
            vectorStore = vectorStore,
            textSplitter = textSplitter
        )
    }

    val vectorStore: VectorStore by lazy {
        VectorStore(
            vectorDao = database.vectorDao(),
            kgNodeDao = database.kgNodeDao(),
            kgEdgeDao = database.kgEdgeDao(),
            documentDao = database.documentDao()
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

    val memoryManager: MemoryManager by lazy {
        MemoryManager(
            vectorStore = vectorStore,
            keywordSearcher = keywordSearcher,
            graphStore = graphStore,
            embeddingClient = embeddingClient,
            rerankClient = rerankClient,
            ragConfig = RagConfiguration()
        )
    }

    val textSplitter: RecursiveCharacterTextSplitter by lazy {
        RecursiveCharacterTextSplitter(chunkSize = 800, chunkOverlap = 100)
    }

    val microGraphExtractor: MicroGraphExtractor by lazy {
        MicroGraphExtractor(
            protocol = llmProvider.protocol,
            graphStore = graphStore,
            jitCacheDao = database.kgJitCacheDao(),
            modelId = getSavedProviderConfig()?.model
        )
    }

    val kgProvider: KgProvider by lazy {
        MicroGraphKgAdapter(microGraphExtractor)
    }

    val webSearchContextProvider: WebSearchProvider by lazy {
        WebSearchContextProvider(this, httpClient)
    }

    val graphExtractor: com.promenar.nexara.data.rag.GraphExtractor by lazy {
        com.promenar.nexara.data.rag.GraphExtractor(
            protocol = llmProvider.protocol,
            graphStore = graphStore,
            modelId = getSavedProviderConfig()?.model
        )
    }

    val vectorizationQueue: com.promenar.nexara.data.rag.VectorizationQueue by lazy {
        com.promenar.nexara.data.rag.VectorizationQueue(
            vectorStore = vectorStore,
            embeddingClient = embeddingClient,
            graphExtractor = graphExtractor,
            documentDao = database.documentDao(),
            vectorDao = database.vectorDao(),
            vectorizationTaskDao = database.vectorizationTaskDao()
        )
    }

    val documentImporter: com.promenar.nexara.data.rag.DocumentImporter by lazy {
        com.promenar.nexara.data.rag.DocumentImporter(
            context = this,
            documentDao = database.documentDao(),
            vectorizationQueue = vectorizationQueue
        )
    }

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
    }

    fun switchToLocalProvider(modelName: String = "") {
        _llmProvider.value = LlmProvider.local(localInferenceEngine, modelName)
    }

    fun getSavedProviderConfig(): ProviderConfig? {
        return ProviderManager.getInstance().getMainProviderConfig()
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
